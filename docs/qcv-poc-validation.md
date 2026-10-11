# 공개 PADS QCV PoC 검증

검토 시작 PR #1 HEAD: 10a3636e3988a9ba8bae4bcc1e3c2cc040348ff2 (2026-10-11).
삼성 원본이 아닌 공개 Sparrow의 관측된 line-based NET/PIN dialect가 대상이다.

## 출처 및 이용 조건

Upstream: https://github.com/esharpab/hardware-ESH10000540-R3
고정 commit: `7a9de5b64c6b22502502700123ea00853dd38342`.
GitHub license=null, recursive tree에 LICENSE/COPYING 없음, root README 404.
이용/재배포 허가를 추정하지 않는다. 원본 CSV/QCV, 변환 XLSX와 참고 Python 코드는
`.local/`에서 로컬 검증에만 사용하고 Git에 커밋하지 않는다. 공개 재배포/배포 패키지에
넣으려면 권리자의 허가 또는 명확한 라이선스가 필요하다.
원본 URL/path/bytes/SHA-256은 `scripts/qcv-fixtures.json`에 기록한다.

## 재현

```sh
python3 scripts/fetch-qcv-fixtures.py
cd backend
./gradlew convertQcvBoms
./gradlew clean test bootJar
```

Java 21 JDK 및 Docker 필요. 변환은 Java/Commons CSV/Apache POI로 실행한다.
다운로드 Python 스크립트는 URL fetch와 checksum만 수행하며 백엔드/파서는 아니다.
소스는 `.local/qcv-fixtures/{fe,ntop,fe-ntop}.{csv,qcv}`, 변환은 같은 위치의 `.xlsx`이다.
CSV 세미콜론/UTF-8, 각 셀을 문자열로 저장한다. 행/열/값/공백/빈 셀/선행 0을 보존하고
XLSX 재열기 후 원본 CSV 모든 셀과 비교한다. XLSX ZIP 메타데이터 시각에 따라 파일 해시는
실행마다 달라질 수 있지만 셀 내용은 동일해야 한다. 실제 변환 파일의 해시도 검증 기록에 남긴다.
외부 Fixture가 없으면 테스트는 명시적으로 실패하며 자동 skip/PASS하지 않는다.

## QCV 지원 문법 및 해석

- 한 행의 `NET : 'name' REF-PIN ...`, `PIN : 'label' REF-PIN` 및 optional `(by TERM)`.
- 빈 줄/# section comment, UTF-8(선두 BOM 포함). 입력/행/token budget 검사.
- COMP 목록 불필요. NET과 별도 PIN에서 Component/Pin을 추출한다.
- Terminal은 greedy Reference + 마지막 구분자의 검증된 영숫자/underscore pin suffix.
  Reference의 하이픈/계층 경로/일부 특수문자를 보존하며 모든 `-`로 분할하지 않는다.
  숫자 pin은 공개 원본에서 검증했고 alphanumeric suffix는 보조 테스트에서 검증했다.
- Net 이름은 원문 그대로, ID는 label SHA-256 기반이다. Reference/Net 스코프는 설계 내부.
  임의 전압/핀 기능/제조사 MPN/사양을 만들지 않는다.
- 같은 Net label 반복은 Pin 집합을 병합하고 이슈 기록. 같은 Pin/Net 중복은 dedup+경고.
  하나의 Pin에 다른 Net 또는 명시적 PIN 선언이 충돌하면 연결 제외+ERROR.
- TERM은 정상적인 선언이며 가짜 Net/오류를 만들지 않는다. 별도 PIN label과 comment section,
  terminal, filename/행 번호는 pin.sourceMetadata에 보존한다. 원본 전체 파일은 DB에 저장한다.
- 일반 미연결 PIN은 UNCONNECTED, 명시 TERM은 TERMINATED. 한 핀 Net section의 bare PIN은
  불확실 상태로 보존하며 자동으로 다른 Net에 합치지 않는다. 생략된 핀 전체 목록은 추측하지 않는다.

미지원: COMP 등 다른 dialect record, 줄을 나눈 record, quote escape가 있는 Net label,
모호한 hyphen 포함 pin number, 별도 계층 scope/반복 local Ref 의미, 비 UTF-8 입력.
알 수 없는 문법은 실패시키며 삼성 QCV 전 변형을 지원한다고 주장하지 않는다.

## 실제 원문에서 재확인한 수치

