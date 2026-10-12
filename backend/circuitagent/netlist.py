"""공개 PADS QCV 및 KiCad S-expression을 동일한 회로 IR로 변환한다."""

import hashlib
import re
from typing import Protocol

from .bom import MAX_ROWS, MAX_VALUES, file_limit
from .domain import (
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


class NetlistParser(Protocol):
    def parse(self, data: bytes, filename: str) -> NetlistResult: ...


class QcvNetlistParser:
    """실제 Sparrow에서 확인한 NET/PIN 문법만 지원한다. PIN/TERM은 Net 연결이 아니다."""

    record = re.compile(r"(NET|PIN)\s*:\s*'([^']+)'\s+(.+)")
    terminal = re.compile(r"([/A-Za-z_][A-Za-z0-9_./()+:-]*)-([A-Za-z0-9_]+)")

    def parse(self, data, filename):
        file_limit(data)
        text = data.decode("utf-8-sig", errors="strict")
        components, specs, pins, nets, connections, ambiguous, issues = {}, {}, {}, {}, {}, set(), []
        separate = terminated = tokens = 0
        section = "unspecified"
        for line, original in enumerate(text.splitlines(), 1):
            value = original.strip()
            if line > MAX_ROWS or len(value) > 100_000:
                raise ValueError(f"QCV line budget exceeded at {filename}:line:{line}")
            if not value:
                continue
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
            if kind == "NET":
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
            for token in identifiers:
                tokens += 1
                if tokens > MAX_VALUES:
                    raise ValueError("QCV terminal budget exceeded")
                terminal = self.terminal.fullmatch(token)
                if not terminal:
                    raise ValueError(f"Invalid terminal identifier at {filename}:line:{line}")
                ref, number = terminal.groups()  # 마지막 유효 단자 접미부만 분리한다.
                key = PinKey(reference=ref, number=number)
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
                disposition = (
                    PinDisposition.UNSPECIFIED
                    if kind == "NET" or section == "begin one pin nets list" and not term
                    else PinDisposition.TERMINATED
                    if term
                    else PinDisposition.UNCONNECTED
                )

                def conflict():
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


def sexpression(text):
    """문자열·escape·괄호를 순서대로 읽는 한도 있는 파서. 정규식으로 전체 트리를 해석하지 않는다."""
    position = 0
    count = 0

    def whitespace():
        nonlocal position
        while position < len(text) and text[position].isspace():
            position += 1

    def read(depth=0):
        nonlocal position, count
        if depth > 128:
            raise ValueError("S-expression nesting limit exceeded")
        whitespace()
        if position >= len(text):
            raise ValueError("Unexpected end of S-expression")
        count += 1
        if count > MAX_VALUES:
            raise ValueError("S-expression value budget exceeded")
        char = text[position]
        position += 1
        if char == "(":
            values = []
            whitespace()
            while position < len(text) and text[position] != ")":
                values.append(read(depth + 1))
                whitespace()
            if position >= len(text):
                raise ValueError("Unclosed S-expression")
            position += 1
            if not values or not isinstance(values[0], str):
                raise ValueError("Missing S-expression tag")
            return values
        if char == ")":
            raise ValueError("Unexpected closing parenthesis")
        if char == '"':
            value = []
            while position < len(text):
                c = text[position]
                position += 1
                if c == '"':
                    return "".join(value)
                if c == "\\":
                    if position >= len(text):
                        raise ValueError("Truncated quoted escape")
                    c = text[position]
                    position += 1
                    c = {"n": "\n", "r": "\r", "t": "\t"}.get(c, c)
                value.append(c)
            raise ValueError("Unclosed quoted string")
        start = position - 1
        while position < len(text) and not text[position].isspace() and text[position] not in "()":
            position += 1
        return text[start:position]

    root = read()
    whitespace()
    if position != len(text) or not isinstance(root, list):
        raise ValueError("Unexpected trailing S-expression data")
    return root


def children(node, tag):
    return [v for v in node[1:] if isinstance(v, list) and v[0] == tag]


def child(node, tag):
    return next(iter(children(node, tag)), None)


def field(node, tag, required=False):
    value = child(node, tag)
    result = value[1] if value and len(value) > 1 and isinstance(value[1], str) else None
    if required and not result:
        raise ValueError("Missing KiCad " + tag)
    return result


class KiCadNetlistParser:
    def parse(self, data, filename):
        file_limit(data)
        root = sexpression(data.decode("utf-8-sig", errors="strict"))
        if root[0] != "export" or child(root, "components") is None or child(root, "nets") is None:
            raise ValueError("Expected KiCad export with components/nets")
        metadata = {"format": "kicad", "filename": filename}
        design = child(root, "design")
        if design:
            metadata.update(
                {k: field(design, k) for k in ("source", "date", "tool") if field(design, k) is not None}
            )
        libraries = {}
        for part in children(child(root, "libparts") or [], "libpart"):
            pins = children(child(part, "pins") or [], "pin")
            lib, name = field(part, "lib"), field(part, "part")
            libraries[(lib, name)] = pins
            aliases = child(part, "aliases") or []
            for a in aliases[1:]:
                if isinstance(a, str):
                    libraries[(lib, a)] = pins
                elif a and a[0] == "alias" and len(a) > 1:
                    libraries[(lib, a[1])] = pins
        components, specs, pins, nets, connections, ambiguous, issues = {}, {}, {}, {}, {}, set(), []
        for comp in children(child(root, "components"), "comp"):
            ref = field(comp, "ref", True)
            if ref in components:
                issues.append(issue("DUPLICATE_REFERENCE", ref, filename, "First instance retained", True))
                continue
            raw = {
                k: field(comp, k)
                for k in ("value", "footprint", "datasheet", "description", "tstamps")
                if field(comp, k) is not None
            }
            for f in children(child(comp, "fields") or [], "field"):
                if field(f, "name") and isinstance(f[-1], str) and len(f) > 2:
                    raw["field:" + field(f, "name")] = f[-1]
            sheet = child(comp, "sheetpath")
            path = field(sheet, "names") if sheet else None
            if path is not None:
                raw["sheetpath"] = path
            if sheet and field(sheet, "tstamps"):
                raw["sheetpath:tstamps"] = field(sheet, "tstamps")
            lib = child(comp, "libsource")
            if lib:
                key = (field(lib, "lib"), field(lib, "part"))
                raw.update({k: v for k, v in [("library", key[0]), ("libraryPart", key[1])] if v is not None})
                raw["pinInventory"] = "complete" if key in libraries else "connections-only"
                for p in libraries.get(key, []):
                    pk = PinKey(reference=ref, number=field(p, "num", True))
                    pins[pk] = ComponentPin(key=pk, name=field(p, "name"), electrical_type=field(p, "type"))
            spec = PartSpecification()
            specs[spec.id] = spec
            components[ref] = ComponentInstance(
                reference=ref,
                value=field(comp, "value"),
                part_specification_id=spec.id,
                raw_sources={"netlist": raw},
                source_path=path,
            )
        for net in children(child(root, "nets"), "net"):
            id_, name = field(net, "code", True), field(net, "name", True)
            if id_ in nets:
                if nets[id_].name != name:
                    raise ValueError("Conflicting names for duplicate Net code")
                issues.append(
                    issue("DUPLICATE_NET", source=filename, message="Duplicate Net code; nodes merged")
                )
            nets.setdefault(id_, CircuitNet(id=id_, name=name, source_path=metadata.get("source")))
            for node in children(net, "node"):
                ref = field(node, "ref", True)
                if ref not in components:
                    issues.append(
                        issue(
                            "UNKNOWN_COMPONENT", ref, filename, "Net node references missing component", True
                        )
                    )
                    continue
                key = PinKey(reference=ref, number=field(node, "pin", True))
                existing = pins.get(key)
                pins[key] = ComponentPin(
                    key=key,
                    name=field(node, "pinfunction") or (existing.name if existing else None),
                    electrical_type=field(node, "pintype")
                    or (existing.electrical_type if existing else None),
                )
                previous = connections.get(key)
                if key in ambiguous or previous and previous.net_id != id_:
                    ambiguous.add(key)
                    connections.pop(key, None)
                    issues.append(
                        issue(
                            "AMBIGUOUS_CONNECTION",
                            ref,
                            "pin:" + key.number,
                            "Pin " + key.number + " assigned to multiple Nets; connection omitted",
                            True,
                        )
                    )
                else:
                    connections.setdefault(key, PinConnection(pin=key, net_id=id_))
        return NetlistResult(
            components=components,
            specifications=specs,
            pins=list(pins.values()),
            nets=nets,
            connections=list(connections.values()),
            issues=issues,
            source_metadata=metadata,
        )


PARSERS = {"qcv": QcvNetlistParser(), "kicad": KiCadNetlistParser()}
