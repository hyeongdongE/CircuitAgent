# CircuitAgent — Python 통합 PoC

BOM Excel과 공개 PADS QCV/KiCad Netlist를 회로 모델로 만들고, 명시적인 데이터시트 Rule을 적용해 결과와 근거를 확인합니다. **Backend와 화면 모두 Python으로 작성**합니다.

- Python 3.12 권장(최소 3.11), FastAPI, Pydantic, SQLAlchemy, MySQL 8
- Excel: openpyxl/XLSX, xlrd/XLS, 표준 csv / SI 단위는 Decimal
- 화면: Streamlit, 브라우저 검증: Python Playwright
- Graph: 불변 Snapshot + Map/Set 인덱스, 제한 깊이 BFS
- Rule: 범위·속성 존재·Pin 연결·Net 일치·FB 조건부 계산, 다섯 상태와 Evidence
- Java/Gradle/React/Node.js 설치는 필요하지 않습니다. Java 기준 버전은 Git `1a89f39`에 보존합니다.

## 설치

ZIP을 풀고 CircuitAgent 루트에서 실행합니다. 가상환경은 프로젝트 실행과 테스트에 모두 사용합니다.

Windows PowerShell:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install --require-hashes -r backend/requirements.lock
python -m pip install --no-deps -e backend
```

macOS/Linux:

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install --require-hashes -r backend/requirements.lock
python -m pip install --no-deps -e backend
```

`requirements.lock`은 API·화면·테스트 의존성을 모두 고정합니다. API만 필요하면 `python -m pip install -e backend`, 화면까지 설치하려면 `python -m pip install -e 'backend[ui]'`도 가능합니다.

## MySQL

Docker가 실행된 상태에서 처음 한 번 실행합니다. 아래 비밀번호는 로컬 개발용 예시이며 두 터미널 설정과 일치시켜야 합니다.

```sh
docker run --name circuitagent-mysql -e MYSQL_ROOT_PASSWORD=poc-root-password -e MYSQL_DATABASE=circuitagent -e MYSQL_USER=circuitagent -e MYSQL_PASSWORD=poc-db-password -p 127.0.0.1:3306:3306 -d mysql:8.0.43
docker logs circuitagent-mysql
```

`ready for connections`를 확인합니다. 재시작은 `docker start circuitagent-mysql`입니다. 기존 Java DB를 사용할 경우 [전환·DB 호환 문서](docs/python-migration.md)를 먼저 확인하세요.

## Backend — 터미널 1

Windows PowerShell:

```powershell
$env:DB_HOST="127.0.0.1"
$env:DB_PORT="3306"
$env:DB_NAME="circuitagent"
$env:DB_USERNAME="circuitagent"
$env:DB_PASSWORD="poc-db-password"
$env:CIRCUITAGENT_MODE="local-poc"
cd backend
python -m uvicorn circuitagent.api:app --host 127.0.0.1 --port 8080 --no-proxy-headers
```

macOS/Linux:

```sh
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=circuitagent
export DB_USERNAME=circuitagent DB_PASSWORD=poc-db-password CIRCUITAGENT_MODE=local-poc
cd backend
python -m uvicorn circuitagent.api:app --host 127.0.0.1 --port 8080 --no-proxy-headers
```

시작 시 고정 SQL V1~V4를 검사·적용합니다. 테이블을 삭제하거나 ORM 자동 생성으로 바꾸지 않습니다. API 설명: http://127.0.0.1:8080/api/docs / Rule 확인: http://127.0.0.1:8080/api/audit-rules

## 화면 — 터미널 2

가상환경을 활성화하고 루트에서 실행합니다.

```sh
circuitagent-web
```

http://127.0.0.1:8501 에 접속합니다. Backend는 별도 터미널에서 실행된 상태여야 합니다. 다른 Backend 포트라면 `API_BASE_URL` 환경변수를 로컬 주소로 지정합니다.

