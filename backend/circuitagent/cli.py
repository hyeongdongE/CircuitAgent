"""실행 순서를 한곳에서 보여주는 진입점: XLSX → QCV → Reference 통합 → 연결 조회.

예: python -m circuitagent.cli --bom fe.xlsx --netlist fe.qcv --component U16 --pin 5
회로 파일을 읽고 메모리에서 처리한다. 서버·DB·브라우저는 사용하지 않는다.
"""

import argparse
import json
from pathlib import Path

from .bom_parser import parse_bom
from .circuit_graph import CircuitGraph, assemble
from .models import wire
from .netlist_parser import QcvNetlistParser


def main(argv=None):
    """명령행 인자를 받아 파서들을 순서대로 호출하고, 결과를 JSON으로 출력한다."""
    parser = argparse.ArgumentParser(description="XLSX BOM + QCV Netlist 연결")
    parser.add_argument("--bom", type=Path, required=True, help="BOM XLSX 파일")
    parser.add_argument("--netlist", type=Path, required=True, help="QCV Netlist 파일")
    parser.add_argument("--bom-mapping", type=Path, help="특이한 XLSX 헤더/단위를 지정하는 JSON")
    parser.add_argument("--name", help="회로 이름; 생략하면 BOM 파일명")
    parser.add_argument("--version", default="v1", help="사용자가 지정한 설계 버전; 파일에서 추정하지 않음")
    parser.add_argument("--component", help="조회할 Reference, 예: U16")
    parser.add_argument("--pin", help="조회할 핀 번호; --component와 함께 사용")
    parser.add_argument("--output", type=Path, help="전체 회로 모델 JSON 저장 경로")
    args = parser.parse_args(argv)
    if args.pin and not args.component:
        parser.error("--pin requires --component")
    try:
        mapping = json.loads(args.bom_mapping.read_text(encoding="utf-8-sig")) if args.bom_mapping else None

        # 1. BOM은 사양을 제공한다. 이 단계에서는 Net/배선 연결을 만들지 않는다.
        bom = parse_bom(args.bom.read_bytes(), args.bom.name, mapping)
        # 2. Netlist는 어떤 부품의 어떤 핀이 같은 Net에 있는지 제공한다.
        netlist = QcvNetlistParser().parse(args.netlist.read_bytes(), args.netlist.name)
        # 3. 두 파일의 Reference가 정확히 같은 경우에만 사양과 연결 정보를 결합한다.
        design = assemble(args.name or args.bom.stem, args.version, bom, netlist)
        # 4. 통합 모델을 조회용 사전으로 인덱싱한다. Net을 서로 합치지 않는다.
        graph = CircuitGraph(design)
        result = {
            "bomComponentCount": len(bom.components),
            "netlistComponentCount": len(netlist.components),
            "matchedReferenceCount": len(set(bom.components) & set(netlist.components)),
            "componentCount": len(design.components),
            "pinCount": len(design.pins),
            "netCount": len(design.nets),
            "connectionCount": len(design.connections),
            "noMountCount": sum(c.assembly_status == "DNP" for c in design.components.values()),
            "terminatedPinCount": sum(p.disposition == "TERMINATED" for p in design.pins),
            "issues": design.issues,
        }
        if args.component:
            # 핀 번호는 문자열이다. A1 같은 핀도 있으므로 int로 변환하지 않는다.
            result["component"] = graph.get_component(args.component)
            result["specification"] = graph.get_component_specification(args.component)
            result["pins"] = graph.get_component_pins(args.component)
        if args.pin:
            context = graph.get_pin_context(args.component, args.pin)
            result["pinContext"] = context
            net = context["net"]
            # 같은 Net의 다른 핀과 부품을 반환한다. 주변 부품의 회로 기능은 판단하지 않는다.
            result["connectedPins"] = graph.get_connected_pins(net.id) if net else ()
            result["componentsOnNet"] = graph.get_components_on_net(net.id) if net else ()
        if args.output:
            # Decimal은 문자열로 저장한다. 원본 숫자를 float로 반올림하지 않는다.
            args.output.write_text(
                json.dumps(wire(design), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
        print(json.dumps(wire(result), ensure_ascii=False, indent=2))
    except (ValueError, LookupError, OSError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
