# Stage 02 API

`POST /api/designs/import`는 multipart/form-data를 받습니다.

| 필드 | 내용 |
| --- | --- |
| name | 필수, 설계 이름 (최대 191자) |
| version | 설계 버전 (기본 v1, 최대 64자); 같은 name/version 재등록은 409 |
| bom | CSV(UTF-8), XLSX 또는 XLS 파일 |
| netlist | KiCad S-expression .net 파일 |
| netlistFormat | 기본 kicad. qcv는 실제 샘플/명세 확보 전 422 |

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
409, 파일 크기 초과는 413, QCV 미지원은 422입니다. 구문 파싱 오류 응답에는
PARSE_FAILURE ImportIssue와 파일 위치가 포함됩니다. 유효 모델을 만들 수 없는
파싱 실패는 설계를 저장하지 않습니다. 알 수 없는 수치는 null/속성 부재로 유지하며
0을 대입하지 않습니다. 단위 정규값은 SI 단위입니다.

```sh
curl -f -X POST http://localhost:8080/api/designs/import \
  -F 'name=5V Power Supply' -F 'version=v1' \
  -F 'bom=@backend/src/test/resources/fixtures/5v/5Volt_Power_Supply.csv' \
  -F 'netlist=@backend/src/test/resources/fixtures/5v/5Volt_Power_Supply.net'
```

위 명령은 저장소 루트에서 실행합니다. 실제 QCV 지원과 Stage 03 회로 검증 규칙은
현재 API 구현 범위가 아닙니다.
