# Stage 02 진행 기록

## STEP 0 — 완료

- 배경: 기존 저장소가 없음을 사용자에게 확인하고 새 프로젝트 생성 권한을 받음.
- 구현한 기능: Java 21 / Spring Boot 3.5.16 / Gradle 8.14.6 프로젝트, JPA/MySQL 설정, DB를 제외한 부트스트랩 테스트.
- 변경 파일: README.md, .gitignore, backend/build.gradle, settings.gradle, Gradle Wrapper, Application, application.yml, ApplicationBootstrapTest.
- 기존 구조/기능: 기존 소스, Entity, API, Service, Frontend, AGENTS.md, 기존 테스트 없음. Stage 02는 backend/src 아래에 추가 예정.
- 테스트 명령: `cd backend && ./gradlew clean test --no-daemon` (환경별 JAVA_HOME, GRADLE_USER_HOME, Java proxy 및 시스템 trustStore 설정).
- 테스트 결과: 새 부트스트랩 테스트 1개 PASS, BUILD SUCCESSFUL. 기존 테스트는 없음.
- 미해결 문제: MySQL 저장 검증과 QCV 샘플은 후속 단계에서 확인.
- 다음 단계: STEP 1 실제 PoC 데이터 확보.

- 실행 환경 문제 및 해결: 기본 캐시 경로 쓰기 제한 → /workspace 캐시 사용; JRE만 설치됨 → 체크섬 검증한 Temurin JDK 21 설치; Java proxy 설정 및 시스템 인증서 지정으로 다운로드 문제 해결.
- GitHub: `gh repo create hyeongdongE/CircuitAgent --private` 실패 (`CreateRepository` 권한 부족). 로컬 저장소와 feat/stage02-design-modeling 브랜치 생성 완료. 원격 저장소/PR 없음.

## STEP 1 — 완료

