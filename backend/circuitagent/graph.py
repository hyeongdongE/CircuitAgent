"""Reference 통합과 불변 Snapshot 인덱스. 부품 양쪽의 서로 다른 Net은 병합하지 않는다."""

from collections import deque

from .domain import AssemblyStatus, CircuitDesign, ComponentInstance, PinDisposition, PinKey, issue
from .units import normalize


def assemble(name, version, bom, netlist):
    components, specs = dict(bom.components), dict(bom.specifications)
    issues = [*bom.issues, *netlist.issues]
    for ref, n in netlist.components.items():
        b = components.get(ref)
        if b is None:
            components[ref] = n
            specs[n.part_specification_id] = netlist.specifications[n.part_specification_id]
            issues.append(
                issue("NETLIST_ONLY_COMPONENT", ref, "netlist", "Reference missing from BOM; preserved")
            )
            continue
        if b.value and n.value and b.value != n.value:
            unit = {"resistor": "Ω", "capacitor": "F", "inductor": "H"}.get(
                specs[b.part_specification_id].type
            )
            try:
                equivalent = bool(
                    unit and normalize(b.value, unit).normalized == normalize(n.value, unit).normalized
                )
            except ValueError:
                equivalent = False
            if not equivalent:
                issues.append(
                    issue(
                        "VALUE_MISMATCH",
                        ref,
                        "bom/netlist",
                        "BOM value differs from Netlist value; BOM retained",
                    )
                )
        components[ref] = ComponentInstance(
            reference=ref,
            value=b.value or n.value,
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
    """한 Snapshot을 한 번만 인덱싱하여 여러 Rule이 반복 DB 조회 없이 공유한다."""

    def __init__(self, design: CircuitDesign):
        self.design = design
        self.pins = {p.key: p for p in design.pins}
        self.by_component = {ref: [] for ref in design.components}
        self.by_net = {id_: [] for id_ in design.nets}
        self.net_of_pin = {}
        for p in design.pins:
            self.by_component[p.key.reference].append(p)
        for c in design.connections:
            self.net_of_pin[c.pin] = c.net_id
            self.by_net[c.net_id].append(self.pins[c.pin])

    def get_component(self, ref):
        if ref not in self.design.components:
            raise LookupError("Component not found")
        return self.design.components[ref]

    def get_component_pins(self, ref):
        self.get_component(ref)
        return tuple(self.by_component[ref])

    def get_net_of_pin(self, ref, number):
        key = PinKey(reference=ref, number=number)
        if key not in self.pins:
            raise LookupError("Pin not found")
        id_ = self.net_of_pin.get(key)
        return self.design.nets[id_] if id_ else None

    def get_connected_pins(self, id_):
        if id_ not in self.by_net:
            raise LookupError("Net not found")
        return tuple(self.by_net[id_])

    def get_components_on_net(self, id_):
        return tuple(
            self.get_component(ref) for ref in sorted({p.key.reference for p in self.get_connected_pins(id_)})
        )

    def get_component_specification(self, ref):
        return self.design.specifications[self.get_component(ref).part_specification_id]

    def get_component_attributes(self, ref):
        return self.get_component_specification(ref).attributes

    def get_component_issues(self, ref):
        self.get_component(ref)
        return tuple(i for i in self.design.issues if i.reference is None or i.reference == ref)

    def get_pins_by_name(self, ref, name):
        return tuple(p for p in self.get_component_pins(ref) if p.name == name)

    def get_pin_context(self, ref, number):
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
        inventory = component.raw_sources.get("netlist", {}).get("pinInventory") == "complete"
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
            if pin.disposition == PinDisposition.UNCONNECTED or inventory
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

    def explore(self, start: PinKey, max_depth=2, allow_components=(), include_dnp=False):
        """NET/COMPONENT edge 근거를 분리한다. 부품 경유는 실제 단락 또는 도통 판정이 아니다."""
        self.get_net_of_pin(start.reference, start.number)
        if not 0 <= max_depth <= 8 or len(allow_components) > 100:
            raise ValueError("Exploration budget exceeded")
        allowed = set(allow_components)
        for ref in allowed:
            self.get_component(ref)
        visited, queue, edges = {start: 0}, deque([start]), {}
        expanded_nets, expanded_components = set(), set()
        while queue:
            key = queue.popleft()
            depth = visited[key]
            if depth >= max_depth:
                continue
            neighbors = []
            net = self.get_net_of_pin(key.reference, key.number)
            # Net은 hyperedge다. 한 번만 펼쳐 고밀도 Net의 핀 쌍 전체(O(n²))를 생성하지 않는다.
            if net and net.id not in expanded_nets:
                expanded_nets.add(net.id)
                neighbors.extend(
                    (p.key, "NET", net.id) for p in self.get_connected_pins(net.id) if p.key != key
                )
            if (
                key.reference in allowed
                and key.reference not in expanded_components
                and (include_dnp or self.get_component(key.reference).assembly_status != AssemblyStatus.DNP)
            ):
                expanded_components.add(key.reference)
                neighbors.extend(
                    (p.key, "COMPONENT", key.reference)
                    for p in self.get_component_pins(key.reference)
                    if p.key != key
                )
            for other, kind, source in neighbors:
                a, b = sorted([key, other], key=lambda k: (k.reference, k.number))
                edges[(a, b, kind, source)] = {"from": a, "to": b, "kind": kind, "source": source}
                if other not in visited:
                    if len(visited) >= 20_000:
                        raise ValueError("Exploration vertex budget exceeded")
                    visited[other] = depth + 1
                    queue.append(other)
        return {
            "start": start,
            "pins": tuple(visited),
            "edges": tuple(edges.values()),
            "depths": tuple({"pin": k, "depth": v} for k, v in visited.items()),
        }
