# 직접 설치한 MySQL + Python

Docker/Testcontainers 없이 MySQL Community Server 8.0과 Python3.12를 사용한다. MySQL Workbench는 관리 클라이언트이며 Server도 설치해야 한다. 프로젝트 DB 스키마/API 계약은 변경하지 않는다.

## 1. 서버 준비

Windows: MySQL 공식 Community 다운로드에서 Server를 설치하고 Configurator로 포트3306/TCP를 설정한다. 설치 시 지정한 관리자 비밀번호를 사용한다. 서비스 이름은 설치 환경마다 다르므로 PowerShell `Get-Service *mysql*` 또는 Windows 서비스 앱에서 상태를 확인한다. 서비스 시작은 관리자 권한이 필요할 수 있다. macOS/Linux는 설치한 패키지의 MySQL 서비스 시작 방법을 사용한다. MariaDB는 이번 검증 대상으로 대체하지 않는다.

Workbench에서 관리자 접속 또는 `mysql -h 127.0.0.1 -P 3306 -u root -p`로 접속하고 아래 SQL을 실행한다. 비밀번호는 로컬 예시이다. 기존 계정에 `CREATE USER IF NOT EXISTS`를 실행해도 비밀번호는 변경되지 않으므로 기존 비밀번호를 환경변수에 사용한다. MySQL host 매칭 정책에 따라 localhost 계정을 사용하는 경우 실제 TCP 접속에 허용된 계정인지 확인한다.

```sql
CREATE DATABASE IF NOT EXISTS circuitagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'circuitagent'@'127.0.0.1' IDENTIFIED BY 'poc-db-password';
GRANT ALL PRIVILEGES ON circuitagent.* TO 'circuitagent'@'127.0.0.1';

CREATE USER IF NOT EXISTS 'circuitagent_test'@'127.0.0.1' IDENTIFIED BY 'poc-test-password';
GRANT ALL PRIVILEGES ON `circuitagent_test\_%`.* TO 'circuitagent_test'@'127.0.0.1';
```

테스트 권한은 `circuitagent_test_` 접두사 DB에 한정한다. MySQL8.0의 database grant 패턴에서 `\_`는 접두사의 실제 밑줄이며 `%`만 와일드카드다. 테스트 계정에 앱 DB 또는 모든 DB의 권한을 부여하지 않는다.

## 2. 설치 및 실행

프로젝트 루트 PowerShell:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --require-hashes -r backend/requirements.lock
python -m pip install --no-deps -e backend

$env:DB_HOST="127.0.0.1"
$env:DB_PORT="3306"
$env:DB_NAME="circuitagent"
$env:DB_USERNAME="circuitagent"
$env:DB_PASSWORD="poc-db-password"
$env:CIRCUITAGENT_MODE="local-poc"
cd backend
python -m uvicorn circuitagent.api:app --host 127.0.0.1 --port 8080 --no-proxy-headers
```

새 터미널에서 프로젝트 루트의 가상환경을 활성화하고 `circuitagent-web` 실행. 화면8501/API8080이며 localhost로만 실행한다. .venv는 Python 라이브러리 분리 기능이며 Docker/가상 머신이 아니다. 기존 DATABASE_URL이 설정되어 있으면 DB_*보다 우선하므로 다른 DB로 연결되지 않도록 확인한다.

## 3. 테스트

Git 다운로드라면 루트에서 실제 원본과 Excel 호환성 회귀 입력을 준비한다:

```sh
python scripts/fetch-qcv-fixtures.py
python -m circuitagent.conversion .local/qcv-fixtures
```

별도 터미널의 PowerShell:

```powershell
$env:TEST_DB_HOST="127.0.0.1"
$env:TEST_DB_PORT="3306"
$env:TEST_DB_USERNAME="circuitagent_test"
$env:TEST_DB_PASSWORD="poc-test-password"
cd backend
python -m pytest -q tests
python -m ruff check circuitagent tests browser_tests
python -m build
```

macOS/Linux의 활성화는 `source .venv/bin/activate`이며 테스트 설정은 다음과 같다:

```sh
export TEST_DB_HOST=127.0.0.1 TEST_DB_PORT=3306
export TEST_DB_USERNAME=circuitagent_test TEST_DB_PASSWORD=poc-test-password
```

DB 통합 테스트는 임의 UUID로 새 스키마를 생성한다. CREATE 성공 후에만 소유권을 표시하며 정상/실패 종료에서 해당 스키마만 DROP한다. 기존 DB를 초기화하거나 DB_NAME/DATABASE_URL을 테스트 설정으로 사용하지 않는다. 프로세스 강제 종료 시 테스트 스키마가 남을 수 있으며, 확인 후 관리자 수동 정리가 필요하다. 테스트 계정 누락/접속 실패/권한 부족은 실패로 보고하며 PASS/skip으로 처리하지 않는다.

파서·그래프·Rule만 검사하려면 MySQL 없이 명시적으로 실행한다:

```sh
python -m pytest -q tests/test_model_parsers.py tests/test_dynamic_csv.py tests/test_audit.py tests/test_web.py tests/test_mysql_configuration.py
```

실제 브라우저 테스트는 MySQL/API/화면을 실행한 상태에서 `python -m playwright install chromium`, `python -m pytest -q browser_tests`를 실행한다. UI의 실제 CSV 업로드, U16 Pin5/U16_EN 조회, Audit PASS1/UNKNOWN5와 이력 복원을 확인한다.

## 오류 확인

- Connection refused: MySQL 서비스와 DB_PORT를 확인한다.
- Access denied: 계정 host/비밀번호/권한을 Workbench에서 확인한다.
- CREATE DATABASE denied: 앱 계정이 아닌 전용 TEST_DB_USERNAME과 접두사 DB 권한을 확인한다.
- 같은 이름/버전 업로드409: 다른 이름/버전으로 새 설계를 생성한다.

## Windows 해시 설치 오류

초기 lock은 Linux 환경만 해결해 build/pytest의 Windows 전용 Colorama와 pandas의 TZData를 누락했다. `--require-hashes` 모드에서는 이러한 간접 의존성도 버전·해시가 필요하므로 오류가 발생한다. 수정된 requirements.lock으로 교체한 후 기존 설치 명령을 다시 실행한다. 가상환경이나 MySQL 데이터를 삭제할 필요는 없다. hash 검사를 끄거나 Colorama만 미리 설치하는 방식으로 우회하지 않는다.

유지보수 시 Windows/Linux/macOS 의존성을 모두 포함하도록 다음 명령을 사용한다:

```sh
uv pip compile backend/pyproject.toml --extra ui --extra test --universal --generate-hashes --output-file backend/requirements.lock
```

이번 수정은 기존 패키지 버전을 유지하고 Colorama0.4.6/TZData2026.5의 조건부 버전·해시를 추가했다. Linux 환경에서 Windows x64/Python3.12 대상으로 wheel/hash 설치 계획을 검증했으며, 실제 Windows 실행 테스트로 간주하지 않는다.
