"""CLI에서 실제 XLSX와 QCV를 연결하고 전체 모델 JSON을 복원한다."""

import json

import pytest
from conftest import DATA

from circuitagent.cli import main
from circuitagent.models import CircuitDesign


def test_real_fe_cli(capsys, tmp_path):
    output = tmp_path / "circuit.json"
    main(
        [
            "--bom",
            str(DATA / "fe.xlsx"),
            "--netlist",
            str(DATA / "fe.qcv"),
            "--component",
            "U16",
            "--pin",
            "5",
            "--output",
            str(output),
        ]
    )
    result = json.loads(capsys.readouterr().out)
    assert (
        result["bomComponentCount"],
        result["netlistComponentCount"],
        result["matchedReferenceCount"],
        result["netCount"],
        result["connectionCount"],
    ) == (445, 453, 445, 335, 1500)
    assert result["pinContext"]["net"]["name"] == "U16_EN"
    assert {p["reference"] for p in result["componentsOnNet"]} == {"U16", "R107", "R83"}
    restored = CircuitDesign.model_validate(json.loads(output.read_text(encoding="utf-8")))
    assert len(restored.components) == 453 and len(restored.connections) == 1500


@pytest.mark.parametrize("extra", [["--pin", "5"], ["--format", "kicad"], ["--component", "missing"]])
def test_bad_cli(extra):
    with pytest.raises(SystemExit) as error:
        main(["--bom", str(DATA / "fe.xlsx"), "--netlist", str(DATA / "fe.qcv"), *extra])
    assert error.value.code == 2
