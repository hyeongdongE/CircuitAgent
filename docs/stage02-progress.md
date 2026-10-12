> 아래 단계별 수치와 미지원 항목은 각 단계 완료 당시의 기록입니다. 현재 통합 상태는 [integration-progress.md](integration-progress.md), 최신 집계는 [integration-test-results.json](integration-test-results.json)에 있습니다. 과거 집계·성능·PR 본문 복사본은 중복 산출물 정리 과정에서 삭제했습니다.

# Stage 02 진행 기록

이 문서는 초기 구현/이전 리뷰를 보존한 누적 기록입니다. 현재 상태는 아래 **공개 PADS QCV PoC** STEP 0~12와 [최종 검증](qcv-poc-validation.md)을 참고하세요. 이전의 QCV 미지원 설명은 해당 시점의 기록입니다.

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

## STEP 8 — 준비 검토 완료, QCV Parser 미구현

- 구현한 기능: QCV 인터페이스 경계와 실제 미지원 응답을 검토; 계층/핀 이름·번호/전원/미연결 핀/Source 적용 가능 부분 및 추가 입력 요구사항 문서화.
- 변경 파일: docs/stage02-qcv.md, docs/stage02-progress.md. QCV 문법 코드는 작성하지 않음.
- 테스트 명령: `rg --files --hidden -g '*.qcv'` (저장소/첨부/작업 폴더); STEP 4/7 미지원 거절 테스트 결과 확인.
- 테스트 결과: QCV 원본 샘플 0개. QCV 구문/성공 파싱 테스트는 미실행. 미지원 경계 검증만 PASS.
- 미해결 문제: 실제 QCV와 대응 BOM, 생성 도구/버전·인코딩·명세·정답 연결 정보 필요. 계층별 중복 Reference 규칙은 실제 샘플 기반 추가 설계 필요.
- 다음 단계: STEP 9 지원되는 KiCad PoC 전체 clean test 및 GitHub 작업 결과 기록. QCV 구현은 자료 확보 시 별도 진행.

## STEP 9 — 로컬/Cloud 검증 완료, 원격 GitHub 작업 차단

- 구현한 기능: 지원 범위 전체 clean test, 실행 Boot JAR 빌드, 테스트 XML 집계, 단계별 커밋 및 PR 설명 준비. 기본 브랜치에는 부트스트랩만 생성했고 Stage 02는 feat/stage02-design-modeling에서 구현함. 원격 기본 브랜치 병합 없음.
- 변경 파일: docs/stage02-progress.md 및 당시 테스트 집계·PR 본문(현재 최신 통합 집계로 정리). 빌드 결과 backend/build/libs/circuitagent-0.1.0-SNAPSHOT.jar (Git 제외).
- 테스트 명령: `cd backend && ./gradlew clean test bootJar --no-daemon`; `git diff --check`.
- 테스트 결과: BUILD SUCCESSFUL. 27 tests / 0 failures / 0 errors / 0 skipped. 기본 앱 1, Fixture 1, Domain 3, BOM 6, Netlist 5, Graph 3, 실제 MySQL 3, 실제 MySQL 기반 API 5. 5V BOM/Netlist/매칭 모두 12, R1 1000 Ω, U1 L7805, Pin 25/Net 6/연결 25. Shift BOM 27/Netlist 31/매칭 27 및 H1~H4 이슈 확인. 원본 Fixture 무결성 유지. 실행 JAR 빌드 성공, diff check 통과.
- 미실행 테스트: 실제 QCV 구문/성공 파싱 (샘플/명세 없음). QCV는 미지원 거절만 검증함. 외부 배포/React UI 테스트는 범위 밖.
- GitHub 결과: `gh repo create hyeongdongE/CircuitAgent --private` → GraphQL CreateRepository 권한 부족으로 실패. 계정 조회는 가능하지만 저장소 생성은 불가. 원격 저장소/PR 없음, 로컬 작업 브랜치 `feat/stage02-design-modeling`. GitHub 계정 권한을 실행 환경의 파일·네트워크 권한으로 변경할 수 없음.
- 다음 단계: GitHub에 빈 비공개 저장소 생성 또는 연결 계정의 생성 권한 확보 → main 부트스트랩과 작업 브랜치 push → 준비된 설명으로 PR 생성. 실제 QCV/대응 BOM 및 명세 확보 후 QCV 구현·검증.

