"""전자부품 SI 단위 정규화. 실제 0과 미확인 None을 구분하고 Decimal만 사용한다."""

import re
from decimal import Decimal, DecimalException, localcontext

from .domain import AttributeValue

POWERS = {
    "": 0,
    "R": 0,
    "p": -12,
    "n": -9,
    "u": -6,
    "µ": -6,
    "μ": -6,
    "m": -3,
    "k": 3,
    "K": 3,
    "M": 6,
    "G": 9,
}
UNITS = {
    "Resistance": "Ω",
    "Capacitance": "F",
    "Inductance": "H",
    "Voltage Rating": "V",
    "Voltage": "V",
    "Current Rating": "A",
    "Tolerance": "%",
}


def _normalize(raw: str, unit: str) -> AttributeValue:
    if len(raw) > 128:
        raise ValueError("Engineering value exceeds 128 characters")
    value = raw.strip()
    if unit == "%":
        value = value.removeprefix("±").strip()
    suffix = "(?i:ohms?|Ω|Ω)" if unit == "Ω" else re.escape(unit)
    value = re.sub(r"\s*" + suffix + "$", "", value).strip()
    if re.fullmatch(r"[+]?[0-9]+,[0-9]{1,2}(?:\s*[pnuµμmkKMG])?", value):
        value = value.replace(",", ".")
    embedded = re.fullmatch(r"([0-9]+)([pnuµμmkKMGRVA])([0-9]+)", value)
    power = 0
    if embedded and unit != "%":
        a, marker, b = embedded.groups()
        unit_marker = marker == unit or unit == "Ω" and marker == "R"
        if not unit_marker and (marker not in POWERS or marker == "R"):
            raise ValueError("Embedded unit marker does not match " + unit)
        power = 0 if unit_marker else POWERS[marker]
        number = Decimal(a + "." + b)
    else:
        if unit == "Ω":
            value = value.removesuffix("R")
        match = re.fullmatch(
            r"([+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)\s*([pnuµμmkKMG]?)", value
        )
        if not match or unit == "%" and match[2]:
            raise ValueError("Cannot normalize engineering value as " + unit)
        number = Decimal(match[1])
        power = POWERS[match[2]]
    # 계산 환경의 기본 정밀도로 원문 숫자가 반올림되지 않도록 충분한 정밀도를 명시한다.
    if len(number.as_tuple().digits) > 32 or abs(number.as_tuple().exponent) > 32:
        raise ValueError("Engineering numeric precision/scale exceeds 32")
    with localcontext() as ctx:
        ctx.prec = 64
        number = number.scaleb(power).normalize()
    return AttributeValue(raw=raw, normalized=number, unit=unit)


def normalize(raw: str, unit: str) -> AttributeValue:
    """과도한 지수 등 Decimal 자체 파싱 오류도 입력 오류로 통일하여 원문/Issue를 보존한다."""
    try:
        return _normalize(raw, unit)
    except DecimalException as exc:
        raise ValueError("Invalid engineering decimal") from exc
