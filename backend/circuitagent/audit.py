"""명시 Rule을 Decimal로 평가한다. 미확인 신원·누락·DNP·충돌은 UNKNOWN이다."""

import hashlib
import json
import logging
import re
from collections.abc import Mapping
from datetime import UTC, datetime
from decimal import Decimal, localcontext
from pathlib import Path
from time import perf_counter
from typing import Any, Literal, Protocol
from urllib.parse import urlsplit
from uuid import UUID, uuid4

from pydantic import Field, model_validator

from .domain import AttributeValue, Model, wire


def now():
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def number(v):
    if v is None or not v.is_finite() or len(v.as_tuple().digits) > 32 or abs(v.as_tuple().exponent) > 32:
        raise ValueError("Decimal precision/scale exceeds 32")


def text(v, n):
    if v is None or not v.strip() or len(v) > n or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in v):
        raise ValueError("Explicit bounded text required")


def tolerance(v, required=True):
    if v is None and not required:
        return
    number(v)
    if not 0 <= v <= 50:
        raise ValueError("Tolerance must be 0..50 percent")


class Target(Model):
    reference: str
    part_name: str | None = None
    pin_number: str | None = None
    pin_function: str | None = None
    require_verified_mpn: bool = False


class Bounds(Model):
    min: Decimal
    max: Decimal
    tolerance_percent: Decimal

    @model_validator(mode="after")
    def checked(self):
        number(self.min)
        number(self.max)
        tolerance(self.tolerance_percent)
        if self.min > self.max:
            raise ValueError("Inverted bounds")
        return self


class Divider(Model):
    upper_reference: str
    lower_reference: str
    ground_pin: str
    vref: Decimal
    vref_min: Decimal
    vref_max: Decimal
    resistor_tolerance_percent: Decimal | None = None

    @model_validator(mode="after")
    def checked(self):
        text(self.upper_reference, 191)
        text(self.lower_reference, 191)
        text(self.ground_pin, 128)
        if self.upper_reference == self.lower_reference:
            raise ValueError("Distinct resistors required")
        for v in [self.vref, self.vref_min, self.vref_max]:
            number(v)
            if v <= 0:
                raise ValueError("Positive Vref required")
        if not self.vref_min <= self.vref <= self.vref_max:
            raise ValueError("Invalid Vref interval")
        tolerance(self.resistor_tolerance_percent, False)
        return self


class DatasheetSource(Model):
    url: str
    revision: str
    page: int
    section: str
    summary: str
    sha256: str

    @model_validator(mode="after")
    def checked(self):
        for v, n in [(self.url, 1000), (self.revision, 128), (self.section, 256), (self.summary, 2000)]:
            text(v, n)
        u = urlsplit(self.url)
        if (
            u.scheme != "https"
            or not u.hostname
            or u.username
            or u.password
            or self.page < 1
            or not re.fullmatch("[0-9a-f]{64}", self.sha256)
        ):
            raise ValueError("HTTPS source/revision/page/SHA required")
        return self


class AuditRule(Model):
    id: str
    version: str
    type: Literal["VALUE_RANGE", "ATTRIBUTE_PRESENT", "PIN_CONNECTED", "NET_MATCH", "FB_DIVIDER"]
    target: Target
    condition: str
    applicable: bool = True
    not_applicable_reason: str | None = None
    attribute: str | None = None
    unit: str | None = None
    expected: Bounds | None = None
    expected_net: str | None = None
    divider: Divider | None = None
    source: DatasheetSource

    @model_validator(mode="after")
    def checked(self):
        text(self.id, 128)
        text(self.version, 64)
        text(self.condition, 2000)
        text(self.target.reference, 191)
        if self.target.part_name is not None:
            text(self.target.part_name, 191)
        if not self.applicable:
            text(self.not_applicable_reason, 2000)
        if self.type in {"PIN_CONNECTED", "NET_MATCH", "FB_DIVIDER"}:
            text(self.target.pin_number, 128)
        if self.type == "NET_MATCH":
            text(self.expected_net, 191)
        if self.type in {"VALUE_RANGE", "ATTRIBUTE_PRESENT"}:
            text(self.attribute, 128)
            if self.unit not in {"Ω", "F", "H", "V", "A", "%"}:
                raise ValueError("Unsupported unit")
        if self.type == "VALUE_RANGE" and self.expected is None:
            raise ValueError("Explicit bounds required")
        if self.type == "FB_DIVIDER" and (self.unit != "V" or self.divider is None):
            raise ValueError("Explicit divider required")
        return self


