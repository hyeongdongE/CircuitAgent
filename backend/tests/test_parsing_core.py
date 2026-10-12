"""XLSX+QCV만 검증한다. 실제 공개 회로 회귀와 별도 작성 오류 입력을 구분한다."""

import hashlib
import io
import json
from decimal import Decimal
from pathlib import Path

import openpyxl
import pytest

from circuitagent.bom_parser import MAX_BYTES, parse_bom
from circuitagent.circuit_graph import CircuitGraph, assemble
from circuitagent.models import CircuitDesign, PinKey, wire
from circuitagent.netlist_parser import QcvNetlistParser
from circuitagent.unit_normalizer import normalize


def workbook_bytes(rows, extra_sheets=None):
    """자체 오류/경계 테스트 입력을 만든다. 공개 회로 원본으로 주장하지 않는다."""
    book = openpyxl.Workbook()
    book.active.title = "BOM"
    for row in rows:
        book.active.append(row)
    for name, rows in (extra_sheets or {}).items():
        sheet = book.create_sheet(name)
        for row in rows:
            sheet.append(row)
    data = io.BytesIO()
    book.save(data)
    book.close()
    return data.getvalue()


@pytest.mark.parametrize(
    "board,bom_count,nets,connected,term,dnp,issues",
    [
        ("fe", 445, 335, 1500, 135, 51, 80),
        ("ntop", 296, 233, 972, 139, 67, 93),
        ("fe-ntop", 161, 193, 794, 17, 37, 129),
    ],
)
def test_real_public_boards(boards, board, bom_count, nets, connected, term, dnp, issues):
    bom, netlist, design, graph = boards[board]
    assert len(bom.components) == bom_count and len(netlist.components) == bom_count + 8
    assert set(bom.components) <= set(netlist.components)
    assert len(design.nets) == nets and len(design.connections) == connected
    assert sum(p.disposition == "TERMINATED" for p in design.pins) == term
    assert sum(c.assembly_status == "DNP" for c in bom.components.values()) == dnp
    assert len(design.issues) == issues
    assert not any(i.code == "INVALID_UNIT" for i in design.issues)


def test_real_fixture_checksums():
    root = Path(__file__).parents[2]
    for source in json.loads((Path(__file__).parent / "fixture-sources.json").read_text())["files"]:
        data = (root / ".local/qcv-fixtures" / source["local"]).read_bytes()
        assert len(data) == source["bytes"] and hashlib.sha256(data).hexdigest() == source["sha256"]


def test_actual_u16_connection(boards):
    graph = boards["fe"][3]
    context = graph.get_pin_context("U16", "5")
    assert context["net"].name == "U16_EN"
    assert {p.key for p in graph.get_connected_pins(context["net"].id)} == {
        PinKey(reference=r, number=n) for r, n in [("U16", "5"), ("R107", "2"), ("R83", "2")]
    }
    assert graph.get_component_attributes("R107")["Resistance"].normalized == 140000
    assert graph.get_component_attributes("R83")["Resistance"].normalized == 56000
    for ref in ["R107", "R83"]:
        assert graph.get_component(ref).assembly_status == "DNP"
        assert graph.get_net_of_pin(ref, "1").id != context["net"].id
    assert graph.get_pin_context("U16", "unknown")["status"] == "MISSING_PIN"
    with pytest.raises(LookupError):
        graph.get_component("unknown")


@pytest.mark.parametrize("count", [1, 7, 137])
def test_dynamic_rows_and_optional_columns(count):
    rows = [["Comment", "Reference", "Value", "DNP", "Custom Voltage"]]
    rows += [
        ["note-" + str(i), "R" + str(i), "1k", "no", "3.3V" if i % 2 else None] for i in range(1, count + 1)
    ]
    bom = parse_bom(workbook_bytes(rows), "bom.xlsx", {"units": {"Custom Voltage": "V"}})
    assert len(bom.components) == count
    for i, c in enumerate(bom.components.values(), 1):
        attrs = bom.specifications[c.part_specification_id].attributes
        assert attrs["Resistance"].normalized == 1000
        assert ("Custom Voltage" in attrs) == bool(i % 2)
        assert attrs["Comment"].raw == "note-" + str(i)


def test_header_mapping_and_original_source():
    data = workbook_bytes([["설명"], ["메모", "부품번호", "값", "장착제외"], ["한글", "R1 R2", "10k", "no"]])
    bom = parse_bom(
        data, "bom.xlsx", {"columns": {"reference": "부품번호", "value": "값", "dnp": "장착제외"}}
    )
    assert set(bom.components) == {"R1", "R2"}
    for c in bom.components.values():
        assert c.raw_sources["bom"]["메모"] == "한글"
        assert c.raw_sources["provenance"]["bomLocation"] == "sheet:BOM:row:3"
        assert bom.specifications[c.part_specification_id].attributes["Resistance"].normalized == 10000


