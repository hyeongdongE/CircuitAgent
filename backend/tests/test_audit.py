"""실제 FE 결과와 자체 Fixture의 다섯 상태를 구분하여 검증한다."""

from decimal import Decimal

import pytest
from circuitagent.audit import (
    AuditRule,
    DeterministicRuleChecker,
    StructuredRuleAdapter,
    catalog,
    run_audit,
    snapshot_hash,
)
from circuitagent.bom import parse_bom
from circuitagent.domain import CircuitDesign, wire
from circuitagent.graph import CircuitGraph, assemble
from circuitagent.netlist import QcvNetlistParser


def small(value="1k", flag="no"):
    b = parse_bom(f"Reference,Value,DNP\nR1,{value},{flag}\nR2,1k,no\n".encode(), "bom.csv")
    n = QcvNetlistParser().parse(b"NET: 'A' R1-1 R2-1\nNET: 'B' R1-2\nNET: 'GND' R2-2\n", "x.qcv")
    return CircuitGraph(assemble("Own fixture", "v1", b, n))


def rule(**changes):
    raw = wire(catalog()[1][3])
    raw.update(
        id="own-resistor",
        type="VALUE_RANGE",
        target={"reference": "R1", "requireVerifiedMpn": False},
        attribute="Resistance",
        unit="Ω",
        expected={"min": "900", "max": "1100", "tolerancePercent": "0"},
    )
    raw["source"] = {
        "url": "https://example.org/own-test.pdf",
        "revision": "authored-fixture",
        "page": 1,
        "section": "test-input",
        "summary": "Own synthetic unit test; not a manufacturer claim",
        "sha256": "0" * 64,
    }
    raw.update(changes)
    return AuditRule.model_validate(raw)


def test_actual_audit(boards):
    a = run_audit(boards["fe"][3], catalog()[1])
    assert [r.status for r in a.results] == ["UNKNOWN", "UNKNOWN", "UNKNOWN", "PASS", "UNKNOWN", "UNKNOWN"]
    assert a.results[2].measured == Decimal("11.96158139534883720930232558139535")
    assert a.results[3].measured == Decimal("1e-7")
    assert all(r.missing_inputs for r in a.results if r.status == "UNKNOWN")
    assert a.results[0].evidence.components["R107"].assembly_status == "DNP"


@pytest.mark.parametrize(
    "value,flag,status",
    [
        ("1k", "no", "PASS"),
        ("2k", "no", "FAIL"),
        ("", "no", "UNKNOWN"),
        ("1k", "yes", "NOT_APPLICABLE"),
        ("1k", "", "UNKNOWN"),
        ("bad", "no", "UNKNOWN"),
        ("0", "no", "FAIL"),
    ],
)
def test_states(value, flag, status):
    assert DeterministicRuleChecker().evaluate(small(value, flag), rule()).status == status


@pytest.mark.parametrize(
    "kind,net,status",
    [("PIN_CONNECTED", None, "PASS"), ("NET_MATCH", "A", "PASS"), ("NET_MATCH", "wrong", "FAIL")],
)
def test_connection_types(kind, net, status):
    r = rule(
        type=kind,
        target={"reference": "R1", "pinNumber": "1"},
        expectedNet=net,
        expected=None,
        attribute=None,
        unit=None,
    )
    assert DeterministicRuleChecker().evaluate(small(), r).status == status


@pytest.mark.parametrize(
    "field,value",
    [
        ("id", ""),
        ("unit", "bad"),
        ("version", ""),
        ("type", "PYTHON_EVAL"),
        ("expected", {"min": "2", "max": "1", "tolerancePercent": "0"}),
        ("expected", {"min": "0", "max": "1e999", "tolerancePercent": "0"}),
        ("unexpected", True),
    ],
)
def test_bad_rules(field, value):
    raw = wire(rule())
    raw[field] = value
    with pytest.raises(ValueError):
        AuditRule.model_validate(raw)


def test_unknown_missing_and_verified_mpn():
    checker = DeterministicRuleChecker()
    assert checker.evaluate(small(), rule(target={"reference": "not-there"})).status == "UNKNOWN"
    assert (
        checker.evaluate(small(), rule(target={"reference": "R1", "requireVerifiedMpn": True})).status
        == "UNKNOWN"
    )
    assert (
        checker.evaluate(small(), rule(applicable=False, notApplicableReason="Different conditions")).status
        == "NOT_APPLICABLE"
    )


def test_error_is_not_fail(monkeypatch):
    g = small()
    monkeypatch.setattr(g, "get_component", lambda ref: (_ for _ in ()).throw(RuntimeError("private-secret")))
    r = DeterministicRuleChecker().evaluate(g, rule())
    assert r.status == "ERROR" and "private-secret" not in str(wire(r))


def test_duplicates_and_hash_order(boards):
    with pytest.raises(ValueError):
        StructuredRuleAdapter().validate([rule(), rule()])
    d = boards["fe"][2]
    raw = wire(d)
    raw["pins"].reverse()
    raw["connections"].reverse()
    assert snapshot_hash(boards["fe"][3]) == snapshot_hash(CircuitGraph(CircuitDesign.model_validate(raw)))


@pytest.mark.parametrize(
    "upper,lower,mounted,bounds,tol,status",
    [
        ("10k", "10k", "no", ("1.9", "2.1"), "1", "PASS"),
        ("20k", "10k", "no", ("1.9", "2.1"), "1", "FAIL"),
        ("10k", "0", "no", ("1.9", "2.1"), "1", "UNKNOWN"),
        ("10k", "10k", "yes", ("1.9", "2.1"), "1", "UNKNOWN"),
        ("10k", "10k", "no", None, "1", "UNKNOWN"),
        ("10k", "10k", "no", ("1.9", "2.1"), None, "UNKNOWN"),
    ],
)
def test_own_divider(upper, lower, mounted, bounds, tol, status):
    bom = parse_bom(
        f"Reference,Value,DNP\nU1,OWN,no\nR1,{upper},{mounted}\nR2,{lower},no\n".encode(), "own.csv"
    )
    net = QcvNetlistParser().parse(
        b"NET: 'OUT' R1-1 U1-6\nNET: 'FB' R1-2 R2-1 U1-4\nNET: 'GND' R2-2 U1-1\n", "own.qcv"
    )
    g = CircuitGraph(assemble("Own divider fixture", "v1", bom, net))
    r = rule(
        type="FB_DIVIDER",
        target={"reference": "U1", "pinNumber": "4"},
        attribute=None,
        unit="V",
        expected={"min": bounds[0], "max": bounds[1], "tolerancePercent": "0"} if bounds else None,
        divider={
            "upperReference": "R1",
            "lowerReference": "R2",
            "groundPin": "1",
            "vref": "1",
            "vrefMin": "1",
            "vrefMax": "1",
            "resistorTolerancePercent": tol,
        },
    )
    result = DeterministicRuleChecker().evaluate(g, r)
    assert result.status == status
    if status == "PASS":
        assert result.measured == 2 and "calculatedWorstCaseIntervalV" in result.evidence.calculation
