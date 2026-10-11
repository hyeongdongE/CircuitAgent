# Stage 03 통합 PoC 진행 기록

시작 기준: PR #1 OPEN, base main, HEAD 29a9f17885fea7ee0380ac68227f5fb12d7612d9.
작업 브랜치 feat/stage03-integration-poc는 Stage02 최신 브랜치에서 분기했습니다.

## STEP 0 — PASS
- 확인: Java21/Boot3.5.16/Gradle8.14.6; 기존 Stage02 immutable model/Map·Set graph/DesignQueryService/JPA/Flyway V1~V3/REST와 80개 테스트.
- 기존 Rule/Audit/React/package.json/AGENTS.md 없음. Stage01/Rule Parser 또는 팀원 Stage03을 대체하지 않고 audit.poc 패키지와 frontend를 추가합니다.
- 변경: integration-progress.md 및 작업 브랜치만 준비. 기존 Stage02 코드 보존.
- 테스트: clean test bootJar: 80 PASS/FAIL0/SKIP0, BUILD SUCCESSFUL 4m2s, 실제 Docker MySQL8.0.43.
- 충돌 위험: PR1 미병합으로 base feat/stage02-design-modeling인 stacked PR 예정; 작업 종료 전 원격 갱신 확인.
- 다음: STEP1 조회 계약 테스트.

## STEP 1 — PASS
- 구현: 기존 DesignQueryService.snapshot 재사용, CircuitGraph.designSnapshot() additive getter로 버전/무결성 hash 근거를 추가 DB 조회 없이 확보.
- 변경: CircuitGraph.java, SnapshotContractTest.java.
- 테스트: SnapshotContractTest 1 PASS/BUILD SUCCESSFUL. 실제 FE U16 pin5→U16_EN→R107/R83, DNP/단위/출처/null MPN·pin name/TERM·missing 상태 및 불변성.
- 문제/해결: 핀 기능은 QCV에 없어 null 유지; Datasheet binding은 Stage03 입력 책임.
- 다음: STEP2 독립 poc-rule-v1 계약/교체 Adapter.

## STEP 2 — PASS
- 구현: poc-rule-v1 AuditRule/Target/Bounds/Divider/DatasheetSource와 RuleInputAdapter/StructuredRuleAdapter. Stage01 cp-v1 미확정으로 별도 교체 경계.
- 변경: audit/poc/AuditRule.java·RuleInputAdapter.java·StructuredRuleAdapter.java, RuleInputTest.java.
- 테스트: RuleInputTest 2 PASS, IDs/명시 source/version/target/condition/unit/range/tolerance/큰 숫자/중복 검증.
- 미완료: 실제 팀원 Stage01 Parser 없음; Adapter 연결은 BLOCKED, 기존 코드 대체 없음.
- 다음: STEP3 공식 데이터시트와 실제 rule bindings.

## STEP 3 — PASS
- 공식 TI product page/PDF 직접 확인: SLVSDG6C revised March2026, SHA1632b388…fdbdc5. PDF/추출 text/HTML은 ignored .local에만 보존, 재배포 가정 없음.
- 근거: DDC6pin table p3 GND1/SW2/VIN3/FB4/EN5/BOOT6; p4 operating VIN4.5~28V; p5 Vref0.581/0.596/0.611V(조건 명시); p11 1% 이하 divider 권장; p15 Eq7 및0.1uF bootstrap.
- 실제 source: U15/16/17 family/package candidate TPS54302DDC, 주문용 DDCR/DDCT MPN은 BOM 미확인. 과거 R3 silicon과 최신 C revision 일치 미확인. 기존 모델 MPN 변경 없음.
- 실제 U16 R107/R83 모두NM; FB R82=82k/R87=4.3k fitted; C5=100n fitted BOOT6↔SW2 연결. Net명12V를 목표 측정전압으로 사용하지 않음.
- 변경: poc/tps54302-rules.json (별도 정형 PoC fixture/catalog), PocRuleCatalog, scripts/tps54302-datasheet.json, DatasheetCatalogTest.
- 테스트: DatasheetCatalogTest 1 PASS. official pin/constant metadata 및 actual C5↔IC Net 목록 확인. 최소 catalog6개, 공차/target/MPN 누락 명시.
- 다음: STEP4 결과5상태/출처 모델.

