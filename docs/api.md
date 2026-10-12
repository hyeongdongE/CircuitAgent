# Python REST API

로컬 origin `http://127.0.0.1:8080`, OpenAPI `/api/docs`. UUID는 설계 식별자이며 사용자 권한을 증명하지 않는다. 데이터 응답은 no-store다. 기업 모드/외부 클라이언트는 미설정 상태에서 거절한다.

| 메서드 | 경로 | 결과 |
| --- | --- | --- |
| POST | /api/designs/import | multipart name/version/netlistFormat/bom/netlist, 201 + 생성 ID/수량/Issue |
| GET | /api/designs/{id} | 전체 공통 모델 |
| GET | /api/designs/{id}/components | 부품 목록 |
| GET | /api/designs/{id}/components/{ref} | 부품·동적 사양·Pin/Net·실장 상태·출처 |
| GET | /api/designs/{id}/components/{ref}/pins | 핀 목록 |
| GET | /api/designs/{id}/components/{ref}/pins/{number} | 연결 상태와 부품/사양/Net/Issue |
| GET | /api/designs/{id}/nets | 연결망 목록 |
| GET | /api/designs/{id}/nets/{netId} | Net 이름·연결 핀·부품 |
| GET | /api/designs/{id}/issues | ImportIssue |
| GET | /api/audit-rules | 수동 PoC 카탈로그·Pin mapping·출처 |
| POST | /api/designs/{id}/audits | 평가·저장, 201 + AuditExecution |
| GET | /api/designs/{id}/audits | 최신50개 이력/상태별 수량 |
| GET | /api/designs/{id}/audits/{auditId} | 저장된 실행과 Evidence |
| GET | /api/designs/{id}/audits/{auditId}/results | 결과 목록 |

Reference/Net ID는 URL 인코딩한다. 계층 Reference의 `/`도 보존한다. 부품 미존재404, 알려진 부품의 미존재 핀은 PinContext.status=MISSING_PIN이다.

업로드 예:

```sh
curl -F name='Sparrow FE' -F version=R3 -F netlistFormat=qcv -F bom=@.local/qcv-fixtures/fe.csv -F netlist=@.local/qcv-fixtures/fe.qcv http://127.0.0.1:8080/api/designs/import
```

Audit 요청은 schemaVersion과 ruleIds 또는 rules 중 하나만 제공한다. 알 수 없는 필드/ID/중복을 거절한다.

```json
{"schemaVersion":"poc-rule-v1","ruleIds":["tps54302-c5-nominal","tps54302-u16-en-network"]}
```

AuditExecution: id/designId/designVersion/snapshotHash/ruleSnapshot/results/executedAt/timingMs. 각 결과에는 ruleId/ruleVersion/reference/status/measured/expected/unit/reason/missingInputs/evidence가 있다. Evidence는 datasheet/condition/components/specifications/pins/calculation/issues이다. Decimal은 정밀 문자열이다. null은 누락이고 0은 실제 숫자다.

정상 평가한 FAIL/UNKNOWN도 HTTP201이다. 잘못된 계약400(INVALID_INPUT), 미존재404(NOT_FOUND), 중복/무결성409(DESIGN_CONFLICT), 실제 body 한도413, PoC 경계403, 저장 오류500(PERSISTENCE_ERROR)이다. 오류 응답에는 원본 파일/DB 접속정보/내부 exception 문자열을 포함하지 않는다.

파일당10MiB, multipart 요청20MiB, Audit JSON512KiB, Rule100개. XLSX ZIP expanded32MiB/entry1000, BOM row50000/column256/cell250000, Netlist token500000/depth128 한도를 적용한다. 실제 파일 magic과 문법도 확인한다.

CSV 동적 BOM 입력은 `bomMapping` multipart 필드를 선택적으로 받는다. 예:

```json
{"columns":{"reference":"부품번호","value":"값","dnp":"장착제외","Custom Voltage":"전압"},"units":{"Custom Voltage":"V"},"encoding":"cp949","delimiter":";","noMountBlank":"UNSPECIFIED"}
```

`columns`는 논리 속성→실제 헤더 매핑이다. Reference 컬럼은 알려진 별칭 또는 명시 매핑으로 식별해야 한다. 행 번호, 값 또는 MPN으로 Reference를 추측하지 않는다. 기본 인코딩은 UTF-8/BOM이며 CP949 등은 명시한다. 헤더 순서, 행 수, 부품별 선택 속성은 고정하지 않는다. 그룹 Reference는 개별 객체로 분리하고 Netlist의 동일 Reference에만 연결한다. BOM만으로 실제 배선은 생성하지 않는다.

알 수 없는 컬럼은 동적 속성에 보존한다. 짧은 행의 빈 셀은 누락으로 유지하며 0으로 채우지 않는다. 이름 없는 추가 셀은 `column:N` 속성과 오류 Issue로 보존한다. 원본 행·헤더 순서·매핑은 DB 복원 후에도 유지한다. 단위는 알려진 속성이나 명시 `units`에만 적용한다. 생소한 BOM의 빈 NoMount는 실장 미확정이며, 공개 Sparrow 양식의 NM 정책 또는 명시 `noMountBlank`로만 해석한다.
