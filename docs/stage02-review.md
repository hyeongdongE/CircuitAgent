> 이전 HEAD 10a3636 기준 리뷰 기록입니다. 현재 공개 PADS QCV 구현/검증은 [PoC 결과](qcv-poc-validation.md)를 참고하세요.

# PR #1 Stage 02 리뷰 및 Stage 03 연동 준비

기준: PR #1 OPEN, base `main`, head `feat/stage02-design-modeling`;
검토 시작 HEAD `85b5b9d410fcb1df11c77ce8806dfe8eacd40586` (2026-10-11).
기존 변경/Fixture/Flyway V1을 보존하고 기본 브랜치를 병합하지 않는다.

## 순차 검토 기록

| STEP | 확인·발견 사항 | 수정·검증 결과 | 다음 |
| --- | --- | --- | --- |
| 1 | PR 전체 Java/JPA/SQL/API/테스트/문서/공개 Fixture 검토. Java 21, Boot 3.5.16, 기존 27 tests | `clean test bootJar`: 27 PASS, 0 failure/error/skip. 실제 MySQL 8.0.43. 5V 12 components/25 pins/6 nets/25 connections, R1=1000Ω, U1=L7805 확인 | 2 |
| 2 | 핵심 필드+동적 속성, 그룹 Reference, 빈 값·0, 원문 보존은 적절. DNP가 공유 사양 속성에 혼입; 동등한 SI 값의 원문 차이로 MPN 충돌; 헤더 없는 Excel 셀 조용히 유실; 단위 자릿수 무제한 | 모델 유지, 수정 후보 분류. baseline BOM 6 PASS | 3 |
| 3 | 무방향 incidence graph로 Net 합치기·전압 추정 없음. 계층 이름/미연결 lib pin 유지. 중복 Net 선언 뒤 node 유실, atom형 library alias 미인식, 모호/미연결 상태 구분 불가 | baseline Netlist 5, graph 3 PASS. 실제 중복·alias 회귀 테스트 필요 | 4 |
| 4 | 실제 `.qcv` 없음. 공통 NetlistParser/IR 및 미지원 stub 존재; source scope/key 규칙은 실물 확인 필요 | QCV 완료 아님. baseline 미지원 테스트 PASS는 문법 검증이 아님 | 5 |
| 5 | 6개 기본 쿼리는 CircuitGraph에 존재. designId 기반 Spring facade, pin name 조회, 상태/이슈 및 제한된 다단계 탐색 필요 | 기존 REST 응답을 유지하며 additive Java query API 준비. 검증 엔진/FB 계산 범위 제외 | 6 |
| 6 | 실제 MySQL roundtrip/롤백/버전 격리 확인. 미참조 spec은 load에서 유실. DB FK는 connection과 pin/net의 같은 design 보장 못함. 파일 확장자/content 및 POI 예외·파싱 자원 제한 보완 필요 | MySQL 3/API 5 baseline PASS. 인증·설계별 사용자 권한 없음: 삼성 데이터 배포 차단 사항. 새 version의 동일 파일은 허용, 같은 name/version 409 | 7 |

## 초기 발견 분류