class RuleInputAdapter(Protocol):
    def validate(self, rules) -> tuple[AuditRule, ...]: ...


class StructuredRuleAdapter:
    def validate(self, rules):
        if rules is None or not 1 <= len(rules) <= 100:
            raise ValueError("Provide 1..100 explicit rules")
        result = tuple(r if isinstance(r, AuditRule) else AuditRule.model_validate(r) for r in rules)
        if len({r.id for r in result}) != len(result):
            raise ValueError("Duplicate Rule ID")
        return result


class AuditEvidence(Model):
    datasheet: DatasheetSource
    condition: str
    components: Mapping[str, Any]
    specifications: Mapping[str, Any]
    pins: tuple[Any, ...]
    calculation: Mapping[str, str]
    issues: tuple[Any, ...]


class AuditResult(Model):
    id: UUID = Field(default_factory=uuid4)
    rule_id: str
    rule_version: str
    reference: str
    status: Literal["PASS", "FAIL", "UNKNOWN", "NOT_APPLICABLE", "ERROR"]
    measured: Decimal | None = None
    expected: Bounds | None = None
    unit: str | None = None
    reason: str
    missing_inputs: tuple[str, ...]
    evidence: AuditEvidence
    executed_at: str = Field(default_factory=now)  # Java Instant nanos를 마이크로초로 잘라 버리지 않는다.

    @model_validator(mode="after")
    def unknown_reason(self):
        if self.status == "UNKNOWN" and not self.missing_inputs:
            raise ValueError("UNKNOWN requires missing inputs")
        return self


class AuditExecution(Model):
    id: UUID = Field(default_factory=uuid4)
    design_id: UUID
    design_version: str
    snapshot_hash: str
    rule_snapshot: tuple[AuditRule, ...]
    results: tuple[AuditResult, ...]
    executed_at: str = Field(default_factory=now)
    timing_ms: Mapping[str, float] = Field(default_factory=dict)


