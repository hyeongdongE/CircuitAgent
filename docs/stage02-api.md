# Stage 02 API

`POST /api/designs/import`는 multipart/form-data를 받습니다.

| 필드 | 내용 |
| --- | --- |
| name | 필수, 설계 이름 (최대 191자) |
| version | 설계 버전 (기본 v1, 최대 64자); 같은 name/version 재등록은 409 |
| bom | CSV(UTF-8), XLSX 또는 XLS 파일 |
| netlist | KiCad S-expression .net 또는 공개 PADS .qcv 파일 |
| netlistFormat | 기본 kicad; .qcv 확장자는 QCV adapter 선택. 명시적 qcv도 가능 |

각 파일은 10 MiB까지, 요청은 20 MiB까지입니다. 응답은 201 Created, Location,
설계 ID, Component/Pin/Net 수 및 ImportIssue 목록입니다. 원본 파일과 모델은
하나의 MySQL 트랜잭션으로 저장됩니다. ImportIssue가 있는 회로도 저장할 수
있으므로 호출자는 severity/code를 확인해야 합니다. 중복 Reference의 첫 레코드는
보존하며, 모호한 핀 연결은 제외합니다.

조회는 설계 ID로 격리됩니다.

| 메서드/경로 | 응답 |
| --- | --- |
| GET /api/designs/{id} | 전체 공통 CircuitDesign 모델 |
| GET /api/designs/{id}/components | Reference 순서의 ComponentView 목록 |
| GET /api/designs/{id}/components/{ref} | 값, 사양/동적 속성, 핀과 Net, 원본 레코드 |
| GET /api/designs/{id}/components/{ref}/pins | 핀 번호·이름·타입·Net (미연결은 net=null) |
| GET /api/designs/{id}/nets | Net 목록과 연결 PinKey/Reference |
| GET /api/designs/{id}/nets/{netId} | 특정 Net의 연결 PinKey/Reference |
| GET /api/designs/{id}/issues | ImportIssue 목록 |

입력 오류는 400, 존재하지 않는 설계/부품/Net은 404, 이름/버전 또는 제약 충돌은
409, 파일 크기 초과는 413입니다. 지원 문법을 벗어난 QCV는 400입니다. 구문 파싱 오류 응답에는
PARSE_FAILURE ImportIssue와 파일 위치가 포함됩니다. 유효 모델을 만들 수 없는
파싱 실패는 설계를 저장하지 않습니다. 알 수 없는 수치는 null/속성 부재로 유지하며
0을 대입하지 않습니다. 단위 정규값은 SI 단위입니다.

```sh
curl -f -X POST http://localhost:8080/api/designs/import \
  -F 'name=5V Power Supply' -F 'version=v1' \
  -F 'bom=@backend/src/test/resources/fixtures/5v/5Volt_Power_Supply.csv' \
  -F 'netlist=@backend/src/test/resources/fixtures/5v/5Volt_Power_Supply.net'
```

위 명령은 저장소 루트에서 실행합니다. 공개 PADS QCV 지원 범위는 stage02-qcv.md를 참고하세요. Stage 03 검증 규칙은 구현하지 않습니다.

## PR #1 리뷰 후 추가 사항

Component 응답의 `assemblyStatus`와 상세 pin context route는
[Stage 03 조회 계약](stage03-interface.md)을 참고한다. 기존 route/필드는 유지된다.
Netlist는 `.net` 또는 `.qcv` 확장자만 허용하며 실제 내용도 해당 파서가 검증한다.
XLS/XLSX 확장자와 workbook 형식 불일치 및 비어 있지 않은 헤더 없는 셀은 400이다.
XLSX 압축 해제 합계는 32 MiB/1,000 entries까지이며 POI 로딩 전에 검사한다.
BOM 제한: 50,000행/256컬럼/250,000셀; S-expression은 500,000 values/256 nesting.
초과 시 일부 모델을 저장하지 않는다. 숫자 정규화는 문자열 128자, precision/scale 64 제한.
업로드 경로는 basename으로 정리하며 파싱 실패 응답은 입력 파일명과 일반 오류만 포함한다.

동일 name/version의 업로드는 409이고 같은 파일을 다른 name/version에 재사용하는 것은
허용한다. checksum은 원본 무결성 확인용이며 전역 파일 중복 업로드 차단 키가 아니다.
현재 API에는 사용자 인증/설계 권한 검사가 없다. 실제 삼성 데이터 배포 전 사내 SSO,
소유권 및 원문 조회 권한을 추가해야 한다. 전체 모델 원문/source 정보 노출 정책도 필요하다.

## 실제 XLSX + QCV 업로드

다운로드/POI 변환 이후 저장소 루트에서 실행합니다. 로컬 MySQL 연결이 필요합니다.

```sh
curl -f http://localhost:8080/api/designs/import \
  -F 'name=Sparrow FE' -F 'version=R3' -F 'netlistFormat=qcv' \
  -F 'bom=@.local/qcv-fixtures/fe.xlsx;filename=BOM.xlsx' \
  -F 'netlist=@.local/qcv-fixtures/fe.qcv;filename=Netlist.qcv'
```

201 응답의 실제 검증된 수량 필드(동적 UUID와 issue 목록은 생략):

```json
{"componentCount":453,"pinCount":1643,"netCount":335,"connectionCount":1500,"terminatedPinCount":135}
```

BOM 매칭은 445/445이며 응답 componentCount는 QCV의 PIN-only PCB1~PCB8도 포함합니다.
143개의 별도 PIN 항목 중 TERM은 135개입니다. 이슈 80개는 MISSING_VALUE 72개와
NETLIST_ONLY_COMPONENT 8개이며 누락 값은 0으로 채우지 않습니다.
PinView에는 disposition/sourceMetadata가 추가됩니다. net=null만으로 TERM/미연결/불명확을
판단하지 말고 상세 pin context의 status와 issues를 확인하세요.