| ID | 등급 | 문제 | 예정 조치 |
| --- | --- | --- | --- |
| C1 | Critical (기업 배포) | API 인증/설계 소유권 없음. UUID로 데이터 격리는 되지만 권한 통제 아님. 원문/source 경로 노출 | 현재 trusted PoC 범위 명시, 사내 SSO/소유권 정책 확정 필요. 임의 인증 모델 도입 안 함 |
| M1 | Major | DNP per-instance 상태 없음/MPN 공유 오염 | 명시적 실장 상태 추가, 사양과 분리, JSON/DB 보존 |
| M2 | Major | 같은 Net code 반복 시 추가 연결 유실 | 같은 이름은 node 병합, 충돌 이름은 파싱 거절 |
| M3 | Major | Excel 헤더 없는 셀 유실, POI runtime 오류 500, 파일 형식/자원 제한 약함 | 유실 대신 거절, 확장자/content 검사, 제한 및 입력 오류 처리 |
| M4 | Major | Stage 03 조회에 모호한 연결/누락 상태 및 제한 탐색 부족 | design-scoped snapshot, pin 상태/이슈, 명시적 경유 부품 기반 탐색 |
| M5 | Major | 미참조 사양 roundtrip 유실, DB에서 교차 설계 connection 가능 | 설계-spec 연결 및 복합 FK migration, 실제 MySQL 회귀 테스트 |
| M6 | Major | 같은 MPN/동등 숫자 원문 차이로 거짓 충돌 | SI 의미 비교, 원문은 각 instance에 유지; 실제 충돌은 분리 |
| M7 | Major | atom alias library 핀 유실 | 두 alias 표현 지원 및 미연결 pin 테스트 |
| m1 | Minor | Type 대소문자 때문에 값 비교 거짓 불일치 | canonical type 비교 |
| m2 | Minor | tolerance SI prefix 허용/과도한 숫자 exponent | 퍼센트 prefix 거절, ± tolerance 지원, 숫자 크기 제한 |

실제 QCV 파서·삼성 회로·사내 인증/접근 통제 테스트는 아직 실행할 입력/명세가 없다.

## STEP 7 — 수정 및 중간 검증

- DNP/FITTED/UNSPECIFIED를 ComponentInstance에 추가(기존 Java 생성자 유지). MPN 재사용에서 DNP 제외, SI 의미 비교와 충돌 variant 재사용.
- BOM의 헤더 없는 비어 있지 않은 CSV/Excel 셀 거절. XLS/XLSX 실제 포맷 검사, POI runtime → 입력 오류. BOM 행 위치 별도 provenance 유지.
- 중복 Net code의 같은 이름은 node 병합, 이름 충돌은 입력 거절. atom/library alias 및 sheet UUID 보존.
- Stage 03 designId facade/snapshot, pin name 전체 매칭, 연결 상태/관련 이슈, allow-list 기반 제한 탐색. 기존 6개 graph query 유지.
- V2에 per-instance 실장 상태, 전체 설계-spec 연결, pin/component/net의 설계 복합 FK 추가. V1 파일 수정 없음. JDBC stream 대신 list 사용.
- Netlist 확장자·파서 크기/행/셀/token·단위 precision 제한. 파일 경로 basename 처리, 파싱 오류 응답에 내부 예외/원문 노출 방지.
- `compileJava` PASS. 첫 집중 테스트 28개 중 27 PASS/1 FAIL: 새 테스트의 D5 pin 기대값 오류(공개 원문에서 Net 5의 D5는 pin 2). Fixture 근거로 테스트를 수정했고 재검증 진행.
- API 영향: 기존 응답 필드/route 유지, assemblyStatus와 pin-context route 추가. MISSING_PIN은 새 context API에서 200 상태값, 기존 getNetOfPin은 예전처럼 예외. 신규 DNP 기본은 UNSPECIFIED로 실장 여부를 추측하지 않는다.

다음: STEP 8 전체 회귀/MySQL migration/격리/FK/API/JAR 검증.

## STEP 8 — 최종 검증 완료

명령: `cd backend && ./gradlew clean test bootJar --no-daemon`
(Java 21, 환경 proxy/CA 및 별도 Gradle cache 설정). **51 tests PASS, failures/errors/skipped 0,
BUILD SUCCESSFUL**. 기존 27 + 신규 24. `git diff --check` PASS.
[현재 전체 XML 기반 집계](integration-test-results.json) (51개는 이 리뷰 당시 수치), JAR `backend/build/libs/circuitagent-0.1.0-SNAPSHOT.jar`.
초기 전체 수정 검증 48 PASS 이후 XLSX 사전 압축 해제 제한/속성 충돌/unknown inventory
보조 테스트 3개 추가 및 그래프 parallel edge 보존 보완으로 최종 전체 검증을 재실행했다.

- 실제 공개 5V: 12개 BOM/12개 Netlist/12개 매칭, R1=1000Ω, U1=L7805,
  25 pins/6 nets/25 connections. 기존 CSV/Netlist 4개의 원본/checksum 변경 없음.