## 전달 파일

- 저장소 경로: `/workspace/CircuitAgent`.
- 소스 ZIP: `/workspace/CircuitAgent-source.zip` (빌드 캐시/개인 환경 파일 제외).
- Git 이력 bundle: `/workspace/CircuitAgent.bundle` (main 및 작업 브랜치 포함).
- QCV 지원 완료 또는 원격 PR 생성 완료를 주장하지 않음.

## GitHub 연결 후속 확인

- 사용자가 비공개 저장소 `https://github.com/hyeongdongE/CircuitAgent.git`를 생성하고 제공함.
- 해당 URL을 로컬 `origin`으로 연결함. 현재 GitHub 계정 조회는 hyeongdongE로 성공.
- `gh api repos/hyeongdongE/CircuitAgent`는 404, `git ls-remote origin`은 403 (`Write access to repository not granted`), GitHub Connector의 설치된 저장소 검색은 빈 결과.
- 원격 브랜치/파일을 읽거나 push할 수 없어 기존 원격 내용을 확인하지 못함. 원격 수정/병합/PR 생성은 수행하지 않음.
- 다음 단계: GitHub의 ChatGPT/Codex 앱 저장소 접근 설정에 CircuitAgent를 추가하고 현재 연결 권한을 갱신 → 원격 초기 상태 확인 → 기존 내용 보존하면서 브랜치 push 및 PR 생성.
- 이 후속 변경은 문서/원격 URL 설정만이며 구현 코드는 변경하지 않음. 이전 전체 테스트 27개 PASS 결과 유지, 추가 테스트 미실행.

## GitHub 접근 해결 및 업로드

- 사용자가 ChatGPT Codex Connector GitHub App을 설치하고 저장소 접근을 허용한 뒤 API 읽기/쓰기 권한이 확인됨.
- 원격 `hyeongdongE/CircuitAgent`는 비공개이며 초기 브랜치/커밋이 없는 빈 저장소로 확인함.
- gh credential helper로 Git 인증을 연결함. 기본 부트스트랩 main 및 Stage 02 작업 브랜치를 업로드하고 PR을 생성할 예정.
- 구현 코드는 변경하지 않음. 마지막 clean test/bootJar: 27개 PASS, 실패/오류/skip 0. QCV는 여전히 실제 자료가 없어 미지원.

## STEP 9 — GitHub PR 생성 완료

- 부트스트랩 main 및 단계별 구현 이력을 포함한 feat/stage02-design-modeling 브랜치 push 성공.
- PR: https://github.com/hyeongdongE/CircuitAgent/pull/1 (base main, head feat/stage02-design-modeling).
- 저장소 비공개 유지, 기본 브랜치 직접 병합 없음.
- 후속 변경은 문서뿐이며 구현 코드의 마지막 전체 검증은 clean test bootJar 27개 PASS. 새 테스트를 실행했다고 주장하지 않음.
- 다음 단계: PR 검토 후 사용자/팀이 병합 여부 결정. QCV 구현은 실제 샘플과 명세 확보 후 진행.

## PR #1 리뷰 및 Stage 03 연동 준비 (2026-10-11)

원래 STEP 0~9 기록은 보존한다. 새 리뷰 STEP 1~8의 확인/문제/수정/테스트/다음 단계는
[순차 리뷰 기록](stage02-review.md)에 남겼다. 검토 기준 PR HEAD `85b5b9d410fcb1df11c77ce8806dfe8eacd40586`.
기존 27개 재실행 PASS; 개선 후 최종 `clean test bootJar` 51 PASS / failures·errors·skipped 0.
실제 MySQL V1→V2, DNP/미참조 사양 복원, 격리/FK/롤백/API 및 실제 공개 Fixture 검증.
DNP/사양 재사용/중복 Net/입력 제한/Stage 03 snapshot·pin 상태·탐색을 보완했다.
[Stage 03 계약](stage03-interface.md) 및 [최종 집계](integration-test-results.json) 참고.
미해결: 실제 QCV/정답 회로·사내 SSO/설계 권한·외부 Stage 03 DTO/대규모 성능 검증.
다음: 리뷰 STEP 9 같은 feature 브랜치/PR #1에 반영. main 병합 안 함.

