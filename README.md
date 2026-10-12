# CircuitAgent

회로 검증 자동화 프로젝트의 Stage 02와 최소 Stage 03 통합 PoC입니다.
공개 KiCad 회로와 Sparrow 3개 보드의 실제 PADS QCV + BOM XLSX로 검증합니다.

- Java 21 **JDK**, Spring Boot 3.5.16, Gradle 8.14.6, MySQL 8
- BOM: UTF-8 CSV, XLSX, XLS / 단위 정규화, 원본 및 ImportIssue 보존
- Netlist: KiCad S-expression 및 공개 PADS QCV / Component·Pin·Net 그래프 및 조회
- MySQL: JPA, Flyway, JSON 속성, 원본 파일·체크섬, 트랜잭션 저장·복원
- REST: 파일 업로드 및 설계/부품/핀/Net/Issue 조회
- QCV: **공개 Sparrow PADS NET/PIN 문법 지원**; 삼성 내부 변형은 미검증
- Stage 03: 정형 Rule adapter, 결정론적 범위/연결/FB 계산, 다섯 판정 상태·근거, MySQL Audit 이력
- React: 실제 업로드/부품·Net 탐색/Rule 실행/저장된 이력 조회
- Stage 01 실제 Parser·전체 AI 검증 엔진·Stage 04·기업 SSO는 이번 PoC 범위 밖입니다.

## 구조

```text
backend/
  gradle/                         Gradle Wrapper
  src/main/java/com/circuitagent/
    design/model/                 DB 독립 도메인 모델
    design/parse/                 BOM 및 Netlist 파서
    design/graph/                 통합 및 연결 조회
    design/persistence/           JPA 저장 및 복원
    design/api/                   REST 업로드 및 조회
  src/main/resources/db/migration/ MySQL Flyway schema
  src/test/resources/fixtures/    커밋/체크섬 고정 실제 공개 자료
  src/test/java/                 단위 및 실제 MySQL/API 통합 테스트
docs/                            진행 기록, 모델/API/QCV 설명
```

## 검증

JDK 21과 실행 중인 Docker가 필요합니다. MySQL 통합 테스트는 Testcontainers로
MySQL 8.0.43을 실행하며 Docker가 없으면 실패합니다. 실제 DB 테스트를 H2로
대체하거나 자동 건너뛰지 않습니다.

```sh
python3 scripts/fetch-qcv-fixtures.py
cd backend
./gradlew convertQcvBoms
./gradlew clean test bootJar
```

Docker 없이 파서/모델/그래프만 검증하려면 범위를 명시합니다.

```sh
./gradlew test --tests '*FixtureIntegrityTest' --tests '*DomainModelTest' \
  --tests '*BomParserTest' --tests '*NetlistParserTest' --tests '*CircuitGraphTest'
```

JRE만 설치되어 있다면 JDK 21을 설치하고 `JAVA_HOME`을 지정하세요.
관리형 환경에서는 허용된 캐시 경로에 `GRADLE_USER_HOME`을 설정하고,
해당 환경의 Java proxy 및 CA trust 설정을 적용해야 합니다.

## 로컬 실행

기존 MySQL 8의 `circuitagent` DB를 사용하거나 다음과 같이 MySQL을 준비합니다.
먼저 로컬용 `MYSQL_ROOT_PASSWORD`와 `DB_PASSWORD` 환경 변수를 설정하세요.

```sh
docker run --name circuitagent-mysql \
  -e MYSQL_ROOT_PASSWORD -e MYSQL_DATABASE=circuitagent \
  -e MYSQL_USER=circuitagent -e MYSQL_PASSWORD="$DB_PASSWORD" \
  -p 127.0.0.1:3306:3306 -d mysql:8.0.43
```

DB가 준비된 후 실행합니다. Flyway가 테이블을 생성하고 Hibernate가 schema를 검증합니다.

```sh
export DB_URL='jdbc:mysql://localhost:3306/circuitagent?useUnicode=true&characterEncoding=UTF-8'
export DB_USERNAME=circuitagent
cd backend
./gradlew bootRun
```