| 보드 | BOM 데이터 행 | BOM refs | NET refs | QCV 전체 refs | Net | 연결 고유 pin | 별도 PIN | TERM | 일반 PIN | NoMount |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FE R3 | 69 | 445 | 445 | 453 | 335 | 1500 | 143 | 135 | 8 | 51 |
| N-Top R3 | 55 | 296 | 292 | 304 | 233 | 972 | 147 | 139 | 8 | 67 |
| FE N-Top R3 | 21 | 161 | 161 | 169 | 193 | 794 | 25 | 17 | 8 | 37 |

NET-connected pins와 별도 PIN 선언은 다른 지표다. 별도 PIN 143을 TERM 143으로 보고하면
틀리며 실제 TERM은 135다. 모든 보드의 PCB1~PCB8은 PIN-only/QCV-only이며 삭제하지 않는다.
N-Top의 M1~M4는 NET에는 없지만 PIN에는 있어, 전체 QCV와 BOM은 296개 모두 매칭된다.

원본 기준 FE U16_EN: R107-2, R83-2, U16-5. R107-1→VDD, R83-1→GND.
R107=140K, R83=56K이며 둘 다 NoMount=NM다. 이를 실장된 저항쌍/도통/회로 기능으로
판정하지 않는다. 기본 탐색은 DNP를 경유하지 않으며 명시적 INCLUDE_DNP_SCHEMATIC으로
원문 topology만 조회할 수 있다. NET/COMPONENT edge 종류와 원본 Net/BOM 행 위치를 반환한다.

## 현재 순차 검증

STEP 0: 기존 51 tests/JAR PASS. STEP 1: 변환 2 PASS. STEP 2: 원문 구조 1 PASS.
STEP 3: QCV+KiCad 9 PASS. STEP 4: 통합+BOM/회귀 25 PASS. STEP 5: 그래프/회귀 22 PASS.
STEP 6 DB9 PASS, STEP 7 API10 PASS, STEP 8 보드회귀/KiCad8 PASS. STEP 9 초기 실패 수정 후33 PASS, STEP 10 배치/DB/API/변환8 PASS. 최종 전체 수량은 아래와 같다.

## 모델 및 오류 처리

카탈로그 BOM은 RefDes/QtyOnBoard profile로 식별하고 PartNumber+Rev로 사양 재사용을
검토합니다. PartNumber를 제조사 MPN으로 만들지 않습니다. Part Name/Rev/Voltage와 임의
컬럼은 동적 속성으로, RefDes/NoMount/Qty는 인스턴스·검증 정보로 분리합니다.
원본 Value 및 속성 raw의 공백도 보존합니다. 2n2→2.2e-9 F, 3V6→3.6 V, 2,5→2.5 V를
검증하고 모호한 comma thousands는 거절합니다. 0R은 0 Ω, 빈 값은 null/속성 부재입니다.

실제 FE 모델의 이슈는 MISSING_VALUE 72 + NETLIST_ONLY_COMPONENT 8 = 80입니다.
N-Top은 85+8=93, FE N-Top은 121+8=129입니다. 이들은 원문 누락/추가 부품의 사실을
보존한 경고입니다. QCV 자체는 세 원본 모두 구문·연결 issue 0이며 TERM도 오류가 아닙니다.
초기 FE INVALID_UNIT 13건은 원문 embedded SI/decimal comma를 확인하고 수정했습니다.
새 회귀 테스트의 attribute.raw trailing-space 실패 1건도 수정 후 재검증했습니다.

## 저장 및 API

기존 CircuitDesignStore의 전체 모델+SourceFile 트랜잭션을 재사용합니다. design/version,
Reference/Pin/Net 관계와 JSON 동적 속성/이슈/원본 bytes/hash를 보존합니다. V3는 기존 V1/V2
데이터를 보존하는 additive pin disposition/source JSON migration입니다. 기존 FK/unique 및
원본 파일 저장 후 실패하는 late rollback/재시도/다른 설계 격리 테스트를 실행했습니다.
Hibernate insert batch 50/order_inserts와 MySQL rewriteBatchedStatements를 사용합니다.

기존 POST import/GET design/component/pin/net/issues API에 QCV를 연결했습니다.
MockMvc에서 실제 XLSX/QCV bytes를 업로드하고 실제 MySQL 저장·조회/재업로드409/
잘못된 파일400을 검증합니다. HTTP socket/browser 부하 테스트와 React 화면은 구현하지
않았습니다. React 프로젝트가 없고 API는 인증/사용자 권한이 없어 공개 배포하지 않습니다.
Stage 03의 Java snapshot 및 REST 예시는 stage03-interface.md와 stage02-api.md에 있습니다.