리뷰 STEP 9: 구현 `99c334c`, 테스트 `f7d376e`를 기존 feature 브랜치에 push했고
PR #1 설명/제목을 최종 결과로 갱신했다(REST 확인 OPEN/main/기존 branch).
리뷰 문서와 집계도 같은 브랜치의 문서 commit으로 반영한다. 기본 브랜치 병합 없음.

## 공개 PADS QCV 실무형 PoC (2026-10-11)

### STEP 0 — 완료
- 확인: PR #1 OPEN, base main, 현재 HEAD 10a3636e3988a9ba8bae4bcc1e3c2cc040348ff2; local/remote 동일, 변경 없음. AGENTS/React package.json 없음. Java 21/Boot 3.5.16, 기존 NetlistParser/QCV stub/Common IR, BOM/graph/JPA V1·V2/API/Stage 03 계약 확인.
- 검증: `cd backend && ./gradlew clean test bootJar --no-daemon`: 51 PASS, failure/error/skipped 0, 실제 MySQL 8.0.43, JAR 성공.
- 확장: 기존 QcvNetlistParser 구현, 핀의 종단/출처 정보를 additive 확장. 기존 Java 서비스 유지.
- 다음: STEP 1 라이선스·고정 원본 확보/POI Excel 변환.

### STEP 1 — 완료
- 라이선스: upstream license=null, LICENSE/COPYING 없음, root README 404. 원본 및 파생 XLSX는 `.local/`에만 보관; 재배포하지 않음. 참고 외부 코드 복제 없음.
- 출처/무결성: upstream commit 7a9de5b64c6b22502502700123ea00853dd38342, 6개 파일 path/bytes/SHA-256은 scripts/qcv-fixtures.json. `python3 scripts/fetch-qcv-fixtures.py` 재다운로드/검증 성공.
- 변환: Java CsvToXlsx(Commons CSV/POI), 문자열·공백·빈 셀 보존, XLSX 재열기 후 모든 셀 검증. 세 CSV 모두 UTF-8/세미콜론/8컬럼, FE 69/NTOP 55/FE-NTOP 21 데이터 행.
- 테스트: `./gradlew convertQcvBoms test --tests '*QcvConversionTest'`: 2 PASS, XLSX 70/56/22행(header 포함) 셀별 일치.
- 다음: STEP 2 원문 구조 검증 및 문법 확정.

### STEP 2 — 완료
- `QcvSourceStructureTest`: 1 PASS. 원문은 UTF-8, 한 행에 NET/PIN, 작은따옴표 label, 공백 구분 terminal, # section comment. COMP 선언 없음. 세 원본에 반복 Net/중복 연결/연결-PIN 중복 없음.
- NET는 전기 연결, PIN의 (by TERM)은 명시적 종단, 일반 PIN은 별도 미연결 선언. PIN label을 Net으로 생성하지 않음.
- FE: NET component 445, PIN-only PCB1~PCB8을 보존하면 전체 453; NET 연결 1500, 별도 PIN 143=TERM135+plain8. NTop 전체304/NET292/BOM296, FE-NTop 전체169/NET161/BOM161. 참고 수치와 의미 차이 명시.
- 지원: 관측된 NET/PIN/#/빈 줄, 정확한 quoted label, validated terminal suffix와 마지막 구분자; Ref 특수문자/계층 경로 원문 유지. UTF-8 및 행번호.
- 미지원: COMP/다른 exporter 문법, 줄바꿈된 record, label quote escape, hyphen 포함 pin number의 모호한 분해. 알 수 없는 문법은 거절. 삼성 호환 보장 아님.
- 다음: STEP 3 Java adapter 및 pin 종단/출처 확장.

