# Stage 03 회로 조회 계약

Stage 02는 Rule을 읽거나 FB 저항쌍/전압/도통을 판정하지 않는다.
Stage 03은 `DesignQueryService`를 주입하고 한 검증 작업에 하나의 불변 snapshot을
사용한다. 개별 designId 메서드도 제공하지만 매번 DB를 복원하므로 연속 탐색에는
snapshot이 적합하다. 현재는 전체 설계를 메모리에 복원하는 PoC이며 대규모 설계의
조회·메모리 성능은 실제 QCV 데이터로 별도 측정해야 한다.

```java
var graph = designQueryService.snapshot(designId);
var component = graph.getComponent(reference);
var candidates = graph.getPinsByName(reference, requestedPinName);
// 핀 이름은 여러 핀 번호에 대응할 수 있다. 호출자가 후보/이슈를 해석한다.
for (var pin : candidates) {
    var context = graph.getPinContext(reference, pin.key().number());
    if (context.status() == CircuitGraph.ConnectionStatus.CONNECTED) {
        var sameNetPins = graph.getConnectedPins(context.net().id());
        var sameNetParts = graph.getComponentsOnNet(context.net().id());
        var specification = graph.getComponentSpecification(reference);
    }
}
var neighborhood = graph.explore(startPin, explicitlyAllowedReferences, 4);
```

| 인터페이스 | 반환/주의 |
| --- | --- |
| getComponent(ref) | ComponentInstance: value, specification ID, rawSources, assemblyStatus |
| getComponentPins(ref) | 알려진 핀 전체. 빈 목록은 실제 핀이 없다는 보장이 아님 |
| getPinsByName(ref, name) | 정확한 이름과 일치하는 모든 핀; 없으면 빈 목록, 임의 FB 식별 없음 |
| getNetOfPin(ref, number) | Optional Net; 모르는 핀은 기존 계약대로 예외. 상태 구분은 context 사용 |
| getConnectedPins(netId) | 같은 Net의 모든 ComponentPin |
| getComponentsOnNet(netId) | 중복 제거한 ComponentInstance |
| getComponentAttributes(ref) | 동적 속성 Map. absent와 numeric 0 구분, raw와 normalized/unit 제공 |
| getComponentSpecification(ref) | manufacturer/MPN/type + 동적 속성. 누락은 null |
| getComponentIssues(ref) | 해당 Reference 및 설계 전역 ImportIssue; 충돌값을 정상 사양으로 간주하지 말 것 |
| getPinContext(ref, number) | pin/component/specification/Net/issues + 연결 상태 |
| explore(start, allow-list, maxSteps) | 방문 핀·Net 및 무방향 NET/COMPONENT edge 집합. maxSteps 0~32, 10,000 pins/50,000 edges 초과 시 거절 |

연결 상태:
- CONNECTED: 하나의 유효 Net 연결.
- UNCONNECTED: library에 핀 목록이 있고 해당 핀에 연결 없음.
- AMBIGUOUS: 복수 Net 배정으로 연결 제외; ImportIssue 확인.
- UNRESOLVED: 연결을 찾지 못했고 완전한 library 핀 목록도 확인되지 않음.
- MISSING_PIN: 해당 핀이 모델에 없음. 없는 Component는 404/예외.

Library 핀이 누락된 Netlist로부터 생략된 미연결 핀을 추측하지 않는다.
핀 이름·Type·수치는 원문/정규화 값이며 Net 이름으로 전압을 생성하지 않는다.

DNP는 ComponentInstance의 DNP/FITTED/UNSPECIFIED이다. 빈 DNP 칸은 UNSPECIFIED,
명시적 yes/true/1/x/DNP/DNF는 DNP, no/false/0는 FITTED로 처리한다. 알 수 없는 표기는
UNSPECIFIED + ImportIssue. 표시 값/부품 MPN이 같아도 placement는 공유 사양과 분리한다.
DNP의 Netlist topology는 원문대로 유지하되 explore에서 부품을 경유하지 않는다.
UNSPECIFIED의 실장 여부·신호 방향·스위칭 상태는 Stage 03이 별도 정책으로 해석한다.

`explore`는 지정한 부품의 핀 사이를 topology edge로 탐색하며 Net을 합치지 않는다.
Net 연결과 부품 경유는 서로 다른 edge 종류다. 복수 경로/순환 구조의 edge를 반환하지만
모든 simple path를 열거하거나 전기적 동작을 판정하지 않는다. 허용 부품 목록을 지정하기
전에 DNP/누락/충돌/핀 기능과 Rule의 의미를 Stage 03에서 검토해야 한다.

REST의 기존 route/필드는 유지한다. 추가:
`GET /api/designs/{id}/components/{ref}/pins/{number}` → PinContext.
ComponentView/CircuitDesign.components의 `assemblyStatus`는 추가 JSON 필드다.
새 context에서 존재하지 않는 pin은 200/MISSING_PIN, 없는 design/component는 404.
기존 getNetOfPin 및 부품 조회의 누락 처리 계약은 그대로다.
React나 외부 Stage 03 소비 코드는 현재 저장소에 없어 strict JSON DTO 소비자는 확인 필요.

## 배포 및 QCV 경계

현재 API에는 인증/설계 소유권 검사 없음. 설계 ID 필터와 DB FK는 데이터 무결성이지
사용자 접근 통제가 아니다. 사내 SSO·사용자/조직별 설계 권한·원문 열람 정책이 확정되기
전까지 신뢰된 로컬/격리된 PoC 환경에서만 사용한다. 전체 모델과 Component 응답은
BOM 원문 및 Netlist 내부 source 경로를 포함한다. 민감 원본을 공개 환경에 올리지 않는다.
업로드 파일 경로는 basename으로 제한하고 입력 실패 응답은 내부 예외를 내보내지 않는다.

실제 QCV 미지원(422). 공통 NetlistParser/IR은 재사용 가능하나 QCV의 계층별 반복 Ref,
핀 alias/논리·물리 번호, 전원 scope, 미연결 표기를 실제 원본/명세로 확인해야 한다.

V1에서 이미 저장된 Component의 실장 상태는 마이그레이션 후 UNSPECIFIED이다.
V1 원문·연결은 보존하지만 과거 DNP를 자동 추측해 backfill하지 않는다. 실장 상태가
필요한 과거 설계는 보관 원본으로 새 버전을 import하거나 검증된 변환 절차를 적용한다.
V1에서 이미 소속 관계가 사라진 미참조 specification은 V2가 소속을 복구할 수 없다.
현재 신규 저장 모델의 모든 사양은 design-spec 링크를 통해 보존된다.
