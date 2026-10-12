# Stage 03 통합 PoC 계약

Stage 02의 Java 모델, 파서, 저장 및 조회를 재사용한다. 새 구현은 `com.circuitagent.audit.poc`이며 Stage 01/팀원 Rule Parser, 전체 AI 엔진과 Stage 04는 구현하지 않는다. PR #1의 HEAD29a9f17에서 별도 브랜치로 분기했다.

## Stage 01 교체 경계

실제 cp-v1/팀원 Parser가 저장소에 없어 연결은 BLOCKED다. `RuleInputAdapter`의 `StructuredRuleAdapter`는 수동으로 검토한 `poc-rule-v1`만 받는다. 공식 TI 문서에서 확인한 별도 `poc/tps54302-rules.json`은 PoC fixture이고 Stage 01 출력으로 주장하지 않는다. 자연어 해석, 임의 수식 실행, LLM 수치 판정은 없다.

`AuditRule`에는 id/version/type, target(reference/partName/pinNumber/pinFunction/requireVerifiedMpn), condition/applicable/notApplicableReason, attribute/unit, expected(min/max/tolerancePercent), expectedNet, divider, source(url/revision/page/section/summary/sha256)가 있다. 적용 대상은 명시적 Reference이며 MPN·값으로 자동 매칭하지 않는다. Part Name 조건은 원본 속성의 정확한 문자열 비교이다.

| Type | 연산과 범위 |
| --- | --- |
| VALUE_RANGE | SI 정규화된 nominal BOM 속성의 포함 범위 비교 |
| ATTRIBUTE_PRESENT | 명시적인 수치 속성과 단위 존재 확인 |
| PIN_CONNECTED | 하나의 유효 Net과 알려진 실장 이웃 존재 확인 |
| NET_MATCH | 명시된 Net 이름과 정확히 비교; 전압 추정 없음 |
| FB_DIVIDER | 지정된 저항·FB/GND 핀의 위상 확인 후 제한된 공식 계산 |

연산자는 Type으로 고정한다. 임의 `operator` 등 알 수 없는 필드는400이다. 범위에는 명시적인 비교 허용 오차(0도 명시)가 필요하다. 허용 SI 단위는 Ω/F/H/V/A/%이며 BOM Parser가 원문을 정규화한다. Adapter는1~100개, 고유 ID, source/version/condition, 숫자 precision/scale32 이하, tolerance0~50%, HTTPS source/SHA256 등을 검증한다. Source 검증은 문법 검증이며 요청 URL의 내용을 자동 검증하거나 다운로드하지 않는다. 사용자 정의 Rule은 검토된 로컬 입력으로 제한한다.

`requireVerifiedMpn=true`는 문자열 존재만으로 통과하지 않는다. 현재 계약에는 독립적인 제조사·실리콘·데이터시트 적용성 attestation이 없어 UNKNOWN을 유지한다. 향후 팀원 계약에서 검증된 신원 근거와 정책을 추가해야 한다. false인 nominal attribute 확인은 그 제한된 범위에만 적용한다.

## Stage 02 Snapshot

```java
var graph = designQueryService.snapshot(designId); // Audit 실행당 한 번
var component = graph.getComponent(reference);
var specification = graph.getComponentSpecification(reference);
var pin = graph.getPinContext(reference, pinNumber);
if (pin.status() == CircuitGraph.ConnectionStatus.CONNECTED) {
    var neighbors = graph.getConnectedPins(pin.net().id());
}
var model = graph.designSnapshot(); // 불변 모델, 버전 및 해시 계산
```

기존 [조회 계약](stage03-interface.md)을 유지한다. QCV pin.name은 null일 수 있으며 FB/EN 이름을 생성하지 않는다. 공식 DDC 핀 표의 바인딩은 별도 Rule 입력이다. COMPONENT와 NET 경유는 구분하며 저항·커패시터·0Ω 때문에 Net을 합치지 않는다. sourcePath/rawSources/sourceMetadata와 ImportIssue는 실제 입력에서 가져온다.

