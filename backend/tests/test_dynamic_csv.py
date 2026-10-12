"""행 수/열 순서/부품별 속성 집합을 고정하지 않고 CSV를 동적으로 객체화·통합한다."""

import json
from decimal import Decimal

import pytest
from conftest import DATA

from circuitagent.bom import parse_bom
from circuitagent.graph import assemble
from circuitagent.netlist import QcvNetlistParser


@pytest.mark.parametrize("board,count,issues", [("fe", 445, 80), ("ntop", 296, 93), ("fe-ntop", 161, 129)])
def test_original_csv_boards(board, count, issues):
    b = parse_bom((DATA / (board + ".csv")).read_bytes(), board + ".csv")
    n = QcvNetlistParser().parse((DATA / (board + ".qcv")).read_bytes(), board + ".qcv")
    d = assemble("Dynamic CSV", "R3", b, n)
    assert len(b.components) == count and len(d.issues) == issues
    assert json.loads(d.source_metadata["bomColumns"]) and int(d.source_metadata["bomRowCount"]) > 0


@pytest.mark.parametrize("count", [1, 7, 137])
def test_arbitrary_row_count_and_optional_properties(count):
    data = "Comment,Reference,Value,DNP,Custom Voltage,Tolerance\n"
    data += "".join(
        f"note-{i},R{i},1k,no,{'3.3V' if i % 2 else ''},{'1%' if i % 3 else ''}\n"
        for i in range(1, count + 1)
    )
    b = parse_bom(data.encode(), "bom.csv", {"units": {"Custom Voltage": "V"}})
    assert len(b.components) == count
    for i, c in enumerate(b.components.values(), 1):
        a = b.specifications[c.part_specification_id].attributes
        assert a["Comment"].raw == f"note-{i}" and a["Resistance"].normalized == 1000
        assert ("Custom Voltage" in a) == bool(i % 2) and ("Tolerance" in a) == bool(i % 3)


@pytest.mark.parametrize("encoding,delimiter", [("utf-8-sig", ";"), ("cp949", "\t")])
def test_custom_headers_units_and_encoding(encoding, delimiter):
    cells = [["전압표시", "메모", "부품번호", "값", "장착제외"], ["3.3V", "한글", "R1 R2", "10k", "no"]]
    data = "\n".join(delimiter.join(row) for row in cells).encode(encoding)
    config = {
        "columns": {"reference": "부품번호", "value": "값", "dnp": "장착제외", "Custom Voltage": "전압표시"},
        "units": {"Custom Voltage": "V"},
        "encoding": encoding,
        "delimiter": delimiter,
    }
    b = parse_bom(data, "bom.csv", config)
    assert set(b.components) == {"R1", "R2"}
    assert all(
        b.specifications[c.part_specification_id].attributes["Custom Voltage"].normalized == Decimal("3.3")
        for c in b.components.values()
    )
    assert b.components["R1"].raw_sources["bom"]["메모"] == "한글"


def test_short_and_long_rows_preserve_unnamed_values():
    b = parse_bom(b"Reference,Value,Note\nR1,1k\nR2,2k,hello,extra,0\n", "bom.csv")
    r1 = b.specifications[b.components["R1"].part_specification_id]
    r2 = b.specifications[b.components["R2"].part_specification_id]
    assert "Note" not in r1.attributes and b.components["R1"].raw_sources["bom"]["Note"] == ""
    assert r2.attributes["column:4"].raw == "extra" and r2.attributes["column:5"].raw == "0"
    assert r2.attributes["column:5"].normalized is None  # 없는 의미/단위를 추측하지 않는다.
    assert any(i.reference == "R2" and i.code == "PARSE_FAILURE" and i.severity == "ERROR" for i in b.issues)


def test_blank_column_and_generated_name_collision():
    b = parse_bom(b"Reference,,column:2,Value\nR1,unnamed,original,1k\n", "bom.csv")
    raw = b.components["R1"].raw_sources["bom"]
    assert raw["column:2"] == "original" and raw["source:column:2"] == "unnamed"


@pytest.mark.parametrize(
    "config",
    [
        {"columns": {"reference": "missing"}},
        {"columns": {"reference": "Reference", "value": "Reference"}},
        {"units": {"Resistance": "F"}},
        {"units": {"Anything": "bogus"}},
        {"surprise": True},
    ],
)
def test_ambiguous_or_invalid_mapping_rejected(config):
    with pytest.raises(ValueError):
        parse_bom(b"Reference,Value\nR1,1k\n", "bom.csv", config)


def test_explicit_reference_overrides_other_alias_without_loss():
    b = parse_bom(
        b"CustomRef,Reference,Value\nR1,note,1k\n", "bom.csv", {"columns": {"reference": "CustomRef"}}
    )
    assert set(b.components) == {"R1"}
    assert b.specifications[b.components["R1"].part_specification_id].attributes["Reference"].raw == "note"


def test_no_mount_blank_not_guessed_for_unknown_format():
    data = b"Reference,Value,NoMount\nR1,1k,\n"
    assert parse_bom(data, "bom.csv").components["R1"].assembly_status == "UNSPECIFIED"
    assert parse_bom(data, "bom.csv", {"noMountBlank": "FITTED"}).components["R1"].assembly_status == "FITTED"
