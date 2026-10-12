# Python PoC 실제 검증

Python3.12.14/Linux, MySQL8.0.43 Docker, FastAPI/SQLAlchemy/Streamlit. SQL V1~V4는 Java 기준 커밋과 byte-identical이며 기존 Flyway/설계/Audit 데이터 인계도 실제 DB에서 확인했다.

| 보드 | BOM | 전체 QCV | 매칭 | Net | 연결 Pin | 별도 PIN | TERM | NoMount | Issue |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Sparrow FE R3 | 445 | 453 | 445/445 | 335 | 1500 | 143 | 135 | 51 | 80 |
| Sparrow N-Top R3 | 296 | 304 | 296/296 | 233 | 972 | 147 | 139 | 67 | 93 |
| Sparrow FE N-Top R3 | 161 | 169 | 161/161 | 193 | 794 | 25 | 17 | 37 | 129 |

5V KiCad: BOM/Netlist12, Pin25/Net6/Connection25, R1=1000Ω, U1=L7805. Shift: BOM27 모두 매칭, Netlist의 H1~H4 보존. 모든 원본 manifest SHA/bytes와 CSV→XLSX 전 셀 보존을 검사한다.

공식 TI TPS54302 JSON fixture의 pin mapping과 source/revision/SHA를 유지한다. 실제 FE Rule6개의 결과는 PASS1/UNKNOWN5이며 C5 BOM nominal100nF 확인만 PASS다. U16 EN5의 R107140kΩ/R8356kΩ는 둘 다 DNP다. FB4의 R82/R87로 계산한 조건부 값은11.96158139534883720930232558139535V이며 신원/목표/공차가 없어 UNKNOWN이다. 자체 작성 범위·분압 Fixture는 별도 PASS/FAIL/UNKNOWN 검증용으로 구분한다.

## 실행

- `cd backend && python -m pytest -q tests --junitxml=...`: 143 PASS/FAIL0/ERROR0/SKIP0. 최종 집계는 [python-test-results.json](python-test-results.json).
- `python -m pytest -q browser_tests`: 실제 Streamlit→FastAPI→MySQL, 공개FE 파일 업로드/탐색/Audit/이력/재조회와 모바일 누락 파일 오류, API mock 없음.
- `python -m ruff check circuitagent tests browser_tests`, `python -m build`: Python lint 및 wheel/sdist. 별도 위치에 wheel을 설치해 import·6 Rule·4 SQL 리소스를 검사했다.
- Java/Gradle/Node/JUnit은 이번 Python 실행에 사용하지 않았다. 이전 Java103/React6/browser2 결과와 현재 테스트 수를 합산하지 않는다.

Testcontainers는 실제 MySQL을 사용하고 Docker가 없으면 실패한다. 원본이 없으면 실패하며 fake 데이터로 대체/skip하지 않는다. 써드파티 Starlette TestClient의 httpx deprecation 경고는 테스트 실패가 아니며 현재 pinned 환경에서 정상 실행했다.

## 출처와 재현

[QCV 고정 manifest](../scripts/qcv-fixtures.json), [공식 데이터시트 manifest](../scripts/tps54302-datasheet.json), [기존 KiCad manifest](../backend/tests/fixtures/sources.json). CSV/QCV/PDF 원본과 변환 XLSX는 ignored .local에만 둔다. 원본 재배포 라이선스를 추정하지 않는다. Git으로 받은 경우 README의 다운로드/변환 명령을 실행한다.

[성능 sample/중앙값/평균](python-performance.json)은 실제 FE3회 측정이다. 파일 read/초기화·컨테이너 생성·network/browser latency는 별도이며 SLA가 아니다. 원래 Java 수치와 동일 조건의 벤치마크라고 주장하지 않는다. 재현은 DB/API 설치 후 환경변수를 설정하고 루트에서 `python scripts/python-benchmark.py`를 실행한다. 이 측정은 새 공개 PoC 설계3개와 Audit을 DB에 저장한다.

삼성 내부 데이터·SSO·Stage01 Parser는 미검증이다. Excel 수식 재계산/복잡한 표시 형식과 모든 QCV dialect 호환성도 완료로 표시하지 않는다.