def test_multiple_sheets_and_zero_vs_missing():
    data = workbook_bytes(
        [["MPN", "Value", "Reference", "Tolerance"], [None, 0, "R1", None]],
        {"Other": [["Reference", "Value"], ["R2", None]]},
    )
    bom = parse_bom(data, "bom.xlsx")
    assert set(bom.components) == {"R1", "R2"}
    r1 = bom.specifications[bom.components["R1"].part_specification_id]
    assert r1.mpn is None and "Tolerance" not in r1.attributes and r1.attributes["Resistance"].normalized == 0
    assert bom.components["R2"].value is None
    assert bom.components["R2"].source_path == "sheet:Other:row:2"


def test_groups_duplicate_reference_and_shared_mpn():
    bom = parse_bom(
        workbook_bytes(
            [
                ["Value", "Reference", "Quantity", "MPN"],
                ["1k", "R1,R2", 2, "PART"],
                ["1000", "R3", 1, "PART"],
                ["2k", "R1", 1, "PART"],
                [None, None, 1, None],
                [None, "R4", 2, None],
            ]
        ),
        "bom.xlsx",
    )
    assert bom.components["R1"].part_specification_id == bom.components["R3"].part_specification_id
    assert bom.components["R1"].value == "1k" and bom.components["R4"].value is None
    assert {"DUPLICATE_REFERENCE", "MISSING_REFERENCE", "QUANTITY_MISMATCH", "MISSING_VALUE"} <= {
        i.code for i in bom.issues
    }


def test_conflicting_same_mpn_keeps_separate_specifications():
    bom = parse_bom(
        workbook_bytes([["Reference", "Value", "MPN"], ["R1", "1k", "PART"], ["R2", "2k", "PART"]]),
        "bom.xlsx",
    )
    assert bom.components["R1"].part_specification_id != bom.components["R2"].part_specification_id
    assert any(i.code == "SPECIFICATION_CONFLICT" for i in bom.issues)


@pytest.mark.parametrize(
    "flag,expected",
    [
        ("yes", "DNP"),
        ("no", "FITTED"),
        (None, "UNSPECIFIED"),
        ("maybe", "UNSPECIFIED"),
        (1, "DNP"),
        (0, "FITTED"),
    ],
)
def test_dnp(flag, expected):
    bom = parse_bom(workbook_bytes([["Reference", "Value", "DNP"], ["R1", "0R", flag]]), "bom.xlsx")
    assert bom.components["R1"].assembly_status == expected


def test_unknown_no_mount_blank_not_guessed():
    data = workbook_bytes([["Reference", "Value", "NoMount"], ["R1", "1k", None]])
    assert parse_bom(data, "bom.xlsx").components["R1"].assembly_status == "UNSPECIFIED"
    assert (
        parse_bom(data, "bom.xlsx", {"noMountBlank": "FITTED"}).components["R1"].assembly_status == "FITTED"
    )


def test_unnamed_column_preserved_with_issue():
    bom = parse_bom(workbook_bytes([["Reference", None, "Value"], ["R1", "extra", "1k"]]), "bom.xlsx")
    c = bom.components["R1"]
    assert c.raw_sources["bom"]["column:2"] == "extra"
    assert bom.specifications[c.part_specification_id].attributes["column:2"].raw == "extra"
    assert any(i.code == "PARSE_FAILURE" and i.severity == "ERROR" for i in bom.issues)


@pytest.mark.parametrize(
    "mapping",
    [
        {"columns": {"reference": "missing"}},
        {"columns": {"reference": "Reference", "value": "Reference"}},
        {"units": {"Resistance": "F"}},
        {"surprise": True},
    ],
)
def test_invalid_mapping(mapping):
    with pytest.raises(ValueError):
        parse_bom(workbook_bytes([["Reference", "Value"], ["R1", "1k"]]), "bom.xlsx", mapping)


@pytest.mark.parametrize("headers", [["Value"], ["Reference", "Reference"], ["Reference", "Ref"]])
def test_invalid_headers(headers):
    with pytest.raises(ValueError):
        parse_bom(workbook_bytes([headers, ["R1"]]), "bom.xlsx")


def test_formula_rejected():
    with pytest.raises(ValueError, match="Formula"):
        parse_bom(workbook_bytes([["Reference", "Value"], ["R1", "=1+1"]]), "bom.xlsx")


@pytest.mark.parametrize("filename", ["bom.csv", "bom.xls", "bom.txt"])
def test_other_bom_extensions_rejected(filename):
    with pytest.raises(ValueError, match="XLSX"):
        parse_bom(workbook_bytes([["Reference"], ["R1"]]), filename)


@pytest.mark.parametrize("data", [b"", b"fake", b"PK\x03\x04bad", b" " * (MAX_BYTES + 1)])
def test_bad_xlsx(data):
    with pytest.raises(ValueError):
        parse_bom(data, "bom.xlsx")