## STEP 4 — PASS
- 구현: AuditStatus5종, AuditResult/AuditExecution/AuditEvidence typed immutable collections. Rule/version/source·design/version/hash·측정값/범위/unit/근거/누락/time 저장 계약.
- 변경: audit/poc/AuditStatus.java·AuditResult.java·AuditExecution.java·AuditEvidence.java, AuditResultTest.
- 테스트: AuditResultTest 1 PASS, 다섯 상태 구분/UNKNOWN 누락사유 필수/실제0·출처/불변 list.
- 다음: STEP5 결정론적 checker, 실제/자체 fixture 구분.

## STEP 5 — PASS
- 구현: DeterministicRuleChecker, range/attribute presence/pin connection/net match/FB equation. BigDecimal DECIMAL128, SI 단위 일치, explicit comparison tolerance, optional resistor/Vref worst-case interval, lower0 guard.
- 관계: fitted/DNP/unknown assembly, conflicting import data, TERM/missing/ambiguous pin state; unknown input→UNKNOWN, explicit targetDNP→NOT_APPLICABLE, unexpected engine exception→ERROR with sanitized reason.
- 근거: source/condition, component/spec/rawSources, PinContext 및 QCV location, 원문 이슈, formula/input/tolerance/calculation 기록. Net 이름을 전압으로 사용하지 않음.
- 변경: DeterministicRuleChecker.java, RuleCheckerTest.java.
- 테스트: RuleCheckerTest6+RuleInput2+DatasheetCatalog1=9 PASS. 자체 fixtures의 PASS/FAIL/UNKNOWN/DNP/ERROR/zero/tolerance 및 실제 EN/FB 확인.
- 한계: nominal checks만 구현; 자연어·수식 eval/LLM/온도·DCbias/full converter compliance 없음. 실제 EN floating은 정상일 수 있어 TERM을 오류로 단정하지 않음.
- 다음: STEP6 실제 upload→snapshot→rules MySQL/API 경로 검증.

## STEP 6 — PASS
- 실제 multipart XLSX/QCV→MySQL→DesignQueryService 단일 snapshot→6개 catalog rule 실행.
- 변경: ActualCircuitAuditTest.java. 원본 입력/Stage02 동작 재구현 없음.
- 테스트: ActualCircuitAuditTest 1 PASS/actual MySQL8.0.43. 결과 PASS1(100nF nominal BOM check만)/UNKNOWN5, FAIL0. EN NM→UNKNOWN; FB=11.961581395…V nominal/UNKNOWN, outputNet12V를 목표전압으로 추정하지 않음.
- 출처: 모든 result에 공식 SHA/revision/condition와 실제 component/BOM/QCV pin evidence. 실제 result는 ignored actual-audit-results.json에 보존.
- 다음: STEP7 V4 audit immutable snapshot/JSON evidence/FK/rollback.

## STEP 7 — PASS (초기 실패 및 수정 기록)
- 새 V4 snapshot_hash를 CHAR로 선언했으나 JPA String은 VARCHAR 검증: Spring context schema validation 실패로 5 tests FAIL.
- 해결: 미공개 새 V4의 해당 컬럼만 VARCHAR64로 일치시킴. V1~V3 변경 없음. late rollback은 중복 rule의 실제 SQL unique 실패를 사용하도록 강화.
- 다음: 동일 audit/기존 저장 테스트 재실행; 통과 전 STEP8 진행하지 않음.
- 재실행: 기존 저장3 PASS, audit2 FAIL. (1) 실제 테스트 helper의 고정 name/version이 테스트 간 중복, (2) 불필요하게 전체 GND 이웃의 raw/spec을 evidence에 복제해 JSON 정렬에서 MySQL 기본 sort memory 초과.
- 해결: helper name에 UUID(실제 source unchanged); Net-match evidence는 대상 Pin/Net 및 이웃 ref 목록만 유지, 필요없는 사양 복제 제거; 결과 JSON은 SQL filesort 없이 최대100개를 Java에서 result_order 정렬, 조회 인덱스 추가. 동일 테스트 재검증.
- 세 번째 검증: 10 PASS/1 FAIL. 전체 recursive comparison이 MySQL JSON의 binary64 숫자에서 calculated BigDecimal34자리 및 numeric timestamp nanos 손실을 발견.
- 해결: audit metadata/payload JSON에 lossless serialized JSON string envelope 사용. Boot ObjectMapper로 typed 복원하여 전체 decimal·Instant·Rule/source를 정확히 유지. status/design/version/hash/index 컬럼은 별도 그대로 유지. HTTP measured는 decimal string으로 반환해 JS precision loss도 방지. 비교/기대값 완화 없음.
- 최종 STEP7 검증: AuditPersistence2+MySqlPersistence3+RuleChecker6=11 PASS, BUILD SUCCESSFUL. 실제 MySQL exact recursive roundtrip/hash(원본 모델 vs DB복원)/source·version·Rule·nano time·DECIMAL128/SQL unique late rollback/교차설계FK PASS. 정밀도 완화/skip 없음.

