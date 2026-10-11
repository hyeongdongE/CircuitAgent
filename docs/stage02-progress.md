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