class Work:
    """Rule별 사용 근거·누락 사유를 분리한다. 다른 Rule의 상태를 전파하지 않는다."""

    def __init__(self, g, r):
        self.g, self.r = g, r
        self.missing, self.parts, self.specs, self.pins, self.calculation, self.issues = (
            [],
            {},
            {},
            [],
            {},
            [],
        )

    def miss(self, s):
        if s not in self.missing:
            self.missing.append(s)

    def component(self, ref):
        try:
            c = self.g.get_component(ref)
        except LookupError:
            self.miss("Component " + ref)
            return None
        self.parts[ref] = c
        self.specs[ref] = self.g.get_component_specification(ref)
        for i in self.g.get_component_issues(ref):
            if i not in self.issues:
                self.issues.append(i)
            if (
                i.code in {"DUPLICATE_REFERENCE", "SPECIFICATION_CONFLICT", "VALUE_MISMATCH"}
                or i.severity == "ERROR"
            ):
                self.miss("Conflicting/invalid import data for " + ref)
        return c

    def pin(self, ref, n):
        p = self.g.get_pin_context(ref, n)
        self.pins.append(p)
        return p

    def attribute(self, ref, name, unit):
        if self.component(ref) is None:
            return None
        a = self.g.get_component_attributes(ref).get(name)
        if a is None or a.normalized is None:
            self.miss(ref + "." + name)
            return None
        if a.unit != unit:
            self.miss(ref + "." + name + " incompatible unit")
            return None
        self.calculation[ref + "." + name] = f"{a.normalized:f} {a.unit}; raw={a.raw}"
        return a.normalized

    def result(self, status, value, reason):
        if status not in {"ERROR", "NOT_APPLICABLE"} and self.missing:
            status = "UNKNOWN"
        return AuditResult(
            rule_id=self.r.id,
            rule_version=self.r.version,
            reference=self.r.target.reference,
            status=status,
            measured=value,
            expected=self.r.expected,
            unit=self.r.unit,
            reason=reason,
            missing_inputs=self.missing,
            evidence=AuditEvidence(
                datasheet=self.r.source,
                condition=self.r.condition,
                components=self.parts,
                specifications=self.specs,
                pins=self.pins,
                calculation=self.calculation,
                issues=self.issues,
            ),
        )

    def compare(self, value, reason):
        if value is None:
            return self.result("UNKNOWN", None, "Numeric input missing or incompatible")
        b = self.r.expected
        if b is None:
            self.miss("Explicit expected target/range")
            return self.result("UNKNOWN", value, "Calculated nominal is not a conformity judgement")
        t = b.tolerance_percent / 100
        low = b.min - abs(b.min) * t
        high = b.max + abs(b.max) * t
        self.calculation.update(
            allowedInterval=f"{low:f}..{high:f} {self.r.unit}",
            comparisonTolerancePercent=f"{b.tolerance_percent:f}",
        )
        return self.result("PASS" if low <= value <= high else "FAIL", value, reason)

    def opposite(self, ref, net):
        ps = self.g.get_component_pins(ref)
        if len(ps) != 2:
            return None
        a, b = [self.pin(ref, p.key.number) for p in ps]
        if a["status"] != "CONNECTED" or b["status"] != "CONNECTED":
            return None
        af, bf = a["net"].id == net, b["net"].id == net
        return None if af == bf else b if af else a

    def divider(self):
        d = self.r.divider
        fb = self.pin(self.r.target.reference, self.r.target.pin_number)
        gnd = self.pin(self.r.target.reference, d.ground_pin)
        if fb["status"] != "CONNECTED" or gnd["status"] != "CONNECTED":
            self.miss("Unambiguous FB and GND connections")
            return self.result("UNKNOWN", None, "Divider topology unavailable")
        up, lo = self.component(d.upper_reference), self.component(d.lower_reference)
        if up is None or lo is None:
            return self.result("UNKNOWN", None, "Divider component missing")
        for c in (up, lo):
            if c.assembly_status != "FITTED":
                self.miss(c.reference + " is not confirmed fitted: " + c.assembly_status)
        if up.assembly_status != "FITTED" or lo.assembly_status != "FITTED":
            return self.result("UNKNOWN", None, "Unpopulated divider cannot establish operating set point")
        upper_pin, lower_pin = (
            self.opposite(up.reference, fb["net"].id),
            self.opposite(lo.reference, fb["net"].id),
        )
        if (
            upper_pin is None
            or lower_pin is None
            or lower_pin["net"].id != gnd["net"].id
            or upper_pin["net"].id == gnd["net"].id
        ):
            self.miss("Verified output→upper→FB→lower→GND topology")
            return self.result("UNKNOWN", None, "Divider topology contradictory")
        ru, rl = (
            self.attribute(up.reference, "Resistance", "Ω"),
            self.attribute(lo.reference, "Resistance", "Ω"),
        )
        if ru is None or rl is None or ru < 0 or rl <= 0:
            self.miss("Nonnegative upper and positive lower resistance")
            return self.result("UNKNOWN", None, "Invalid divider resistance; no division by zero")
        self.calculation.update(
            formula="Vout = Vref * (1 + Rupper / Rlower)",
            Vref=f"{d.vref:f} V",
            outputNetLabelNotMeasuredVoltage=upper_pin["net"].name,
        )
        nominal = d.vref * (1 + ru / rl)
        if d.resistor_tolerance_percent is None:
            self.miss("Verified resistor tolerance percentage")
        else:
            t = d.resistor_tolerance_percent / 100
            low = d.vref_min * (1 + ru * (1 - t) / (rl * (1 + t)))
            high = d.vref_max * (1 + ru * (1 + t) / (rl * (1 - t)))
            self.calculation.update(
                calculatedWorstCaseIntervalV=f"{low:f}..{high:f}",
                resistorTolerancePercent=f"{d.resistor_tolerance_percent:f}",
            )
            if self.r.expected:
                b = self.r.expected
                margin = b.tolerance_percent / 100
                return self.result(
                    "PASS"
                    if low >= b.min - abs(b.min) * margin and high <= b.max + abs(b.max) * margin
                    else "FAIL",
                    nominal,
                    "Nominal and resistor/Vref interval under explicit conditions",
                )
        return self.compare(nominal, "Nominal equation evaluation under stated conditions")