- Shift: BOM 27개 모두 매칭, Netlist 31개, H1~H4의 추가 부품 이슈 유지.
- ReviewRegressionTest 17: 그룹/순서/빈 셀·0·MPN 누락, DNP 및 사양 공유/충돌,
  잘못된 CSV/Excel·확장자, 단위 범위/± tolerance, 중복 Net 병합/이름 충돌,
  library alias·미연결/모호 상태·계층 UUID, 실제 회로 다단계/복수경로,
  DNP 경유 금지, 입력 크기/token 제한, 압축 해제 한도, 명시 속성 충돌/provenance,
  핀 inventory unknown 상태.
- ReviewMySqlTest 3: DNP/미참조 spec 복원, 설계별 사양/조회/이슈 격리,
  교차 설계 pin 및 net FK 거절(다른 제약으로 인한 실패가 아님을 constraint 이름까지 확인).
- MigrationUpgradeTest 1: 실제 MySQL의 데이터가 있는 V1에서 V2로 변환,
  기존 핀/연결·설계-spec 소속 유지, composite FK와 UNSPECIFIED 기본값 확인.
- DesignApiTest +3 (총 8): 잘못된 Excel/Net 확장자 400, 크기 초과 413,
  추가 pin-context/실장 상태 조회. 기존 업로드/조회/없는 Component/409/롤백은 계속 PASS.
- 실제 QCV 성공 파싱, 삼성 원본, SSO/사용자 권한, 대규모 성능·외부 Stage 03 DTO 호환은 미실행.
  QCV 미지원 422 테스트를 QCV 파싱 성공으로 계산하지 않는다.

## 최종 리뷰 결과

| 항목 | 상태 | 발견한 문제 | 조치 |
| --- | --- | --- | --- |
| BOM Parser | PASS (검증 범위) | DNP 공유 오염, 숫자 원문 거짓 MPN 충돌, Excel 셀 유실/오류/압축 크기 | per-instance placement, SI 의미 공유, 명시 값 충돌 이슈, 포맷/자원 검사 |
| Netlist Parser | PASS (KiCad) | duplicate Net 연결 유실, atom alias 핀 유실 | node 병합/충돌 거절, 두 alias 표현 지원 |
| Circuit Model | PASS | per-instance 실장 상태 및 provenance 부족 | additive assemblyStatus/행 위치, null/0 구분 유지 |
| Graph | PASS | 모호/미연결 구분·제한된 다단계 탐색 부족 | 상태/이슈 context, 명시적인 경유 허용 목록, edge kind와 복수 경로 보존 |
| MySQL | PASS (실제 MySQL) | 미참조 spec 복원 유실, DB의 교차 설계 connection 허용 | design-spec 링크 및 복합 FK, 실제 저장/복원/롤백/격리/마이그레이션 테스트 |
| REST API | ISSUE (기업 배포) | 파싱 경계 개선 필요; 인증/설계 소유권 없음, 원문/source 포함 | 경계 오류/크기/형식 보완, additive 조회 유지; SSO/권한은 미해결 |
| QCV 확장성 | BLOCKED (실제 파서) | 원본·명세·정답 회로 없음 | 기존 NetlistParser/Common IR 재사용 경계 유지, 실제 파서 미구현/422 명시 |
| Stage 03 연동 | READY (KiCad 조회 계약) | pin 이름·누락/모호 상태·다단계 조회 필요 | DesignQueryService/snapshot 및 계약 문서. 삼성 QCV/Rule audit 성공은 아직 미검증 |

## 수정 파일과 이유

- `model/{ComponentInstance,AssemblyStatus}.java`: 실장 상태를 사양에서 분리, 기존 5인자 생성자 유지.
- `parse/{BomParser,EngineeringUnits,ParserLimits,KiCadNetlistParser,SExpression}.java`:
  위 파싱 정확성/예외/단위/파일·압축 해제·셀·token 제한. 새로운 의존성 없음.
