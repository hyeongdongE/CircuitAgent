# XLSX + QCV 코어 검증

최신 소스는 API/DB/UI/Rule 및 다른 입력 형식 파서를 제거한 Python0.3.0이다. 기존 전체 PoC의 테스트 수와 합산하지 않는다. 최신 정확한 소스/시간/빌드 해시는 [core-test-results.json](core-test-results.json)에 기록한다.

| 실제 공개 설계 | BOM 부품 | QCV 부품 | 매칭 | Net | 연결 | TERM | NoMount | Issue |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Sparrow FE R3 | 445 | 453 | 445 | 335 | 1500 | 135 | 51 | 80 |
| Sparrow NTop R3 | 296 | 304 | 296 | 233 | 972 | 139 | 67 | 93 |
| Sparrow FE-NTop R3 | 161 | 169 | 161 | 193 | 794 | 17 | 37 | 129 |

매칭 수치는 실제 XLSX/QCV Reference 집합의 교집합으로 직접 계산했다. BOM에만 존재하는 기구 부품도 삭제하지 않는다. 실제 U16-5는 U16_EN에서 R107-2/R83-2와 연결되며 R107=140000Ω, R83=56000Ω이고 둘 다 DNP다. QCV 별도 PIN과 NET 연결은 구분한다.

검증 명령:

```sh
python -m pytest -q backend/tests
python -m ruff check backend
python -m build backend
python -m circuitagent.cli --bom .local/qcv-fixtures/fe.xlsx --netlist .local/qcv-fixtures/fe.qcv --component U16 --pin 5 --output circuit.json
```

새 가상환경에서 고정 해시 의존성 설치·편집 가능 패키지 설치를 실행했다. 전체79개 PASS/FAIL0/ERROR0/SKIP0, lint/wheel/sdist/설치 wheel의 실제 CLI 실행이 통과했다. 모든 핵심 클래스/함수의 docstring 존재를 AST로 검사했다. Windows x64/Python3.12 패키지16개의 wheel/hash 설치 계획도 통과했지만 Windows OS에서 직접 실행한 결과는 아니다.

원본에서 이전 작업에 만든 XLSX의 기본 스타일이 없어 openpyxl 경고3개가 발생했다. 원본은 수정하지 않았고 값/연결 검증에는 영향이 없었다. 테스트는 실제3보드/체크섬/변동 행/헤더 매핑/여러 시트/그룹 Reference/중복/누락0구분/사양 공유 및 충돌/단위/DNP/수식/ZIP한도/잘못된 파일/QCV 중복·충돌·계층이름/0Ω Net 분리/CLI를 포함한다.

외부 입력은 `.local/qcv-fixtures`의 XLSX/QCV만 사용한다. 원본 이용 조건이 불명확하므로 Git에는 [출처와 체크섬](../backend/tests/fixture-sources.json)만 기록한다. 이번 코어에는 별도 원본 다운로드/다른 형식 변환 코드가 없다. Git만 받은 환경에서는 해당 실제 파일을 따로 준비해야 한다. 자체 테스트 입력은 실제 공개 회로와 구분한다.

삼성 내부 QCV 변형, QCV 다중 줄 continuation, XLSX 수식 재계산 및 Excel 표시 스타일 재현은 미지원이다. 삭제한 DB/UI/Audit는 이 버전의 테스트 대상이 아니다.