- 구현한 기능: 실제 공개 BOM/Netlist 4개 원본 바이트 Fixture 확보, 커밋 고정 출처/크기/UTF-8/체크섬 manifest 및 무결성 테스트.
- 변경 파일: backend/src/test/resources/fixtures/{5v,shift}/*, fixtures/sources.json, FixtureIntegrityTest.java.
- 테스트 명령: `./gradlew test --tests com.circuitagent.FixtureIntegrityTest`; Python csv 기반 독립적인 Fixture 수량 확인.
- 테스트 결과: 무결성 테스트 1개 PASS (4개 파일). 5V BOM 12 / Netlist 12 / 매칭 12. Shift BOM 27 / Netlist 31 / 매칭 27 / Netlist-only H1~H4. 5V CSV는 세미콜론, Shift CSV는 쉼표 구분. Netlist는 KiCad S-expression. 전부 UTF-8 (BOM 없음).
- 미해결 문제: QCV 샘플 없음. 이 단계의 수량 확인은 Fixture 사전 검사이며 Java 파서 완성 검증은 후속 단계에서 수행.
- 다음 단계: STEP 2 공통 메모리 도메인 모델.

## STEP 2 — 완료

- 구현한 기능: 요청된 7개 공통 개념과 PinKey/AttributeValue, 불변 메모리 모델, 참조 무결성 및 단일 핀의 복수 Net 연결 방지. 동적 속성/원본 데이터 보존, 누락 값 null/미등록.
- 변경 파일: backend/src/main/java/com/circuitagent/design/model/*.java, DomainModelTest.java, docs/stage02-model.md.
- 테스트 명령: `./gradlew test --tests com.circuitagent.design.DomainModelTest`.
- 테스트 결과: 3개 PASS (사양 공유 및 누락값, 잘못된 연결 거부, 원본값 방어적 복사).
- 미해결 문제: 없음. DB는 아직 구현하지 않음.
- 다음 단계: STEP 3 BOM CSV/XLSX/XLS 파서.

## STEP 3 — 완료

- 구현한 기능: Commons CSV (쉼표/세미콜론/탭, UTF-8 BOM), POI XLSX/XLS (시트·헤더 탐색·셀 형식), 컬럼 별칭 표준화, 그룹 Reference 분리, 명시된 MPN 사양 공유, SI BigDecimal 정규화/원문 보존, 중복·누락·수량·단위 이슈.
- 변경 파일: backend/build.gradle, design/parse/{BomParser,BomResult,EngineeringUnits}.java, BomParserTest.java.
- 테스트 명령: `./gradlew test --tests com.circuitagent.design.BomParserTest`.
- 테스트 결과: 6개 PASS. 실제 5V Reference 12, R1 원문 1k / 정규값 1000 Ω, U1 L7805, 그룹 정상 분리, 이슈 0. 실제 Shift 27, R3~R9 및 LED 사양 공유; 원본 '~' 값의 MISSING_VALUE 1건을 정확히 기록. XLS/XLSX 테스트는 보조 생성 데이터이며 실제 공개 CSV 검증과 구별함.
- 미해결 문제: Reference 범위 축약(R1-R5)과 비 UTF-8 CSV는 임의 해석하지 않음. 명시적 Reference와 UTF-8 입력이 필요함.
- 다음 단계: STEP 4 구조적 KiCad S-expression 파서. QCV 문법 추정 없음.

## STEP 4 — 완료 (KiCad), QCV 명시적 미지원

- 구현한 기능: NetlistParser 인터페이스 분리, 중첩/인용 문자열/escape/EOF/깊이 제한을 처리하는 S-expression 구조 파서, KiCad 컴포넌트·라이브러리 핀·Net 노드·소스 추출. 모호한 복수 Net 핀은 이슈 기록 후 연결 제외. 미연결 라이브러리 핀 유지.
- 변경 파일: design/parse/{SExpression,NetlistParser,NetlistResult,KiCadNetlistParser,QcvNetlistParser}.java, NetlistParserTest.java.
- 테스트 명령: `./gradlew test --tests com.circuitagent.design.NetlistParserTest`.
- 테스트 결과: 5개 PASS. 실제 5V Component 12 / Pin 25 / Net 6 / Connection 25 / GND 연결 Pin 8. 실제 Shift Component 31 및 H1~H4 확인. 관계 무결성 검증 통과. 문자열/중첩/잘못된 문법/모호한 연결은 보조 테스트.
- 미해결 문제: QCV는 실제 샘플/명세가 없어 UnsupportedOperationException으로 명시적 거절; QCV 지원 완료 아님.
- 다음 단계: STEP 5 BOM/Netlist 통합 및 무방향 연결 조회.

## STEP 5 — 완료 (핵심 메모리 PoC)

- 구현한 기능: BOM/Netlist 통합, BOM 값 우선·양측 원본 유지, 단위 동치 비교, Reference 불일치/값 충돌 기록, Map 기반 Pin↔Net 인덱스 조회. 설계에 바인딩된 CircuitGraph의 Component/Pins/Net/ConnectedPins/ComponentsOnNet/Attributes 조회.
- 변경 파일: design/graph/{CircuitAssembler,CircuitGraph}.java, CircuitGraphTest.java.
- 테스트 명령: `./gradlew test --tests com.circuitagent.design.CircuitGraphTest`.
- 테스트 결과: 3개 PASS. 5V 12개 전부 매칭, 이슈 0, R1 1000 Ω, 핀 1→Net 6 / 핀 2→Net 5. Net 6의 C3/J1/R1/U1 및 Net 5의 D5/R1 조회. 두 Net이 합쳐지지 않음을 검증. Shift 27개 전부 매칭, H1~H4의 NETLIST_ONLY_COMPONENT 4건 기록. BOM-only 및 값 충돌은 보조 테스트로 검증.
- 미해결 문제: 없음. 아직 DB/API 구현 전.
- 다음 단계: STEP 6 MySQL 저장 및 모델 복원.

## STEP 6 — 완료 (실제 MySQL 검증)

- 구현한 기능: 7개 회로 Entity/테이블 + import_file, Spring Data JPA 저장소 및 트랜잭션 저장/조회, UUID 관계, 설계 이름/버전 및 Reference/Pin/Net 유일성, 대소문자 구별 UTF-8, 동적 속성/원본 레코드 JSON, 원본 바이트/체크섬/Import 시각, 조회 인덱스, Flyway V1 마이그레이션 및 Hibernate validate.
- 변경 파일: backend/build.gradle, application bootstrap test, design/persistence/*.java, db/migration/V1__circuit_design.sql, AttributeValue.java (JSON 숫자의 정규 형태 유지), MySqlIntegrationSupport.java, MySqlPersistenceTest.java.
- 테스트 명령: `./gradlew test --tests com.circuitagent.design.MySqlPersistenceTest --tests com.circuitagent.ApplicationBootstrapTest`.
- 테스트 결과: 4개 PASS. Docker + Testcontainers의 실제 MySQL 8.0.43로 Flyway/schema 검증, 5V 12/25/6/25 저장·복원 및 원본 파일 바이트 일치, Shift 이슈/MPN 공유 복원, 버전 격리와 늦은 제약 오류 시 전체 롤백, 같은 이름/버전 중복 거절 확인. H2 대체 없음. JSON 컬럼 3개 확인.
- 미해결 문제: 원격 GitHub 생성 권한 부족 지속. DB 테스트는 Docker가 없으면 실패하며 자동 PASS/skip으로 처리하지 않음.
- 다음 단계: STEP 7 업로드 및 회로 조회 REST API.

## STEP 7 — 완료

- 구현한 기능: multipart BOM/Netlist 업로드·파싱·통합·MySQL 저장, 전체 모델/Component/Pin/Net/Issue 조회, Component에 사양과 연결 함께 반환, 요청 검증, 파일 한도, 오류 400/404/409/413/422 및 구조 파싱 실패 ImportIssue 응답.
- 변경 파일: design/api/*.java, DesignApiTest.java, docs/stage02-api.md.
- 테스트 명령: `./gradlew test --tests com.circuitagent.design.DesignApiTest`.
- 테스트 결과: 5개 PASS. MockMvc + 실제 서비스 + 실제 MySQL로 5V 업로드 201 (12/25/6), R1 1000 Ω 및 Net 6/5 조회, U1 L7805, Net/Issue 조회, 실제 Shift 추가 H1~H4 이슈 조회, 중복 버전 409, 파싱 실패 400/ImportIssue, 미지원 QCV 422, 잘못된 요청 400/없는 데이터 404 검증. Mock 저장소로 대체하지 않음.
- 미해결 문제: React 화면은 필수 범위 밖으로 생성하지 않음.
- 다음 단계: STEP 8 QCV 적용 준비와 실제 샘플 여부 명시.
