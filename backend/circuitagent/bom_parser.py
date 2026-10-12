"""BOM XLSX 파서. 그룹 Reference를 펼치고 원문·위치·실장 상태를 보존한다."""

from __future__ import annotations

import io
import json
import re
import zipfile
from collections.abc import Mapping
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Literal
from xml.etree.ElementTree import ParseError

import openpyxl
from pydantic import Field, model_validator

from .models import (
    AssemblyStatus,
    AttributeValue,
    BomResult,
    ComponentInstance,
    Model,
    PartSpecification,
    issue,
    wire,
)
from .unit_normalizer import UNITS, normalize

MAX_BYTES = 10 * 1024 * 1024
MAX_ROWS, MAX_COLUMNS, MAX_CELLS, MAX_VALUES = 50_000, 256, 250_000, 500_000
ALIASES = {
    **dict.fromkeys(
        ["reference", "references", "ref", "refs", "designator", "designators", "refdes"], "reference"
    ),
    "value": "value",
    "designation": "value",
    "qty": "quantity",
    "quantity": "quantity",
    "qtyonboard": "quantity",
    "mpn": "mpn",
    "partnumber": "mpn",
    "manufacturerpartnumber": "mpn",
    "manufacturer": "manufacturer",
    "mfr": "manufacturer",
    "type": "type",
    "componenttype": "type",
    "voltagerating": "Voltage Rating",
    "voltage": "Voltage Rating",
    "currentrating": "Current Rating",
    "current": "Current Rating",
    "resistance": "Resistance",
    "capacitance": "Capacitance",
    "inductance": "Inductance",
    "tolerance": "Tolerance",
    "nomount": "nomount",
    "dnp": "dnp",
    "dnf": "dnp",
    "donotpopulate": "dnp",
    "donotfit": "dnp",
}


def file_limit(data):
    """입력 파일 크기가 10MiB 이내인지 확인한다. 큰 파일은 객체를 만들기 전에 거절한다."""
    if len(data) > MAX_BYTES:
        raise ValueError("Parser input exceeds 10 MiB")


def clean(value):
    """공백·빈 문자열·~를 누락(None)으로 바꾼다. 문자열 0은 실제 값이므로 유지한다."""
    return None if value is None or not value.strip() or value.strip() == "~" else value.strip()


def canonical(header):
    """알려진 컬럼 별칭만 공통 이름으로 바꾼다. 알 수 없는 컬럼 이름은 유지한다."""
    return ALIASES.get(re.sub("[^a-z0-9]", "", header.strip().lower()), header.strip())


def catalogue(headers):
    """공개 Sparrow 양식의 RefDes/QtyOnBoard 헤더를 확인한다. 모든 BOM에 적용하지 않는다."""
    keys = {re.sub("[^a-z0-9]", "", h.strip().lower()) for h in headers}
    return {"refdes", "qtyonboard"} <= keys


def column(header, is_catalogue):
    # 내부 PartNumber와 Voltage는 제조사 MPN/정격이라고 자동 확정하지 않는다.
    """양식별 컬럼 의미를 정한다. Sparrow 내부 PartNumber를 제조사 MPN으로 오인하지 않는다."""
    if is_catalogue and header.strip().lower() == "partnumber":
        return "Part Number"
    if is_catalogue and header.strip().lower() == "voltage":
        return "Voltage"
    return canonical(header)


class BomMapping(Model):
    """자동 별칭으로 알 수 없는 헤더는 명시적으로 의미를 지정한다. 나머지 열은 모두 동적 속성이다."""

    columns: Mapping[str, str] = Field(default_factory=dict)
    units: Mapping[str, str] = Field(default_factory=dict)
    no_mount_blank: Literal["FITTED", "UNSPECIFIED"] | None = None

    @model_validator(mode="after")
    def checked(self):
        """헤더 매핑과 단위를 검증한다. 한 컬럼에 서로 다른 의미를 부여할 수 없다."""
        if len(self.columns) > MAX_COLUMNS or len(self.units) > MAX_COLUMNS:
            raise ValueError("BOM mapping column budget exceeded")
        values = list(self.columns.values())
        if len(set(values)) != len(values):
            raise ValueError("One source column cannot have conflicting roles")
        for k, v in [*self.columns.items(), *self.units.items()]:
            if not k.strip() or not v.strip() or len(k) > 512 or len(v) > 512:
                raise ValueError("Explicit bounded BOM mapping names required")
        for k, v in self.units.items():
            if v not in {"Ω", "F", "H", "V", "A", "%"}:
                raise ValueError("Unsupported explicit attribute unit")
            if k in UNITS and v != UNITS[k]:
                raise ValueError("Known physical attribute has incompatible unit")
        return self


