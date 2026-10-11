# Audit REST API

기존 [Stage 02 API](stage02-api.md)를 유지한다. 로컬 backend127.0.0.1:8080과 Vite5173 proxy에서만 사용한다. 예시의 DESIGN_ID/AUDIT_ID는 실제 import/execute 응답의 UUID로 교체한다.

| 요청 | 응답 |
| --- | --- |
| GET /api/audit-rules | schemaVersion/scope/pinMapping/rules의 PoC catalog |
| POST /api/designs/{id}/audits | 201 AuditExecution; FAIL/UNKNOWN도 정상 평가 결과 |
| GET /api/designs/{id}/audits | 최신50개 Summary(id/version/time/status counts) |
| GET /api/designs/{id}/audits/{auditId} | 저장된 Rule Snapshot/Evidence 포함 전체 실행 |
| GET /api/designs/{id}/audits/{auditId}/results | 저장된 AuditResult 목록 |

```sh
curl http://127.0.0.1:8080/api/audit-rules
curl -X POST "http://127.0.0.1:8080/api/designs/$DESIGN_ID/audits" \
  -H 'Content-Type: application/json' \
  --data '{"schemaVersion":"poc-rule-v1","ruleIds":["tps54302-u16-en-network","tps54302-u16-fb-nominal","tps54302-c5-nominal"]}'
curl "http://127.0.0.1:8080/api/designs/$DESIGN_ID/audits"
curl "http://127.0.0.1:8080/api/designs/$DESIGN_ID/audits/$AUDIT_ID/results"
```

Request는 schemaVersion=poc-rule-v1과 ruleIds 또는 rules 중 정확히 하나다. rules는 [정형 계약](stage03-integration.md)의 AuditRule 배열이다. 빈 배열/중복/미등록 ID/알 수 없는 필드/잘못된 단위·source·숫자는400이다. 전체 catalog 실행은 GET으로 받은6개 ID를 명시적으로 선택한다.

아래는 실제 FE 결과의 일부 필드만 발췌한 형태이며 UUID는 생략했다. 전체 응답에는 ruleVersion, expected, evidence와 executedAt도 포함된다.

```json
{
  "ruleId": "tps54302-u16-fb-nominal",
  "reference": "U16",
  "status": "UNKNOWN",
  "measured": "11.96158139534883720930232558139535",
  "unit": "V",
  "missingInputs": [
    "Verified manufacturer MPN / applicable silicon revision",
    "Verified resistor tolerance percentage",
    "Explicit expected target/range"
  ]
}
```

이는 조건부 nominal 계산이며 실제 출력 전압이나 정상 동작 판정이 아니다. Evidence에는 TI source(revision/page/section/hash), 실제 BOM/QCV 위치, R82/R87 실장·값, FB/GND/출력 Net, 식과 입력 문자열이 있다. HTTP201과 Rule PASS는 서로 다른 개념이다.

| HTTP/code | 의미 |
| --- | --- |
| 400 RULE_INPUT_INVALID | 잘못된 Audit JSON/Rule/UUID |
| 403 POC_ACCESS_DENIED | 원격·다른 Origin·production/enterprise 경계 |
| 404 NOT_FOUND | 없는 설계/검사 또는 요청 설계에 속하지 않는 검사 |
| 409 AUDIT_CONFLICT | 결과 저장 무결성 위반 |
| 413 JSON_TOO_LARGE | JSON 실제 body512KiB 초과 |
| 500 AUDIT_EXECUTION_ERROR | 요청 내부 오류; 상세 trace 미노출 |

엔진 내부 예외는 정상 실행의 개별 결과 ERROR로 표현할 수 있다. 파일 업로드는 기존400 PARSE_FAILURE/INVALID_REQUEST,409 IMPORT_CONFLICT,413 FILE_TOO_LARGE 등 기존 계약을 따른다. 원본 파일10MiB/파일,20MiB/request와 actual format 검사, JSON1~100 Rule 제한을 적용한다. 응답은 Cache-Control:no-store/X-Content-Type-Options:nosniff이다.

설계 ID를 조건으로 조회하고 DB FK로 격리하지만 사용자 권한 검사는 없다. 원문 Evidence를 외부 서비스로 전달하거나 공개 배포하지 않는다. GET 이력50개 제한은 PoC 범위이며 운영 이력 pagination/보존 정책은 별도다.