- `graph/{CircuitAssembler,CircuitGraph,DesignQueryService}.java`: DNP/원문 연결,
  designId 기반 조회, pin name/context, 제한된 topology 탐색. Rule 계산 없음.
- `persistence/{CircuitDesignEntity,ComponentInstanceEntity,ComponentPinEntity,CircuitDesignStore}.java`:
  실장 상태/사양 소속/핀 design ID 보존, JDBC stream 수명 문제 제거.
- `db/migration/V2__placement_and_design_integrity.sql`: 기존 V1 수정 없이 추가 구조와 FK.
- `api/{DesignController,DesignImportService,DesignApiExceptionHandler}.java`: additive 응답/route,
  확장자 검증·filename basename·안전한 실패 응답. 기존 route/필드 제거 없음.
- `test/{ReviewRegressionTest,ReviewMySqlTest,MigrationUpgradeTest,DesignApiTest}.java`:
  위 회귀와 실제 DB/API 검증. 공개 Fixture는 수정하지 않았으며 생성 입력은 보조/adversarial 테스트다.
- README, 모델/API 문서, review/집계/Stage 03 계약/진행 문서: 지원 범위와 검증 증거.

## 남은 자료 및 제한

1. 실제 QCV + 대응 BOM, 생성 EDA 도구/버전/인코딩, 허용된 명세와 정답 연결.
   계층별 동일 Reference, 논리/물리 pin, 전역 전원 scope/미연결 규칙은 실물 기반 확인 필요.
2. 사내 SSO, 설계 소유/조직 권한 및 원문 조회 정책. 현재 trusted PoC 외 기업 배포 차단(C1).
3. 실제 Stage 03 소비 DTO/Rule 스키마 계약과 대규모 회로 수량/성능 목표.
   `getPinsByName`의 복수/빈 결과, assemblyStatus UNSPECIFIED, ImportIssue를 명시적으로 처리할 것.
4. V1의 과거 실장 상태는 UNSPECIFIED로 시작한다. 기존 raw BOM을 보존하며,
   과거 DNP의 자동 backfill은 하지 않는다. 필요 시 원본의 새 버전 import/검증된 변환.
   이미 V1에서 사양 소속이 유실된 미참조 spec은 V2만으로 과거 소속 복구 불가.
5. CSV 첫 유효 행은 헤더, UTF-8 및 명시적 ref 목록 필요. Reference range,
   vendor별 단위/컬럼, Excel 수식 원문은 원본 파일에서 확인해야 한다(셀 표시값은 평가값).
6. MySQL DDL migration은 transactional rollback이 아니다. 기존 DB에 교차 설계
   관계가 있으면 FK 적용이 실패하므로 사전 무결성 점검/백업 후 적용해야 한다.

다음 단계: STEP 9 문서/커밋/PR #1 업데이트, 기본 브랜치 병합 없음.

## STEP 9 — PR #1 반영

- 기존 feature 브랜치에 `99c334c`(정확성/조회/무결성)와 `f7d376e`(회귀/실제 DB/API 테스트)를 push 완료.
- PR #1 제목·설명을 51개 테스트와 최종 기능/미해결 QCV·권한 경계에 맞게 업데이트 완료.
- `gh pr edit`는 CLI의 폐기된 Projects classic 조회 오류로 실패했고,
  GitHub REST PATCH로 같은 PR 업데이트를 완료했다. 권한 부족/새 PR 생성 문제가 아니다.
- 원격 확인: OPEN, base `main`, head `feat/stage02-design-modeling`.
  https://github.com/hyeongdongE/CircuitAgent/pull/1
- 리뷰/계약/집계/진행 문서는 별도 문서 commit으로 같은 브랜치에 반영한다.
- 원래 PR의 변경/커밋/Fixture/V1 보존, 기본 브랜치 직접 병합·force push 없음.
- 다음: 팀이 PR 검토, 실제 QCV·대응 BOM·SSO/설계 권한 정책·Stage 03 DTO 확보 후
  QCV adapter와 기업 배포 접근 통제 및 실제 회로 연동 검증 진행.