## STEP 8 — PASS
- AuditService는 실행당 DesignQueryService.snapshot 1회. Rule catalog 선택 또는 poc-rule-v1 typed adapter, run/store 후201; FAIL/UNKNOWN은 HTTP 에러가 아님.
- 기존 route 보존; POST/GET /api/designs/{id}/audits, GET /{auditId}·/results; GET /api/audit-rules.
- 변경: AuditService.java·AuditController.java·AuditApiExceptionHandler.java, AuditApiTest.java.
- 테스트: 실제 MySQL/API roundtrip/정확decimal string/history/다른design404 및 자체 fixture FAIL201/invalid400/missing404 실행 중.
- 다음: 통과 후 STEP9 React.
- STEP8 최종: AuditApi2+ActualCircuitAudit1=3 PASS, bootJar SUCCESS. 실제 results/history/source/정확decimal string 재조회; 자체 FAIL201; bad rule400/없는design404/crossdesign audit404. 권한 검사와 ID격리는 별개이며 SSO 없음.

## STEP 9 — PASS
- 새 frontend/ React·TypeScript·Vite·TanStackQuery; 기존 React/팀원 코드 없음. API live data만 사용, 자체 UI tests의 mock은 실제 data와 구분.
- registry 확인: React19.3.0 MIT, Vite8.3.4 MIT, TanStackQuery5.104.1 MIT, Playwright1.64.0 Apache2.
- 다음: frontend 설치/빌드/단위·실제 브라우저 E2E.
- STEP9 초기: frontend build PASS, unit4 PASS/2 FAIL(jsdom required file native submission). form handler 직접 제출 테스트로 분리, 실제 native behavior는 browser로 검증.
- browser 초기 invalid upload PASS, real pipeline5s assertion timeout FAIL(upload pending). HTTP201 응답을 명시적으로 기다리고 UI waiting15s/test120s 설정; 응답/수량/상태 기대값 변경 없음.
- 최종: npm build PASS(TypeScript7.0.2/Vite8.3.4), Vitest6 PASS, 실제 Chromium156 Playwright2 PASS(30.6s). network mocks 없는 실제 upload→parse→DB→snapshot→rule→auditDB→API→React→page reload/history 검증. 모바일 invalid upload PASS. test trace/raw data는 ignored artifacts로만 보존.
- 다음: STEP10 safe local boundary/production deny/JSON strict validation.

## STEP 10 — PASS / SSO BLOCKED
- safe default: backend127.0.0.1, frontend127.0.0.1; local-poc mode/loopback/local Vite origin만허용. prod/production profile 또는 미설정 enterprise mode는 API403. 실제 사용자/설계 소유권/사내SSO는 BLOCKED; IP guard를 인증으로 주장하지 않음.
- 민감 원본 필드 기존 계약 유지하되 격리된 로컬에서만 제공; no-store/nosniff, frontend localStorage는 designID만.
- JSON512KiB actual-stream 제한(unknown content length 포함), strict typed contract unknown field거절, rules100/unique/단위·숫자·source 제한. 기존 multipart10MiB/file20MiB/request 유지.
- 초기compile FAIL: ObjectReader.readValue(JsonNode)의 IOException 선언 누락. throws/입력오류400 handler 수정. context-path prefix에도 guard 적용.
- 변경: security/PocAccessBoundary.java, application.yml, AuditController·AuditApiExceptionHandler, PocAccessBoundaryTest·AuditApiTest.
- 다음: 같은 security/API/기존API 집중검증 통과 후 STEP11 성능.

- STEP10 최종: boundary3+AuditAPI2+기존DesignAPI8=13 PASS, bootJar SUCCESS(2m49s). 로컬 origin/remote/production/context path/실제 JSON 제한 검증. SSO·설계 소유권은 별도 BLOCKED.