## 처리와 판정

Rule 검증 → 대상/적용성 → 불변 Snapshot → 속성·핀·Net 조회 → 누락/충돌/실장 상태 → BigDecimal DECIMAL128 계산 → 결과·근거 → 트랜잭션 저장 순서다. 매 Rule마다 DB를 복원하지 않는다. 비교 허용 오차와 저항 공차는 다른 입력이다. FB는2-pin 저항의 반대 핀, lower→IC GND, upper의 별도 출력 Net을 확인한다. lower≤0은 계산하지 않는다. 미실장/실장 미확정 divider는 계산값 없이 UNKNOWN이다. 입력 Net 이름을 출력 목표·실제 전압으로 사용하지 않는다.

| Status | 의미 |
| --- | --- |
| PASS | 명시한 확인 범위의 알려진 사실이 조건 충족 |
| FAIL | 확인 가능한 사실이 명시 조건 위반 |
| UNKNOWN | 누락/모호/충돌/미확인 실장·MPN 때문에 판정 불가 |
| NOT_APPLICABLE | 명시적 적용 제외, 다른 Part Name, 대상 DNP |
| ERROR | 엔진 실행 예외; 내부 예외 원문은 응답하지 않음 |

TERM은 의도된 종단일 수 있어 오류로 단정하지 않는다. PIN_CONNECTED에서 유효한 실장 연결을 확인할 수 없으면 UNKNOWN이다. 없는 대상/핀, 모호 연결, 누락 속성은 FAIL로 바꾸지 않는다. 필요한 expected/tolerance가 없으면 nominal 계산을 반환해도 UNKNOWN이다. DC Bias·온도·실제 전압·스위칭 동작 등 전체 전원 적합성은 판정하지 않는다.

## 결과와 저장

AuditExecution: id/designId/designVersion/snapshotHash/ruleSnapshot/results/executedAt/timingMs.
AuditResult: id/ruleId/ruleVersion/reference/status/measured/expected/unit/reason/missingInputs/evidence/executedAt. measured는 JSON decimal string으로 브라우저 계산 손실을 피한다. 클라이언트는 수치 판정을 수행하지 않는다.
Evidence: datasheet/condition, 실제 components/specifications/raw BOM row, PinContext와 QCV 행·Net, 관련 ImportIssue, 공식/input/비교·공차·계산 과정의 문자열 Map이다. 원문·이슈에는 민감한 설계 정보가 있으므로 로컬에서만 조회한다.

Flyway V4는 audit_execution/audit_result만 추가한다. V1~V3는 수정하지 않는다. design FK, 실행/설계 복합 FK, 실행별 Rule unique, 이력·결과 인덱스를 사용한다. MySQL JSON 숫자 손실을 피하기 위해 payload/metadata에 `{ "json": "lossless typed JSON" }` envelope를 저장하며 상태/버전/ID는 별도 컬럼이다. 전체 recursive equality/정밀 decimal/시간/source/hash, SQL unique 실패 롤백 및 다른 설계 FK를 실제 MySQL로 검증했다. Rule Snapshot과 Evidence는 이후 Rule 변경과 분리하여 보존한다. Snapshot 해시는 정렬된 설계 모델의 SHA256이고 변경된 모델의 판단 재사용 근거로 사용하면 안 된다.

## 운영 전 경계

기본 서버127.0.0.1, local-poc/loopback/로컬 UI Origin만 허용한다. prod/production 또는 enterprise 미설정 모드는403이다. 이는 인증·사용자/설계 소유권이 아니다. SSO, 조직 권한, 원문 열람 정책, 프록시 배포 정책은 BLOCKED다. 공개 인터넷이나 공개 reverse proxy에 노출하지 않는다. 실제 삼성 QCV 변형과 실제 Stage 01 적용성·Rule 계약은 별도 검증이 필요하다.