class DeterministicRuleChecker:
    def evaluate(self, g, r):
        w = Work(g, r)
        try:
            with localcontext() as ctx:
                ctx.prec = 34  # Java DECIMAL128와 동일한 정밀도. float/LLM 판정을 사용하지 않는다.
                if not r.applicable:
                    return w.result("NOT_APPLICABLE", None, r.not_applicable_reason)
                c = w.component(r.target.reference)
                if c is None:
                    return w.result("UNKNOWN", None, "Target missing")
                if (
                    r.target.part_name
                    and g.get_component_attributes(c.reference).get("Part Name", AttributeValue()).raw
                    != r.target.part_name
                ):
                    return w.result(
                        "NOT_APPLICABLE", None, "Part Name applicability does not match; no MPN guessing"
                    )
                if c.assembly_status == "DNP":
                    return w.result("NOT_APPLICABLE", None, "Target not mounted")
                if c.assembly_status == "UNSPECIFIED":
                    w.miss("Target assembly state unspecified")
                if r.target.require_verified_mpn:
                    w.miss(
                        "Verified manufacturer MPN / applicable silicon revision"
                        if g.get_component_specification(c.reference).mpn is None
                        else "Independent MPN / datasheet attestation unavailable"
                    )
                if r.type == "VALUE_RANGE":
                    return w.compare(
                        w.attribute(c.reference, r.attribute, r.unit),
                        "Nominal BOM attribute only; no operating behavior implied",
                    )
                if r.type == "ATTRIBUTE_PRESENT":
                    v = w.attribute(c.reference, r.attribute, r.unit)
                    return w.result(
                        "UNKNOWN" if v is None else "PASS", v, "Explicit numeric attribute lookup"
                    )
                if r.type in {"PIN_CONNECTED", "NET_MATCH"}:
                    p = w.pin(c.reference, r.target.pin_number)
                    if p["status"] != "CONNECTED":
                        w.miss("Pin connection state: " + p["status"])
                        return w.result(
                            "UNKNOWN", None, "No unambiguous connection; TERM is not automatically an error"
                        )
                    ns = [n for n in g.get_components_on_net(p["net"].id) if n.reference != c.reference]
                    w.calculation["sameNetNeighborReferences"] = ",".join(sorted(n.reference for n in ns))
                    if r.type == "PIN_CONNECTED":
                        for n in ns:
                            w.component(n.reference)
                        if not any(n.assembly_status == "FITTED" for n in ns):
                            w.miss("No fitted neighbor (DNP is logical topology only)")
                    return w.result(
                        "FAIL" if r.type == "NET_MATCH" and r.expected_net != p["net"].name else "PASS",
                        None,
                        "Explicit topology; no actual voltage inferred",
                    )
                return w.divider()
        except Exception:
            logging.getLogger(__name__).exception("Rule execution failed")
            return w.result("ERROR", None, "Rule execution failed internally; no conformity judgement")


def catalog():
    d = json.loads(
        (Path(__file__).parent / "resources/poc/tps54302-rules.json").read_text(), parse_float=Decimal
    )
    return d, StructuredRuleAdapter().validate(d["rules"])


def snapshot_hash(g):
    d = wire(g.design)
    for name, key in [("pins", "key"), ("connections", "pin")]:
        d[name].sort(key=lambda p: (p[key]["reference"], p[key]["number"]))
    # Python 정규 JSON은 Decimal 문자열을 사용한다. 기존 Java의 저장된 Audit hash는 변경하지 않는다.
    return hashlib.sha256(
        json.dumps(d, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()
    ).hexdigest()


def run_audit(g, rules):
    s = perf_counter()
    rules = StructuredRuleAdapter().validate(rules)
    times = {"ruleValidationMs": (perf_counter() - s) * 1000}
    s = perf_counter()
    results = tuple(DeterministicRuleChecker().evaluate(g, r) for r in rules)
    times["ruleEvaluationIncludingGraphLookupsMs"] = (perf_counter() - s) * 1000
    s = perf_counter()
    h = snapshot_hash(g)
    times["snapshotHashMs"] = (perf_counter() - s) * 1000
    return AuditExecution(
        design_id=g.design.id,
        design_version=g.design.version,
        snapshot_hash=h,
        rule_snapshot=rules,
        results=results,
        timing_ms=times,
    )