## STEP 11 — PASS
- 변경: AuditRunner.snapshotHashMs timing, AuditPerformanceTest.java. 실제 FE6 Rule/자체 반복60 Rule의 DB snapshot load·validation·traversal preflight·evaluation(추가 graph lookup 포함)·hash·audit persistence·MockMvc retrieval 각3회 측정.
- 자체 부하: 실제 모델에 연결 없는 synthetic component100/1000/5000개 추가. 물리적 실제 연결/실제 보드로 주장하지 않으며 해당 부하는 DB 저장 측정이 아님.
- SLA/1초 합격 기준 없음. warm-up 제외, median/mean/전체 sample/env 기록. 실행당 snapshot 1개 유지.
- 다음: 성능 테스트 통과 후 STEP12 전체 회귀/최종 JAR/browser.

- STEP11 최종: AuditPerformanceTest1 PASS/BUILD SUCCESSFUL3m21s. 실제6 Rule median snapshot541.8ms/validation0.82ms/traversal0.27ms/eval8.57ms/hash283.0ms/auditDB217.5ms/API85.8ms. 60개 반복 Rule과 synthetic부품 규모 결과는 전체 samples/mean/median과 docs/audit-performance.json에 기록 예정. 초기·환경변동 포함이며 이전 측정과 동일 머신/부하를 보장하지 않음.

## STEP 12 — PASS
- 확장: NET_MATCH PASS/FAIL, TERM/missing/ambiguous UNKNOWN, explicit exclusion NOT_APPLICABLE. 미실장 FB 저항에서는 nominal 계산조차 반환하지 않도록 보수적으로 보완하고 자체 DNP 회귀 추가. 실제 FE divider는 fitted이므로 기존 실측 결과 유지.
- Vite5173 strictPort로 origin 경계와 일치. 다음: clean test bootJar, frontend clean install/unit/build, 최종 JAR로 실제 browser E2E.

- 추가 신원 경계 검토: import된 MPN 문자열 존재가 제조사/데이터시트 Revision 적용성 검증은 아님. requireVerifiedMpn=true는 독립 attestation 계약이 없는 PoC에서 항상 UNKNOWN으로 보수적으로 제한. MPN 존재만으로 PASS가 되지 않는 자체 테스트 추가. Stage01/팀원 계약에서 검증된 identity attestation을 확보한 후 확장 필요.
- Frontend 최종 npm ci/unit6/build PASS, npm audit --omit=dev 현시점 production vulnerability0. 보안 JAR browser2 PASS(44.3s); 추가 identity hardening 이후 전체 suite 및 browser 재실행 예정.

- 1차 전체 clean test bootJar:102 PASS/FAIL0/ERROR0/SKIP0, BUILD SUCCESSFUL6m55s. 기존80 유지 확인. 위 identity attestation 회귀1개를 더한 최신 소스로 최종 전체 suite 재실행 중.

- 최신 identity boundary JAR의 browser E2E2 PASS(34.3s): 실제 XLSX/QCV→MySQL→U16_EN/R107DNP→6 Rule(1PASS5UNKNOWN)→정확 decimal/evidence→history reload, 모바일 invalid upload. network mocks 없음. backend 최종103 suite 종료 대기.

- 최종 전체 clean test bootJar --no-daemon:103 PASS/FAIL0/ERROR0/SKIP0(기존80+신규23), BUILD SUCCESSFUL6m18s. source fcdd95e. 추가 identity regression 포함. React6 PASS/build PASS/실제 browser2 PASS34.3s. MySQL/FK/rollback/isolation/V1 migration 모두 실행, automatic skip 없음.

## STEP 13 — PASS
- 구현: stage03-integration.md, stage03-poc-validation.md, audit-api.md, frontend-guide.md, integration-test-results.json, audit-performance.json, frontend-dependencies.json. README/Stage03 조회 계약에 새 구현 링크.
- 테스트: XML31 suites/103 total/기존80·신규23 집계, real result1PASS5UNKNOWN 확인, 공개 원본/파생 XLSX/PDF/trace 미커밋 확인. 문서의 수치는 최종 실행 artifact에서 생성. 과거 성능 기록은 보존.
- 미완료: 실제 Stage01 adapter/독립 identity/삼성 변형/기업SSO는 명확히 BLOCKED 또는 미검증. 새로운 기능으로 대체하지 않음.
- 다음: STEP14 최신 원격 상태 확인, branch push 및 PR1 base의 stacked PR.

## STEP 14 — 진행
- 변경: 별도 feat/stage03-integration-poc, 기능별 커밋; main 직접 병합 없음.
- 다음: 원격 PR1 최신 head/base 확인 후 새 PR 생성 및 OPEN/unmerged 검증.
