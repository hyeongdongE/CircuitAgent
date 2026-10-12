# CircuitAgent — XLSX BOM + QCV 파싱 코어

이 프로젝트는 **BOM.xlsx와 Netlist.qcv를 읽고 Reference로 결합해 연결 관계를 조회**하는 Python 코드다. 입력 형식은 두 가지로 고정한다. 회로 원본을 정확히 보존하고, 누락/중복/모호 연결은 Issue로 기록한다.

API 서버, DB, 웹 화면, Rule Checker, 다른 입력 형식 파서 및 관련 산출물은 이번 범위에서 제거했다. 이전 전체 PoC는 Git 이력에 보존되어 있다. 이 버전에는 프론트/백엔드 서버 실행 명령이 없으며 CLI로 파일을 처리한다.

## 파일을 읽는 순서

| 순서 | 파일 | 담당 기능 |
| --- | --- | --- |
| 1 | [cli.py](backend/circuitagent/cli.py) | 두 파일을 읽고 아래 함수들을 순서대로 호출하는 진입점 |
| 2 | [models.py](backend/circuitagent/models.py) | 부품·사양·핀·Net·연결·Issue 데이터 구조 |
| 3 | [bom_parser.py](backend/circuitagent/bom_parser.py) | XLSX 시트/헤더/행 → 부품 인스턴스와 동적 사양 |
| 4 | [unit_normalizer.py](backend/circuitagent/unit_normalizer.py) | 1kΩ→1000Ω 등의 Decimal 단위 정규화 |
| 5 | [netlist_parser.py](backend/circuitagent/netlist_parser.py) | QCV NET/PIN → Reference·핀·Net 연결 |
| 6 | [circuit_graph.py](backend/circuitagent/circuit_graph.py) | Reference 통합과 연결 조회 인덱스 |

클래스/함수 docstring과 주요 분기 주석은 한국어로 작성했다. [처리 흐름 설명](docs/parsing-core.md)을 코드와 함께 읽는다.

## 설치 및 실행

Python3.12 권장(최소3.11). 프로젝트 루트의 Windows PowerShell:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --require-hashes -r backend/requirements.lock
.\.venv\Scripts\python.exe -m pip install --no-deps -e backend

.\.venv\Scripts\python.exe -m circuitagent.cli --bom .local/qcv-fixtures/fe.xlsx --netlist .local/qcv-fixtures/fe.qcv --component U16 --pin 5 --output circuit.json
```

본인 파일이면 `--bom`, `--netlist` 경로만 변경한다. 다른 형식 파일의 확장자를 바꾸는 것으로 변환되지 않는다. `--output`은 전체 통합 모델을 JSON으로 저장하는 선택 옵션이다. Decimal은 정밀 문자열로 출력한다. MySQL/Docker/브라우저를 설치할 필요가 없다.

macOS/Linux:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install --require-hashes -r backend/requirements.lock
.venv/bin/python -m pip install --no-deps -e backend
.venv/bin/python -m circuitagent.cli --bom .local/qcv-fixtures/fe.xlsx --netlist .local/qcv-fixtures/fe.qcv --component U16 --pin 5
```

## 가변 XLSX 헤더

행 수와 컬럼 순서는 고정하지 않는다. 알려진 Reference/RefDes/Value 등의 별칭을 자동 식별하고, 알 수 없는 컬럼은 동적 속성으로 보존한다. 특이한 헤더는 다음 JSON을 `mapping.json`으로 저장한다:

```json
{"columns":{"reference":"부품번호","value":"값","dnp":"장착제외"},"units":{"사용자 전압":"V"},"noMountBlank":"UNSPECIFIED"}
```

```powershell
.\.venv\Scripts\python.exe -m circuitagent.cli --bom BOM.xlsx --netlist Netlist.qcv --bom-mapping mapping.json
```

핵심 필드는 Reference, 제조사, MPN, Type이며 나머지는 속성 Map이다. 누락은 None, 실제0은 Decimal(0)이다. 같은 MPN도 속성이 다르면 재사용하지 않는다. 수식은 계산하지 않고 오류로 거절한다. 셀 저장값을 읽으며 Excel 스타일 표시값을 재현하지 않는다.

## 테스트

프로젝트 루트 PowerShell:

```powershell
.\.venv\Scripts\python.exe -m pytest -q backend/tests
.\.venv\Scripts\python.exe -m ruff check backend
.\.venv\Scripts\python.exe -m build backend
```

실제3보드 입력은 ZIP의 `.local/qcv-fixtures`에 포함된다. Git에는 원본 이용 조건이 불명확해 파일 자체를 커밋하지 않으며, Git으로만 받은 경우 동일 체크섬의 XLSX/QCV를 해당 경로에 별도로 준비해야 한다. 실제 파일이 없으면 테스트는 실패하며 가짜 입력으로 대체하거나 skip하지 않는다. 출처/원본·파생 파일 체크섬은 [fixture-sources.json](backend/tests/fixture-sources.json)에 있다. BOM XLSX는 공개 설계 원본에서 이전 작업에 변환·검증한 파일이며 이번 코어에 다른 형식 파서/변환 코드는 포함하지 않는다.

공개 Sparrow FE 기준: BOM445 / QCV453 / Reference매칭445 / Net335 / 연결1500 / TERM135 / NoMount51 / Issue80. QCV만의8개 부품도 보존한다. 실제 결과와 한계는 [검증 문서](docs/core-validation.md)에 기록한다.