## 오픈소스 및 범위

기존 Apache POI/Commons CSV/Spring Data JPA/Flyway/JUnit5/Testcontainers만 재사용했습니다.
Map/Set로 제한적 topology 탐색을 제공하므로 JGraphT/추가 서버가 필요하지 않았습니다.
고정 upstream의 parse_qcv_review.py는 읽기 참고만 했습니다. 이 코드는 TERM-only set,
간단 CSV split, Reference regex 및 ERC를 포함하며 라이선스가 명확하지 않아 복제하지
않았습니다. Java adapter는 독립 구현이며 ERC/전원 이름 추정 등 Stage 03 기능을 가져오지
않았습니다. downloader 외 새로운 Python 실행 서비스는 없습니다.

## 미검증/운영 제한

삼성 QCV exporter/version/인코딩/계층·pin alias·global/local 전원 scope 샘플이 필요합니다.
한 설계 내 같은 Net label은 병합하는 관측된 공개 exporter 규칙이며 별도 scope가 있는
다른 dialect는 지원을 가정할 수 없습니다. QCV에는 핀 기능과 완전한 library inventory가
없어 FB/EN 기능 및 생략 핀을 추정하지 않습니다. BOM/QCV에 revision 메타데이터가 일관되게
들어 있지 않아 두 업로드 파일이 같은 board revision인지 자동 증명할 수 없습니다.
version은 호출자 제공값이며 고정 출처·체크섬과 mismatch issue를 확인해야 합니다.
운영 SSO/설계 소유권/민감 원문 정책, 실제 삼성 성능·용량 검증은 추가 작업입니다.

## 최종 전체 검증 (2026-10-11)

`cd backend && ./gradlew clean test bootJar --no-daemon` 성공, 3m59s/8 tasks executed.
21 JUnit suites, 기존51+추가29=80 PASS, failure0/error0/skipped0. 실행 JAR 생성 성공.
정확한 suite별 수량과 6개 원본 및 3개 XLSX SHA/bytes는 qcv-poc-test-results.json에 있습니다.

| 항목 | 실제 결과 |
| --- | --- |
| Sparrow FE R3 | BOM445/445 매칭, 전체453, Net335, 연결1500, PIN143/TERM135, NM51, issue80 |
| Sparrow N-Top R3 | BOM296/296 매칭, 전체304, Net233, 연결972, PIN147/TERM139, NM67, issue93 |
| Sparrow FE N-Top R3 | BOM161/161 매칭, 전체169, Net193, 연결794, PIN25/TERM17, NM37, issue129 |
| 기존 KiCad fixtures | 5V BOM/Netlist12, R1=1000Ω, U1=L7805, pin25/net6/connection25; Shift BOM27 모두 매칭, H1~H4 보존; 관련 기존 tests PASS |
| MySQL | 실제 8.0.43 Testcontainers, 3보드 전체 모델 복원, 원본 bytes/source/disposition/NM/연결, FK/rollback/버전 격리/V1→V3 upgrade PASS |
| API | 실제 FE XLSX/QCV multipart→MySQL→조회, 기존 KiCad/404/400/409/입력 제한 테스트 PASS; MockMvc 방식 |
| 전체 | 80 PASS, FAIL0, SKIP0; bootJar SUCCESS |

## 처리 시간 실측

Java21.0.12.1/Linux amd64, JVM heap512MiB/availableProcessors4, Docker MySQL8.0.43.
FE 실제445개 BOM+8개 QCV-only/335nets/1500연결을 같은 JVM에서 3회 순차 측정한 중앙값:

| 단계 | ms |
| --- | ---: |
| BOM XLSX parsing | 64.890 |
| QCV parsing | 18.740 |
| Model integration | 4.577 |
| Graph construction | 1.887 |
| MySQL persistence(원본 2파일 포함) | 1560.244 |
| API retrieval(전체DB복원+view/JSON) | 379.926 |

각 sample과 방법/환경, 비배치 baseline은 qcv-performance.json에 있습니다.
배치 미설정 baseline의 DB 중앙값13809ms, 배치설정 집중 측정1378ms, 최종 전체 suite측정1560ms.
JVM warmup/실행환경 차이가 있으므로 이 값은 환경 내 관측치이며 고정 SLA/동시 부하 보장은
아닙니다. 파일 read와 컨테이너/Spring 초기화, 네트워크 socket latency는 제외합니다.
생성 stress의 5000 Component/10000 연결은 한도 검증용이며 실제 공개 보드와 구분합니다.