### STEP 3 — 완료
- 독립 Java QcvNetlistParser 구현: NET/PIN 분리, strict UTF-8, 행별 검증, 중복 Net 병합/Pin dedup/모호 연결 제외, 행·token budget, 원문 label/terminal/행 위치 보존. Net ID는 label SHA-256, Net 이름을 전압으로 해석하지 않음.
- ComponentPin에 disposition/sourceMetadata additive 확장, 기존 3인자 생성자 유지. TERM은 ImportIssue 오류가 아님.
- `./gradlew test --tests '*QcvParserTest' --tests '*NetlistParserTest'`: 9 PASS. FE 335 nets/1500 connections/143 separate PIN(135 TERM), 실제 U16_EN/U16_FB/VDD/GND node 목록 비교. KiCad 유지, 잘못된 문법·인코딩·중복 테스트 PASS.
- 기존 empty QCV stub 테스트는 구현 후 빈 파일 거절 테스트로 갱신(테스트 삭제 아님).
- 다음: STEP 4 XLSX BOM 통합.

### STEP 4 — 완료
- RefDes/QtyOnBoard/NoMount 양식 지원. NoMount NM=DNP/빈 flag=FITTED; 기존 DNP 빈 셀=UNSPECIFIED 계약 유지. Rev/Voltage/Part Name 원문·동적 속성 보존. PartNumber는 해당 카탈로그 양식에서 내부 Part Number 속성/재사용 키, MPN은 임의 생성하지 않음.
- 실제 FE BOM445/NoMount51 모두 매칭, PIN-only PCB8개 별도 보존, 통합 전체453. R107=140000Ω/R83=56000Ω, U16 내부PN/PartName/빈 Value 원문 그대로.
- `test --tests '*QcvIntegrationTest' --tests '*BomParserTest' --tests '*ReviewRegressionTest'`: 25 PASS. 기존 그룹/단위/0/누락/DNP 및 MPN 재사용 유지.
- 다음: STEP 5 실제 QCV topology/원문 근거 및 명시적 NoMount 탐색 모드.

### STEP 5 — 완료
- 실제 U16-5→U16_EN→R107-2/R83-2→다른 pin의 VDD/GND 조회 및 BOM 사양 확인. 335 Net 유지; 부품 경유 edge는 Net 병합/도통 주장 아님.
- 기존 explore 기본은 DNP 제외 유지. INCLUDE_DNP_SCHEMATIC을 명시하면 미실장 부품의 원문 논리 topology 경유를 조회. R107/R83가 NM이라는 사실을 감추지 않음. Edge에 원본 Net 행/BOM 행 sourcePath 추가, 상세 pin sourceMetadata 조회 가능.
- TERMINATED/UNCONNECTED/UNKNOWN을 구분; PIN label로 가짜 Net/핀 기능을 만들지 않음.
- `test --tests '*QcvGraphTest' --tests '*CircuitGraphTest' --tests '*ReviewRegressionTest'`: 22 PASS.
- 다음: STEP 6 실제 MySQL roundtrip/source/disposition V3 migration.

### STEP 6 — 완료
- V3 additive migration: PinDisposition 및 QCV sourceMetadata JSON. V1/V2 변경 없음, 기존 핀은 UNSPECIFIED/빈 metadata로 보존. 새 모델의 전체 Pin/연결/NoMount/원문/이슈 보존.
- `test --tests '*QcvPersistenceTest' --tests '*MySqlPersistenceTest' --tests '*ReviewMySqlTest' --tests '*MigrationUpgradeTest'`: 실제 MySQL 8.0.43의 9 tests PASS. FE 전체 모델 recursive comparison, XLSX/QCV 원본 바이트, 453 components/335 nets/1643 inventory/1500 connections, TERM135·출처 roundtrip. 큰 import late constraint 실패 롤백 및 재시도/버전 격리 PASS.
- 기존 schema JSON 컬럼 기대값을 V3의 4개로 갱신. FK/격리/기존 KiCad roundtrip/데이터가 있는 V1 upgrade 계속 PASS.
- 다음: STEP 7 실제 XLSX+QCV 업로드 및 기존 REST 계약 검증.