def logical_name(name):
    # 내부 품번/일반 Voltage를 제조사 MPN/정격으로 자동 승격하지 않는다.
    """명시 매핑의 대상 이름을 정규화한다. 내부 품번과 일반 전압은 별도 속성으로 유지한다."""
    return name if name in {"Part Number", "Part Name", "Voltage"} else canonical(name)


def schema(headers, config):
    """실제 헤더→공통 필드 매핑을 만든다. 명시 매핑을 우선하고 충돌/Reference 누락을 거절한다."""
    explicit = {source: logical_name(target) for target, source in config.columns.items()}
    if any(h not in headers for h in explicit):
        raise ValueError("Mapped BOM source column is missing")
    mapped = {}
    claimed = set(explicit.values())
    for h in headers:
        if not h.strip():
            continue
        target = explicit.get(h, column(h, catalogue(headers)))
        if h not in explicit and target in claimed:
            target = h if h != target else "source:" + h
        if target in mapped.values():
            raise ValueError("Ambiguous duplicate BOM column; provide explicit mapping")
        mapped[h] = target
    if "reference" not in mapped.values():
        raise ValueError("Reference column or explicit reference mapping required")
    return mapped


def checked_headers(cells, config=None):
    """원본 헤더의 중복과 필수 Reference 매핑을 검증한다. 헤더 순서를 변경하지 않는다."""
    if len([h for h in cells if h.strip()]) != len(set(h for h in cells if h.strip())):
        raise ValueError("Duplicate source BOM header")
    schema(cells, config or BomMapping())
    return cells


@dataclass(frozen=True)
class BomRow:
    """한 행의 원본 컬럼 dict, 헤더 순서, 시트/행 위치, 이름 없는 추가 컬럼을 묶는다."""

    location: str
    raw: dict[str, str]
    headers: tuple[str, ...]
    unnamed: tuple[str, ...]


def row_map(headers, cells):
    # 짧은 행은 빈 셀을 보존한다. 헤더 없는 추가 값은 이름을 추측하지 않고 위치 이름+Issue로 보존한다.
    """한 행을 헤더 이름 기반 dict로 바꾼다. 짧은 행은 빈 값, 추가 셀은 column:N으로 보존한다."""
    raw = {h: cells[i] if i < len(cells) else "" for i, h in enumerate(headers) if h.strip()}
    unnamed = []
    for i, value in enumerate(cells):
        if value.strip() and (i >= len(headers) or not headers[i].strip()):
            key = f"column:{i + 1}"
            while key in raw or key in headers:
                key = "source:" + key
            raw[key] = value
            unnamed.append(key)
    return raw, tuple(unnamed)


def validate_zip(data: bytes):
    """XLSX 압축을 검사한다. 입력10MiB, 압축 해제32MiB, ZIP 항목1000개를 넘으면 거절한다."""
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            if len(entries) > 1000 or sum(e.file_size for e in entries) > 32 * 1024 * 1024:
                raise ValueError("XLSX expanded content limit exceeded")
            total = 0
            for entry in entries:
                with archive.open(entry) as source:
                    while chunk := source.read(8192):
                        total += len(chunk)
                        if total > 32 * 1024 * 1024:
                            raise ValueError("XLSX expanded content limit exceeded")
    except zipfile.BadZipFile as exc:
        raise ValueError("Invalid XLSX ZIP") from exc


def cell_text(value) -> str:
    """셀 저장값을 원문 문자열로 보존한다. 빈 셀은 빈 문자열, 실제 숫자0은 문자열0이다.

    Excel 표시 스타일이나 수식 결과를 재계산하지 않는다. 단위 표시는 문자열 셀로 준비한다.
    """
    if value is None:
        return ""
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, float):
        return format(value, ".15g")
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def rows_from_file(data: bytes, filename: str, config=None) -> list[BomRow]:
    """모든 시트를 읽고 Reference 컬럼이 있는 헤더를 찾아 각 데이터 행을 dict로 바꾼다.

    헤더 위의 설명 행은 건너뛰며 행 수/컬럼 순서를 고정하지 않는다.
    출처는 sheet:시트이름:row:Excel행번호로 기록한다. 수식은 값으로 오인하지 않고 거절한다.
    """
    config = config or BomMapping()
    file_limit(data)
    if not filename.lower().endswith(".xlsx"):
        raise ValueError("BOM input must be XLSX")
    if not data.startswith(b"PK\x03\x04"):
        raise ValueError("XLSX content does not match filename extension")
    validate_zip(data)
    try:
        workbook = openpyxl.load_workbook(io.BytesIO(data), read_only=True, data_only=False)
    except (KeyError, ValueError, OSError, ParseError) as exc:
        raise ValueError("Invalid XLSX workbook structure") from exc
    result, budget, found = [], 0, False
    try:
        for sheet in workbook:
            headers = None
            for index, cells in enumerate(sheet.iter_rows(), 1):
                if index > MAX_ROWS or len(cells) > MAX_COLUMNS:
                    raise ValueError("BOM row/column limit exceeded")
                budget += len(cells)
                if budget > MAX_CELLS:
                    raise ValueError("BOM cell limit exceeded")
                if any(c.data_type == "f" for c in cells):
                    raise ValueError("Formula cells require explicit values; formulas are not evaluated")
                values = [cell_text(c.value) for c in cells]
                if headers is None:
                    # 자동 별칭 또는 명시적으로 Reference에 매핑한 헤더만 식별한다.
                    mapped = {
                        source
                        for target, source in config.columns.items()
                        if logical_name(target) == "reference"
                    }
                    if any(canonical(v) == "reference" or v in mapped for v in values):
                        headers = checked_headers(values, config)
                        found = True
                    continue
                raw, unnamed = row_map(headers, values)
                if any(v.strip() for v in raw.values()):
                    result.append(BomRow(f"sheet:{sheet.title}:row:{index}", raw, tuple(headers), unnamed))
    finally:
        workbook.close()
    if not found:
        raise ValueError("BOM Reference column or explicit reference mapping required")
    return result


