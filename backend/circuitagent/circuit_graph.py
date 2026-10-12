"""Reference 통합과 불변 Snapshot 인덱스. 부품 양쪽의 서로 다른 Net은 병합하지 않는다."""

from .models import CircuitDesign, ComponentInstance, PinDisposition, PinKey, issue


def assemble(name, version, bom, netlist):
    """BOM 사양과 Netlist 연결을 Reference로 결합한다. 단독 부품/충돌을 삭제하지 않고 Issue로 남긴다."""
    # BOM은 사양의 기준이다. 복사본에 연결 정보를 붙여 원본 파서 결과를 수정하지 않는다.
    components, specs = dict(bom.components), dict(bom.specifications)
    issues = [*bom.issues, *netlist.issues]
    for ref, n in netlist.components.items():
        b = components.get(ref)
        # Netlist에만 있는 부품도 실제 연결의 일부이므로 보존한다.
        if b is None:
            components[ref] = n
            specs[n.part_specification_id] = netlist.specifications[n.part_specification_id]
            issues.append(
                issue("NETLIST_ONLY_COMPONENT", ref, "netlist", "Reference missing from BOM; preserved")
            )
            continue
        # QCV는 부품값을 제공하지 않으므로 값과 실장 상태는 BOM에서 그대로 가져온다.
        components[ref] = ComponentInstance(
            reference=ref,
            value=b.value,
            part_specification_id=b.part_specification_id,
            raw_sources={**b.raw_sources, **n.raw_sources},
            source_path=n.source_path,
            assembly_status=b.assembly_status,
        )
    for ref in bom.components:
        if ref not in netlist.components:
            issues.append(
                issue(
                    "BOM_ONLY_COMPONENT",
                    ref,
                    "bom",
                    "Reference missing from Netlist; no connectivity inferred",
                )
            )
    return CircuitDesign(
        name=name,
        version=version,
        components=components,
        specifications=specs,
        pins=netlist.pins,
        nets=netlist.nets,
        connections=netlist.connections,
        issues=issues,
        source_metadata={**netlist.source_metadata, **bom.source_metadata},
    )


class CircuitGraph:
    """통합 회로를 조회용 사전으로 인덱싱한다. 실제 전기 연결은 Pin↔Net 관계로만 나타낸다."""

    def __init__(self, design: CircuitDesign):
        """PinKey→Pin, Reference→Pin 목록, Net ID→Pin 목록, PinKey→Net ID의 조회 인덱스를 만든다."""
        self.design = design
        self.pins = {p.key: p for p in design.pins}
        self.by_component = {ref: [] for ref in design.components}
        self.by_net = {id_: [] for id_ in design.nets}
        self.net_of_pin = {}
        # 인덱스만 구성한다. 저항/커패시터의 서로 다른 핀 사이에 배선 edge를 만들지 않는다.
        for p in design.pins:
            self.by_component[p.key.reference].append(p)
        for c in design.connections:
            self.net_of_pin[c.pin] = c.net_id
            self.by_net[c.net_id].append(self.pins[c.pin])

    def get_component(self, ref):
        """Reference로 부품을 찾는다. 존재하지 않으면 LookupError를 낸다."""
        if ref not in self.design.components:
            raise LookupError("Component not found")
        return self.design.components[ref]

    def get_component_pins(self, ref):
        """한 부품의 관측된 핀 목록을 반환한다. QCV에 없는 핀을 임의로 생성하지 않는다."""
        self.get_component(ref)
        return tuple(self.by_component[ref])

    def get_net_of_pin(self, ref, number):
        """Reference+핀 번호로 Net을 찾는다. 알려진 핀이 연결되지 않은 경우 None을 반환한다."""
        key = PinKey(reference=ref, number=number)
        if key not in self.pins:
            raise LookupError("Pin not found")
        id_ = self.net_of_pin.get(key)
        return self.design.nets[id_] if id_ else None

    def get_connected_pins(self, id_):
        """Net ID에 속한 모든 핀을 반환한다. 같은 Net의 핀은 전기적으로 같은 연결망이다."""
        if id_ not in self.by_net:
            raise LookupError("Net not found")
        return tuple(self.by_net[id_])

    def get_components_on_net(self, id_):
        """Net의 핀 목록에서 중복 Reference를 제거해 연결된 부품을 반환한다."""
        return tuple(
            self.get_component(ref) for ref in sorted({p.key.reference for p in self.get_connected_pins(id_)})
        )

    def get_component_specification(self, ref):
        """부품이 참조하는 PartSpecification을 찾는다. 속성은 연결 객체가 아닌 사양에 저장된다."""
        return self.design.specifications[self.get_component(ref).part_specification_id]

    def get_component_attributes(self, ref):
        """Resistance/Capacitance 및 사용자 컬럼을 포함한 동적 속성 Map을 반환한다."""
        return self.get_component_specification(ref).attributes

    def get_component_issues(self, ref):
        """해당 부품의 Issue와 회로 전체 Issue를 함께 반환한다."""
        self.get_component(ref)
        return tuple(i for i in self.design.issues if i.reference is None or i.reference == ref)

    def get_pin_context(self, ref, number):
        """핀·Net·부품 사양·Issue와 상태를 묶는다. 미존재/모호/연결/TERM/미연결/미확정을 구분한다."""
        component = self.get_component(ref)
        key = PinKey(reference=ref, number=number)
        pin = self.pins.get(key)
        net = self.get_net_of_pin(ref, number) if pin else None
        issues = self.get_component_issues(ref)
        ambiguous = any(
            i.code == "AMBIGUOUS_CONNECTION"
            and i.reference == ref
            and (i.source == "pin:" + number or i.message.startswith("Pin " + number + " assigned"))
            for i in issues
        )
        # QCV는 관측된 핀만 제공한다. 선언이 없는 핀의 미연결 여부를 추측하지 않는다.
        status = (
            "MISSING_PIN"
            if pin is None
            else "AMBIGUOUS"
            if ambiguous
            else "CONNECTED"
            if net
            else "TERMINATED"
            if pin.disposition == PinDisposition.TERMINATED
            else "UNCONNECTED"
            if pin.disposition == PinDisposition.UNCONNECTED
            else "UNRESOLVED"
        )
        return {
            "key": key,
            "pin": pin,
            "status": status,
            "net": net,
            "component": component,
            "specification": self.get_component_specification(ref),
            "issues": issues,
        }