### STEP 7 — 완료
- 기존 multipart/import/component/pin/net/issues route 재사용; XLSX+QCV 자동 adapter 선택 및 실제 MySQL 저장. PinView disposition/sourceMetadata, ImportResponse connectionCount/terminatedPinCount additive.
- `test --tests '*QcvApiTest' --tests '*DesignApiTest'`: 10 PASS. 실제 파일 bytes 업로드 201(453/1643/335/1500/TERM135), U16 PN/빈 Value, U16_EN/R107/R83, NM, TERM135·원문 행 조회, 재업로드409, invalid QCV400, 기존 KiCad 요청 유지.
- 이전 qcv 미지원422 테스트는 실제 구현 이후 invalid syntax400로 갱신(기존51 test 유지). React 앱 없음: 새 frontend 생성/공개 배포 없이 API 검증/예시 제공.
- 다음: STEP 8 다른 두 실제 보드 회귀/roundtrip.

### STEP 8 — 완료
- 같은 CSV→XLSX 기능으로 FE/NTop/FE-NTop 모든 셀 재검증, 공개 원본 Net 일부의 전체 Pin 목록 직접 비교, NoMount/기구 부품 및 전체 Source/연결 roundtrip.
- `test --tests '*QcvBoardRegressionTest' --tests '*NetlistParserTest'`: 8 PASS(보드별 parameterized 3 + KiCad 5), 실제 MySQL. BOM445/296/161, Net335/233/193, 연결1500/972/794, NM51/67/37.
- 전체 QCV refs는453/304/169; 세 보드 모두 BOM ref 전체 매칭 및 PIN-only PCB8 보존. NTop NET에 없는 M1~M4도 PIN에 있어 유지.
- 다음: STEP 9 발견된 embedded SI/소수 comma 단위 보완 및 오류/성능 검증.

### STEP 9 — 초기 실패 및 수정
- 원문에서 발견한 2n2/3V6/2,5 표기 추가 정규화; catalogue Voltage를 임의 Rating으로 해석하지 않고 Voltage 속성으로 유지.
- 초기 오류/성능 집중 테스트 34개 중33 PASS/1 FAIL: normalized attribute.raw가 원본 Value 끝 공백을 trim함. BOM rawSources는 보존돼 있었으나 attribute.raw도 원본 셀 문자열로 수정.
- 원본6파일 checksum, 중복/수량/빈 MPN·Value/NoMount/Zero, 특수 Net/계층 Ref, 충돌 TERM, BOM/QCV mismatches, PIN-only, 5000개 synthetic부품·10000연결 및 탐색 budget 테스트 추가. 생성 stress 입력은 보조 데이터이며 실제 공개 검증과 구분.
- 첫 3회 성능 실측: FE DB 저장13.75~14.87초가 병목. no-SLA, 측정환경/각단계 전체 값 local performance.json. 다음 단계에서 기존 Hibernate batching 검토.
- 재검증: `test --tests '*QcvRobustnessTest' --tests '*BomParserTest' --tests '*ReviewRegressionTest'`: 33 PASS/failure0/skip0(XML 확인). 원본 normalized.raw 끝 공백 보존 수정 검증.
- 다음: STEP 10 기존 라이브러리/배치 저장 및 코드 품질 확인.

### STEP 10 — 완료
- 기존 POI/Commons CSV/JPA/Flyway/JUnit/Testcontainers 재사용, Map/Set 탐색 유지. 새 의존성·JGraphT·Python service 없음.
- 고정 upstream parse_qcv_review.py는 검토만 수행: TERM-only set/간단 CSV split/제한 Ref regex/ERC 확인. 라이선스 불명확해 코드 복제/실행 service 없이 독립 Java adapter 유지.
- CSV 변환 budget 계산 단순화. 실제 저장 병목에 Hibernate batch50/order_inserts, MySQL rewriteBatchedStatements 설정 적용; schema/트랜잭션/API 계약 변경 없음.
- `test --tests '*QcvPerformanceTest' --tests '*QcvPersistenceTest' --tests '*QcvApiTest' --tests '*MigrationUpgradeTest' --tests '*QcvConversionTest'`: 8 PASS/실패0/skip0, BUILD SUCCESSFUL 4m38s. 실제 FK/rollback/전체복원/API/V1upgrade/변환/3회 성능 검증.
- 다음: STEP 11 `clean test bootJar` 전체 회귀.

