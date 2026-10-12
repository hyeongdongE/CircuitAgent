"""공개 PADS QCV의 NET/PIN 선언만 읽어 부품·핀·Net 연결 모델로 변환한다."""

import hashlib
import re

from .bom_parser import MAX_ROWS, MAX_VALUES, file_limit
from .models import (
    CircuitNet,
    ComponentInstance,
    ComponentPin,
    NetlistResult,
    PartSpecification,
    PinConnection,
    PinDisposition,
    PinKey,
    issue,
)


class QcvNetlistParser:
    """실제 Sparrow에서 확인한 NET/PIN 문법만 지원한다. PIN/TERM은 Net 연결이 아니다."""

    # NET/PIN 이름은 작은따옴표 안의 원문 그대로 보존한다.
    record = re.compile(r"(NET|PIN)\s*:\s*'([^']+)'\s+(.+)")
    # Reference 부분은 특수문자를 허용하며 마지막 -핀번호를 구분한다.
    # 단순 split("-")로 분리하면 하이픈이 포함된 Reference를 손상시킨다.
    terminal = re.compile(r"([/A-Za-z_][A-Za-z0-9_./()+:-]*)-([A-Za-z0-9_]+)")

    def parse(self, data, filename):
        """원본 bytes를 공통 부품/핀/Net/연결 모델로 변환한다. 잘못된 문법은 입력 오류로 알린다."""
        if not filename.lower().endswith(".qcv"):
            raise ValueError("Netlist input must be QCV")
        file_limit(data)
        text = data.decode("utf-8-sig", errors="strict")
        # 사전 키로 부품/핀/연결 중복을 제거한다. ambiguous 핀은 임의 Net에 연결하지 않는다.
        components, specs, pins, nets, connections, ambiguous, issues = {}, {}, {}, {}, {}, set(), []
        separate = terminated = tokens = 0
        section = "unspecified"
        for line, original in enumerate(text.splitlines(), 1):
            value = original.strip()
            if line > MAX_ROWS or len(value) > 100_000:
                raise ValueError(f"QCV line budget exceeded at {filename}:line:{line}")
            if not value:
                continue
            # 섹션 제목은 PIN의 원본 의미 구분과 출처 보존에 사용한다.
            if value.startswith("#"):
                section = value[1:].strip()
                continue
            record = self.record.fullmatch(value)
            if not record:
                raise ValueError(f"Expected quoted NET or PIN record at {filename}:line:{line}")
            kind, label, tail = record.groups()
            term = kind == "PIN" and tail.rstrip().endswith("(by TERM)")
            tail = tail.rstrip().removesuffix("(by TERM)").strip() if term else tail.strip()
            identifiers = tail.split()
            if not identifiers or kind == "PIN" and len(identifiers) != 1:
                raise ValueError("PIN requires one terminal and only optional (by TERM)")
            net = None
            # NET은 실제 공통 연결망, PIN은 별도 단자 선언이다.
            if kind == "NET":
                # QCV에 별도 Net 코드가 없으므로 원본 이름의 SHA256으로 안정적인 조회 키를 만든다.
                id_ = "qcv:" + hashlib.sha256(label.encode("utf-8")).hexdigest()
                net = CircuitNet(id=id_, name=label, source_path=f"{filename}:line:{line}")
                if id_ in nets:
                    issues.append(
                        issue(
                            "DUPLICATE_NET",
                            source=f"{filename}:line:{line}",
                            message="Repeated NET name; terminal sets merged",
                        )
                    )
                nets.setdefault(id_, net)
            else:
                separate += 1
                terminated += int(term)
            # R107-2 → PinKey(reference="R107", number="2").
            for token in identifiers:
                tokens += 1
                if tokens > MAX_VALUES:
                    raise ValueError("QCV terminal budget exceeded")
                terminal = self.terminal.fullmatch(token)
                if not terminal:
                    raise ValueError(f"Invalid terminal identifier at {filename}:line:{line}")
                ref, number = terminal.groups()  # 마지막 유효 단자 접미부만 분리한다.
                key = PinKey(reference=ref, number=number)
                # COMP 목록이 없어도 관측한 핀의 Reference로 부품을 생성한다.
                if ref not in components:
                    spec = PartSpecification()
                    specs[spec.id] = spec
                    components[ref] = ComponentInstance(
                        reference=ref,
                        part_specification_id=spec.id,
                        raw_sources={"netlist": {"format": "qcv", "pinInventory": "observed-only"}},
                        source_path=f"{filename}:line:{line}",
                    )
                previous = pins.get(key)
                source = dict(previous.source_metadata) if previous else {}
                source.update(
                    {
                        "filename": filename,
                        f"record:{line}": f"{kind}: '{label}' {token}" + (" (by TERM)" if term else ""),
                        f"section:{line}": section,
                    }
                )
                # TERM은 의도적 종단이다. 이름으로 기능/전압을 추측하거나 오류로 간주하지 않는다.
                disposition = (
                    PinDisposition.UNSPECIFIED
                    if kind == "NET" or section == "begin one pin nets list" and not term
                    else PinDisposition.TERMINATED
                    if term
                    else PinDisposition.UNCONNECTED
                )

                def conflict():
                    """동일 핀의 모순된 선언을 표시하고 기존 연결을 제거한다. 임의로 하나를 선택하지 않는다."""
                    ambiguous.add(key)
                    connections.pop(key, None)
                    issues.append(
                        issue(
                            "AMBIGUOUS_CONNECTION",
                            ref,
                            "pin:" + number,
                            f"Pin {number} assigned to conflicting declarations; connection omitted at {filename}:line:{line}",
                            True,
                        )
                    )

                if previous:
                    declared = previous.disposition != PinDisposition.UNSPECIFIED
                    if kind == "PIN":
                        issues.append(
                            issue(
                                "DUPLICATE_CONNECTION",
                                ref,
                                f"{filename}:line:{line}",
                                "Repeated terminal declaration; one pin retained",
                            )
                        )
                    if (
                        declared
                        and kind == "NET"
                        or kind == "PIN"
                        and key in connections
                        or declared
                        and kind == "PIN"
                        and previous.disposition != disposition
                    ):
                        conflict()
                    if declared:
                        disposition = previous.disposition
                pins[key] = ComponentPin(key=key, disposition=disposition, source_metadata=source)
                if net and key not in ambiguous:
                    old = connections.get(key)
                    if old:
                        if old.net_id != net.id:
                            conflict()
                        else:
                            issues.append(
                                issue(
                                    "DUPLICATE_CONNECTION",
                                    ref,
                                    f"{filename}:line:{line}",
                                    "Repeated terminal on same Net; connection deduplicated",
                                )
                            )
                    else:
                        connections[key] = PinConnection(pin=key, net_id=net.id)
        if not pins:
            raise ValueError("QCV contains no terminals")
        return NetlistResult(
            components=components,
            specifications=specs,
            pins=list(pins.values()),
            nets=nets,
            connections=list(connections.values()),
            issues=issues,
            source_metadata={
                "format": "qcv",
                "dialect": "public-pads-net-pin",
                "encoding": "UTF-8",
                "filename": filename,
                "separatePinRecords": str(separate),
                "terminatedPinRecords": str(terminated),
            },
        )