MySQL 계정에는 `circuitagent` DB에 대한 테이블 생성/조회/수정 권한이 필요합니다.
다른 DB에 원본 데이터를 덮어쓰는 마이그레이션은 없습니다.

[REST 사용법](docs/stage02-api.md) · [단계별 검증](docs/stage02-progress.md) ·
[공통 모델](docs/stage02-model.md) · [QCV 추가 자료](docs/stage02-qcv.md)

## Stage 02 리뷰 및 Stage 03 계약

[PR #1 리뷰 결과](docs/stage02-review.md) · [Stage 03 조회 계약](docs/stage03-interface.md)

현재는 신뢰된 PoC 환경용입니다. 인증·설계별 사용자 접근 통제가 없으므로
사내 SSO/권한 및 원문 열람 정책 없이 실제 삼성 회로 데이터를 외부에 배포하지 마세요.

[공개 QCV PoC 검증·출처·재현 방법](docs/qcv-poc-validation.md). 외부 원본/파생 XLSX는 라이선스가 명확하지 않아 커밋하지 않습니다. 전체 테스트 전 다운로드와 변환이 필요하며, 누락 시 테스트가 실패합니다.

## Stage 03 + React 통합 PoC

[연동 계약](docs/stage03-integration.md) · [실제 Sparrow 검증](docs/stage03-poc-validation.md) · [Audit API](docs/audit-api.md) · [React 실행](docs/frontend-guide.md) · [단계별 기록](docs/integration-progress.md)

Backend103개(기존80+신규23), React unit6개, 실제 Chromium E2E2개를 실행했습니다. 실제 FE6 Rule은 PASS1/UNKNOWN5이며 PASS는 C5 nominal BOM 확인에 한정합니다. EN 저항의 NoMount, IC 신원·공차·실제 동작 조건 누락을 정상 동작으로 판정하지 않습니다.

```sh
cd frontend
npm ci
npm run dev
```

로컬 backend/MySQL이 먼저 실행되어야 합니다. 기본 API bind127.0.0.1이며 remote/production/enterprise 미설정 요청은 거절합니다. 로컬 경계는 사용자 인증이나 설계 소유권이 아니며 공개 proxy/인터넷에 배포하지 않습니다.

## 코드를 읽는 순서

각 클래스의 한국어 주석은 역할을 설명하고, 핵심 분기의 주석은 데이터 처리 이유와 한계를 설명합니다.

| 순서 | 시작 파일 | 확인할 내용 |
| --- | --- | --- |
| 1 | [DesignImportService](backend/src/main/java/com/circuitagent/design/api/DesignImportService.java) | 파일 형식 선택 → 파싱 → 통합 → 저장 |
| 2 | [BomParser](backend/src/main/java/com/circuitagent/design/parse/BomParser.java), [QcvNetlistParser](backend/src/main/java/com/circuitagent/design/parse/QcvNetlistParser.java) | 원본 행 보존, 단위·실장 상태, NET/PIN 구분 |
| 3 | [CircuitAssembler](backend/src/main/java/com/circuitagent/design/graph/CircuitAssembler.java), [CircuitGraph](backend/src/main/java/com/circuitagent/design/graph/CircuitGraph.java) | Reference 매칭, Pin/Net 인덱스, 제한적 탐색 |
| 4 | [CircuitDesignStore](backend/src/main/java/com/circuitagent/design/persistence/CircuitDesignStore.java) | FK 순서로 저장하고 공통 모델로 복원 |
| 5 | [AuditService](backend/src/main/java/com/circuitagent/audit/poc/AuditService.java), [DeterministicRuleChecker](backend/src/main/java/com/circuitagent/audit/poc/DeterministicRuleChecker.java) | 한 Snapshot으로 평가, 누락·DNP·UNKNOWN, 계산 근거 |
| 6 | [App.tsx](frontend/src/App.tsx) | 업로드 → 회로 탐색 → Audit → 이력 표시 |

빌드 결과와 의존성은 재생성되는 파일입니다. Backend는 Gradle 명령으로, Frontend는 `npm ci` 후 테스트/빌드 명령으로 다시 생성합니다. 실제 QCV 원본과 변환 XLSX는 `.local/qcv-fixtures`에서 테스트 입력으로 사용합니다.