### STEP 11 — 완료
- `cd backend && ./gradlew clean test bootJar --no-daemon`: BUILD SUCCESSFUL 3m59s, 8 tasks executed.
- JUnit XML 21 suites/80 tests, 기존51 + 신규29, PASS80/failure0/error0/skipped0. actual MySQL8.0.43. 기존51은 삭제 없이 QCV stub/JSON migration의 변경된 기대값 3곳 갱신.
- 세 실제 보드의 XLSX/BOM/QCV/graph/MySQL 전체 복원/API와 기존 KiCad, FK·롤백·설계 격리·V1upgrade/잘못된 입력/ImportIssue 검증 포함.
- 실제 FE issues80=MISSING_VALUE72+NETLIST_ONLY_COMPONENT8, NTop93=85+8, FE-NTop129=121+8. INVALID_UNIT0. 이슈는 원문 빈 값/PCB 추가 부품을 보존한 경고이며 단위 오류 수정 후 수치.
- 최종 3회 중앙값(ms): BOM64.890/QCV18.740/통합4.577/Graph1.887/MySQL1560.244/API379.926. 위 수치는 당시 측정치이며, 현재 통합 환경의 sample/방법은 docs/audit-performance.json에 기록한다. 컨테이너 초기화/파일 read 제외; API는 MockMvc로 전체DB복원/JSON까지, network latency 제외. SLA 아님.
- 미실행: 실제 삼성 QCV(미제공), React/browser UI(프로젝트 없음), 인증/운영 배포. 공개 QCV PoC의 필수 DB/API 테스트는 skip 없음.
- 다음: STEP 12 문서/기존 PR 업데이트.

### STEP 12 — 완료
- 문서: stage02-progress.md, stage03-interface.md, qcv-poc-validation.md, integration-test-results.json, audit-performance.json 및 README/model/API/QCV 설명 갱신. 이전 리뷰 기록 보존.
- source6파일 및 이번 변환 XLSX SHA/bytes, 보드별 수치·issue·실행 XML suite 집계·성능 실제 sample·지원/미지원 범위·재현 명령 기록.
- 기존 feat/stage02-design-modeling 브랜치에서 core/parser, raw·batch 수정, real source 회귀 테스트, 문서별 커밋. 기본 브랜치 병합 없음. PR #1에 동일 브랜치 후속 변경 반영.
- 문서/라이선스 검증: git diff --check; Git tracked 목록에 외부 Sparrow CSV/QCV/XLSX 및 참고 Python 없음. 기존 KiCad fixtures는 유지. React/Stage01/03 engine/04 코드 변경 없음.
- 미해결/다음: 실제 삼성 QCV/EDA버전/계층 scope·alias 샘플 확보 후 adapter 검증; 사내 인증/설계 권한/민감 원문 정책; Stage03 담당자와 pin-function/MPN mapping 및 snapshot 계약 통합.

### 변경 파일 위치 요약

| 단계 | 주요 변경 파일 |
| --- | --- |
| 0 | 기존 코드/PR 확인, baseline 실행; docs/stage02-progress.md |
| 1 | scripts/fetch-qcv-fixtures.py, scripts/qcv-fixtures.json, tools/CsvToXlsx.java, build.gradle, .gitignore, QcvConversionTest |
| 2 | QcvSourceStructureTest, qcv-poc-validation.md |
| 3 | parse/QcvNetlistParser.java, model/ComponentPin.java·PinDisposition.java·ImportIssue.java, QcvParserTest |
| 4 | parse/BomParser.java, QcvIntegrationTest |
| 5 | graph/CircuitGraph.java, QcvGraphTest |
| 6 | persistence/ComponentPinEntity.java·CircuitDesignStore.java, V3 migration, QcvPersistenceTest |
| 7 | api/DesignController.java, QcvApiTest, 기존 API 기대값 |
| 8 | QcvBoardRegressionTest |
| 9 | parse/EngineeringUnits.java·BomParser.java, QcvRobustnessTest·QcvPerformanceTest |
| 10 | application.yml, CsvToXlsx budget 정리 |
| 11 | clean test bootJar 실행 결과, integration-test-results.json·audit-performance.json |
| 12 | README, docs/stage02-{api,model,qcv,pr,progress,review}.md, stage03-interface.md, qcv-poc-validation.md |