@pytest.mark.parametrize(
    "raw,unit,expected",
    [
        ("1kΩ", "Ω", "1000"),
        ("4R7", "Ω", "4.7"),
        ("0R", "Ω", "0"),
        ("1M", "Ω", "1000000"),
        ("1m", "Ω", ".001"),
        ("100nF", "F", ".0000001"),
        ("4u7", "F", ".0000047"),
        ("2,2u", "F", ".0000022"),
        ("1uH", "H", ".000001"),
        ("6V3", "V", "6.3"),
        ("1A5", "A", "1.5"),
        ("±1%", "%", "1"),
        ("0", "F", "0"),
        ("47K", "Ω", "47000"),
        ("100µF", "F", ".0001"),
    ],
)
def test_units(raw, unit, expected):
    a = normalize(raw, unit)
    assert a.normalized == Decimal(expected) and a.raw == raw and a.unit == unit


@pytest.mark.parametrize(
    "raw,unit",
    [
        ("1,000", "Ω"),
        ("5V", "Ω"),
        ("NaN", "Ω"),
        ("inf", "F"),
        ("1e999", "Ω"),
        ("1e999999999999999999999999999999", "Ω"),
        ("2k%", "%"),
    ],
)
def test_invalid_units(raw, unit):
    with pytest.raises(ValueError):
        normalize(raw, unit)


def test_qcv_duplicates_and_conflicting_connections():
    net = QcvNetlistParser().parse(
        b"NET: 'A' R1-1 R2-1\nNET: 'A' R1-1\nNET: 'B' R1-1\nPIN: '/U1' U1-5 (by TERM)\n", "net.qcv"
    )
    assert len(net.nets) == 2 and len(net.connections) == 1
    assert {i.code for i in net.issues} >= {"DUPLICATE_NET", "DUPLICATE_CONNECTION", "AMBIGUOUS_CONNECTION"}
    assert next(p for p in net.pins if p.key.reference == "U1").disposition == "TERMINATED"


def test_qcv_reference_hyphen_and_hierarchical_net_name():
    net = QcvNetlistParser().parse(b"NET: '/sheet/U_EN+X' /sheet/U-1-A1 R1-2\n", "net.qcv")
    assert "/sheet/U-1" in net.components
    assert next(iter(net.nets.values())).name == "/sheet/U_EN+X"
    assert PinKey(reference="/sheet/U-1", number="A1") in {p.key for p in net.pins}


@pytest.mark.parametrize(
    "data",
    [b"", b"garbage", b"NET: A R1-1", b"NET: 'A' broken", b"PIN: 'A' R1-1 R2-1", b"NET: ' ' R1-1", b"\xff"],
)
def test_bad_qcv(data):
    with pytest.raises(ValueError):
        QcvNetlistParser().parse(data, "net.qcv")


def test_other_netlist_extension_rejected():
    with pytest.raises(ValueError, match="QCV"):
        QcvNetlistParser().parse(b"NET: 'A' R1-1\n", "net.net")


def test_bom_only_and_netlist_only_components():
    bom = parse_bom(workbook_bytes([["Reference", "Value"], ["R1", "1k"], ["R2", "2k"]]), "bom.xlsx")
    net = QcvNetlistParser().parse(b"NET: 'A' R1-1 R3-1\n", "net.qcv")
    d = assemble("Own fixture", "v1", bom, net)
    assert set(d.components) == {"R1", "R2", "R3"}
    assert {i.code for i in d.issues} >= {"BOM_ONLY_COMPONENT", "NETLIST_ONLY_COMPONENT"}
    assert not CircuitGraph(d).get_component_pins("R2")


def test_model_immutable_and_validates_relationships(boards):
    design = boards["fe"][2]
    with pytest.raises(TypeError):
        design.components["bad"] = design.components["U16"]
    with pytest.raises(TypeError):
        design.components["U16"].raw_sources["bom"]["Value"] = "fake"
    raw = wire(design)
    raw["connections"].append({"pin": {"reference": "U16", "number": "5"}, "netId": "missing"})
    with pytest.raises(ValueError):
        CircuitDesign.model_validate(raw)


def test_zero_ohm_does_not_merge_nets():
    bom = parse_bom(workbook_bytes([["Reference", "Value", "DNP"], ["R1", "0R", "no"]]), "bom.xlsx")
    net = QcvNetlistParser().parse(b"NET: 'LEFT' R1-1\nNET: 'RIGHT' R1-2\n", "net.qcv")
    graph = CircuitGraph(assemble("Own zero-ohm fixture", "v1", bom, net))
    assert graph.get_component_attributes("R1")["Resistance"].normalized == 0
    assert graph.get_net_of_pin("R1", "1").id != graph.get_net_of_pin("R1", "2").id
    assert len(graph.design.nets) == 2


def test_xlsx_zip_expansion_limit():
    import zipfile

    data = io.BytesIO()
    with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("large.xml", b" " * (32 * 1024 * 1024 + 1))
    with pytest.raises(ValueError, match="expanded"):
        parse_bom(data.getvalue(), "bom.xlsx")


def test_zip_is_not_automatically_an_xlsx():
    import zipfile

    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        archive.writestr("not-a-workbook.txt", "plain data")
    with pytest.raises(ValueError, match="workbook structure"):
        parse_bom(data.getvalue(), "bom.xlsx")