def parse_bom(data: bytes, filename: str, mapping=None) -> BomResult:
    """XLSX 데이터를 BomResult로 변환한다. 각 Reference를 개별 ComponentInstance로 만들고 사양/원문/Issue를 보존한다."""
    config = mapping if isinstance(mapping, BomMapping) else BomMapping.model_validate(mapping or {})
    # components는 Reference별 인스턴스, specifications는 UUID별 사양이다.
    # variants는 동일 품번의 사양 재사용 후보이며, issues는 원본의 문제를 누적한다.
    components, specifications, variants, issues = {}, {}, {}, []
    rows = rows_from_file(data, filename, config)
    source_headers = []
    for row in rows:
        location, raw = row.location, row.raw
        source_headers.extend(h for h in row.headers if h not in source_headers)
        resolved = schema(row.headers, config)
        fields = {resolved.get(h, h): v for h, v in raw.items()}
        references = clean(fields.get("reference"))
        if references is None:
            issues.append(issue("MISSING_REFERENCE", source=location, error=True))
            continue
        # 한 행에 R1,R2 또는 R1 R2가 들어 있어도 개별 부품으로 펼친다.
        refs = [r for r in re.split(r"[,;\s]+", references) if r]
        quantity = clean(fields.get("quantity"))
        if quantity is not None:
            try:
                q = Decimal(quantity)
                if not q.is_finite() or q != q.to_integral_value():
                    raise ValueError("Quantity must be an integer")
                if q != len(refs):
                    issues.append(issue("QUANTITY_MISMATCH", references, location))
            except (ValueError, InvalidOperation):
                issues.append(issue("PARSE_FAILURE", references, location, "Invalid quantity", True))
        for ref in refs:
            # 중복 Reference는 첫 원본을 유지한다. 뒤 행으로 조용히 덮어쓰지 않는다.
            if ref in components:
                issues.append(issue("DUPLICATE_REFERENCE", ref, location, "First instance retained", True))
                continue
            if row.unnamed:
                issues.append(
                    issue(
                        "PARSE_FAILURE",
                        ref,
                        location,
                        "Values without column headers retained as positional attributes: "
                        + ", ".join(row.unnamed),
                        True,
                    )
                )
            value = clean(fields.get("value"))
            if value is None:
                issues.append(issue("MISSING_VALUE", ref, location))
            type_ = clean(fields.get("type"))
            # R/C/L+숫자의 통상 명명 규칙만 수동 소자 단위 해석에 사용한다.
            # 이 분류는 제조사/MPN 신원 확인이나 회로 기능 판정이 아니다.
            if type_ is None:
                type_ = next(
                    (
                        t
                        for prefix, t in [("R", "resistor"), ("C", "capacitor"), ("L", "inductor")]
                        if re.fullmatch(prefix + "[0-9]+", ref)
                    ),
                    None,
                )
            else:
                type_ = type_.lower()

            def placement(raw_flag, no_mount=False):
                """DNP/NoMount 원문을 실장 상태로 변환한다. 알 수 없는 값은 미확정으로 유지한다."""
                flag = clean(raw_flag)
                if flag is None:
                    if no_mount:
                        # 확인된 공개 catalogue 규칙 또는 사용자 명시 정책만 빈 값을 실장으로 해석한다.
                        blank = config.no_mount_blank or (
                            "FITTED" if catalogue(row.headers) else "UNSPECIFIED"
                        )
                        return AssemblyStatus(blank)
                    return AssemblyStatus.UNSPECIFIED
                if no_mount:
                    result = AssemblyStatus.DNP if flag.upper() == "NM" else None
                else:
                    result = (
                        AssemblyStatus.DNP
                        if flag.lower() in {"1", "true", "yes", "y", "dnp", "dnf", "x"}
                        else AssemblyStatus.FITTED
                        if flag.lower() in {"0", "false", "no", "n"}
                        else None
                    )
                if result is None:
                    issues.append(issue("PARSE_FAILURE", ref, location, "Unknown assembly flag"))
                return result or AssemblyStatus.UNSPECIFIED

            mounted = (
                placement(fields.get("nomount"), True)
                if "nomount" in fields
                else placement(fields.get("dnp"))
            )
            if (
                "nomount" in fields
                and clean(fields.get("dnp")) is not None
                and mounted != placement(fields["dnp"])
            ):
                issues.append(
                    issue("SPECIFICATION_CONFLICT", ref, location, "NoMount and DNP disagree", True)
                )
                mounted = AssemblyStatus.UNSPECIFIED

            def attribute(key, raw_value):
                """단위가 확인된 속성만 Decimal로 정규화한다. 실패해도 원문을 남기고 Issue를 추가한다."""
                unit = config.units.get(key, UNITS.get(key))
                if unit is None:
                    return AttributeValue(raw=raw_value)
                try:
                    return normalize(raw_value, unit)
                except ValueError:
                    issues.append(issue("INVALID_UNIT", ref, location, "Invalid " + key + " unit"))
                    return AttributeValue(raw=raw_value)

            # 고정 핵심 필드 외의 비어 있지 않은 컬럼을 모두 동적 속성으로 보존한다.
            attributes = {
                k: attribute(k, v)
                for k, v in fields.items()
                if clean(v) is not None
                and k
                not in {
                    "reference",
                    "value",
                    "quantity",
                    "manufacturer",
                    "mpn",
                    "type",
                    "Id",
                    "dnp",
                    "nomount",
                }
            }
            physical = {"resistor": "Resistance", "capacitor": "Capacitance", "inductor": "Inductance"}.get(
                type_
            )
            if physical and value is not None:
                from_value = attribute(physical, fields["value"])
                explicit = attributes.setdefault(physical, from_value)
                if (
                    explicit.normalized is not None
                    and from_value.normalized is not None
                    and explicit.normalized != from_value.normalized
                ):
                    issues.append(issue("VALUE_MISMATCH", ref, location, "Explicit attribute retained"))
            mpn, manufacturer = clean(fields.get("mpn")), clean(fields.get("manufacturer"))
            spec = PartSpecification(mpn=mpn, manufacturer=manufacturer, type=type_, attributes=attributes)
            catalogue_part = clean(fields.get("Part Number"))
            identity = (
                ("mpn", manufacturer or "", mpn)
                if mpn
                else ("catalogue", catalogue_part, fields.get("Rev", ""))
                if catalogue_part
                else None
            )
            # 같은 MPN/내부 품번이어도 속성이 다르면 별도 사양과 충돌 Issue를 만든다.
            if identity:
                existing = variants.setdefault(identity, [])

                def equivalent(other):
                    """같은 품번이어도 유형과 전체 속성이 같은 경우에만 사양 객체를 재사용한다."""
                    if other.type != spec.type or other.attributes.keys() != spec.attributes.keys():
                        return False
                    return all(
                        a.unit == b.unit and a.normalized == b.normalized
                        if a.normalized is not None and b.normalized is not None
                        else a == b
                        for key, a in spec.attributes.items()
                        for b in [other.attributes[key]]
                    )

                shared = next((s for s in existing if equivalent(s)), None)
                if shared:
                    spec = shared
                else:
                    if existing:
                        issues.append(
                            issue(
                                "SPECIFICATION_CONFLICT",
                                ref,
                                location,
                                "Same identity has conflicting attributes",
                            )
                        )
                    existing.append(spec)
            specifications[spec.id] = spec
            components[ref] = ComponentInstance(
                reference=ref,
                value=value,
                part_specification_id=spec.id,
                raw_sources={
                    "bom": raw,
                    "provenance": {
                        "bomLocation": location,
                        "bomHeaderOrder": json.dumps(row.headers, ensure_ascii=False),
                    },
                },
                source_path=location,
                assembly_status=mounted,
            )
    return BomResult(
        components=components,
        specifications=specifications,
        issues=issues,
        source_metadata={
            "bomFormat": filename.lower().rsplit(".", 1)[-1],
            "bomEncoding": "XLSX workbook",
            "bomRowCount": str(len(rows)),
            "bomColumns": json.dumps(source_headers, ensure_ascii=False),
            "bomMapping": json.dumps(wire(config), ensure_ascii=False, sort_keys=True),
        },
    )
