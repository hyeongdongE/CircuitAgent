"""실제 Fixture가 없으면 skip/가짜 대체하지 않고 실패한다."""

from pathlib import Path

import pytest
from circuitagent.bom import parse_bom
from circuitagent.graph import CircuitGraph, assemble
from circuitagent.netlist import QcvNetlistParser

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / ".local/qcv-fixtures"


@pytest.fixture(scope="session")
def boards():
    result = {}
    for board in ("fe", "ntop", "fe-ntop"):
        b = parse_bom((DATA / (board + ".xlsx")).read_bytes(), board + ".xlsx")
        n = QcvNetlistParser().parse((DATA / (board + ".qcv")).read_bytes(), board + ".qcv")
        d = assemble("Python " + board, "R3", b, n)
        result[board] = b, n, d, CircuitGraph(d)
    return result