업로드 파일: `.local/qcv-fixtures/fe.csv`, `.local/qcv-fixtures/fe.qcv`. U16 → Pin 5 → U16_EN → R107/R83을 조회하고 Rule을 실행합니다. 기본 6 Rule의 기대 결과는 PASS1/UNKNOWN5입니다. EN 저항은 둘 다 DNP이며 실제 전압/도통을 정상으로 단정하지 않습니다.

ZIP에는 실제 PoC 입력이 포함됩니다. Git으로 받은 경우 루트에서 준비합니다.

```sh
python scripts/fetch-qcv-fixtures.py
python -m circuitagent.conversion .local/qcv-fixtures
```

## 테스트·패키징

가상환경을 활성화하고 `backend`에서 실행합니다. MySQL 통합 테스트는 Testcontainers가 별도 MySQL을 생성하므로 Docker가 필요합니다. 원본이 없거나 Docker가 없으면 실패하며 가짜 데이터나 SQLite로 대체/자동 skip하지 않습니다.

```sh
python -m pytest -q tests
python -m ruff check circuitagent tests browser_tests
python -m build
```

실제 브라우저 테스트는 MySQL, API(8080), 화면(8501)을 먼저 실행한 뒤 별도 터미널에서 진행합니다.

```sh
python -m playwright install chromium
python -m pytest -q browser_tests
```

순수 파서·그래프·Rule 테스트만 실행할 때는 `python -m pytest -q tests/test_model_parsers.py tests/test_audit.py`로 범위를 명시합니다. 자세한 실제 결과는 [검증 문서](docs/python-validation.md)에 기록합니다.

## 코드를 읽는 순서

| 순서 | 파일 | 역할 |
| --- | --- | --- |
| 1 | [api.py](backend/circuitagent/api.py) | 업로드·조회·Audit HTTP 계약과 입력/로컬 경계 |
| 2 | [domain.py](backend/circuitagent/domain.py) | 불변 Component/Part/Pin/Net/Issue 모델, 원문/정규값 분리 |
| 3 | [bom.py](backend/circuitagent/bom.py), [netlist.py](backend/circuitagent/netlist.py) | CSV/XLSX/XLS·공개 QCV·KiCad 파싱 |
| 4 | [graph.py](backend/circuitagent/graph.py) | Reference 통합·Pin/Net 탐색, Net 자동 병합 금지 |
| 5 | [audit.py](backend/circuitagent/audit.py) | Decimal 비교·조건부 계산·UNKNOWN·Evidence |
| 6 | [database.py](backend/circuitagent/database.py) | 기존 MySQL 스키마, 트랜잭션 저장·복원·버전 이력 |
| 7 | [web.py](backend/circuitagent/web.py) | Python 화면, 서버 응답 표시·검색·업로드·검사 이력 |

[전환·호환성](docs/python-migration.md) · [API](docs/api.md) · [화면](docs/frontend-guide.md) · [Stage 03 계약](docs/stage03-interface.md)

공개 Sparrow QCV 형식에서 검증한 PoC입니다. 삼성 내부 QCV 변형, Stage01 실제 Parser, 기업 SSO/설계별 접근 권한은 미연동입니다. 로컬/격리된 환경에서 사용하며 인증 미구현 API를 공개 인터넷에 배포하지 않습니다. Excel 수식 셀은 계산하지 않고 거절하므로 먼저 값으로 변환해야 합니다. 외부 원본은 출처/체크섬 manifest로 관리하고 재배포 권한을 추정하지 않습니다.

BOM 입력은 CSV를 기본으로 사용합니다. 행 수나 컬럼 순서를 고정하지 않으며, 알려진 컬럼 별칭은 자동 식별합니다. 특이한 헤더는 화면의 선택적 BOM 매핑 JSON 또는 API `bomMapping`으로 지정합니다. 알 수 없는 컬럼은 동적 속성으로 보존합니다. CSV의 부품 속성과 QCV의 연결정보를 Reference로 결합하며, BOM만으로 배선을 추정하지 않습니다. 매핑 예제와 불규칙 행 처리 정책은 [API 문서](docs/api.md)에 있습니다. XLSX/XLS는 호환 파서 및 회귀 테스트로 유지합니다.
