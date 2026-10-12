# Stage 03 회로 조회 계약

Stage 02는 Rule을 읽거나 FB 저항쌍/전압/도통을 판정하지 않는다.
Stage 03은 `DesignQueryService`를 주입하고 한 검증 작업에 하나의 불변 snapshot을
사용한다. 개별 designId 메서드도 제공하지만 매번 DB를 복원하므로 연속 탐색에는
snapshot이 적합하다. 현재는 전체 설계를 메모리에 복원하는 PoC이며 대규모 설계의
공개 FE QCV 성능은 qcv-poc-validation.md에 기록한다. 실제 삼성 규모는 추가 측정이 필요하다.

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
- TERMINATED: QCV `(by TERM)` 명시적 종단, 오류나 Net 연결을 생성하지 않음.
- UNCONNECTED: 명시적 QCV 미연결 선언 또는 library inventory에 연결 없는 핀.
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
새 React PoC는 추가 조회 필드를 사용한다. 외부 팀원 소비 코드의 strict JSON DTO는 연결 시 확인이 필요하다.

## 배포 및 QCV 경계

현재 API에는 인증/설계 소유권 검사 없음. 설계 ID 필터와 DB FK는 데이터 무결성이지
사용자 접근 통제가 아니다. 사내 SSO·사용자/조직별 설계 권한·원문 열람 정책이 확정되기
전까지 신뢰된 로컬/격리된 PoC 환경에서만 사용한다. 전체 모델과 Component 응답은
BOM 원문 및 Netlist 내부 source 경로를 포함한다. 민감 원본을 공개 환경에 올리지 않는다.
업로드 파일 경로는 basename으로 제한하고 입력 실패 응답은 내부 예외를 내보내지 않는다.

공개 PADS NET/PIN QCV는 지원한다. 삼성 QCV의 계층별 반복 Ref,
핀 alias/논리·물리 번호, 전원 scope, 미연결 표기를 실제 원본/명세로 확인해야 한다.

V1에서 이미 저장된 Component의 실장 상태는 마이그레이션 후 UNSPECIFIED이다.
V1 원문·연결은 보존하지만 과거 DNP를 자동 추측해 backfill하지 않는다. 실장 상태가
필요한 과거 설계는 보관 원본으로 새 버전을 import하거나 검증된 변환 절차를 적용한다.
V1에서 이미 소속 관계가 사라진 미참조 specification은 V2가 소속을 복구할 수 없다.
현재 신규 저장 모델의 모든 사양은 design-spec 링크를 통해 보존된다.

## 공개 QCV를 이용한 연동 예

QCV에는 핀 이름/기능이 없어 `getPinsByName("U16", "FB")`를 임의로 채우지 않습니다.
Stage 03이 검증된 데이터시트 pin mapping을 사용해 핀 번호를 선택해야 합니다.

```java
var graph = designQueryService.snapshot(designId);
var u16 = graph.getComponent("U16");
var en = graph.getPinContext("U16", "5"); // 실제 source label: U16_EN
var pins = graph.getConnectedPins(en.net().id()); // R107-2, R83-2, U16-5
var r107 = graph.getComponentSpecification("R107");
var otherNet = graph.getNetOfPin("R107", "1"); // VDD
var evidence = graph.explore(new PinKey("U16", "5"), Set.of("R107", "R83"), 3,
    CircuitGraph.TraversalMode.INCLUDE_DNP_SCHEMATIC);
```

R107/R83는 원본에서 NoMount입니다. 기존 explore 기본은 DNP 부품 경유를 제외합니다.
명시적인 INCLUDE_DNP_SCHEMATIC은 실장/도통 판단 없이 논리 topology와 출처만 반환합니다.
반환 Neighborhood의 NET/COMPONENT edge 및 sourcePath, pin.sourceMetadata를 근거로 사용합니다.
R107=140000 Ω, R83=56000 Ω이며 이를 상부/하부 저항으로 의미 분류하지 않습니다.

RefDes/QtyOnBoard 카탈로그 BOM의 PartNumber는 내부 Part Number 동적 속성입니다.
U16의 Part Name은 TPS54302DDC, 내부 Part Number는 EGP10001429이고 MPN/Value는 null입니다.
Stage 03은 내부 PartNumber나 Part Name을 검증되지 않은 MPN으로 취급하지 않습니다.
이 양식의 NoMount NM=DNP, 빈 NoMount=FITTED이며 generic DNP 빈 값 계약은 유지합니다.
Voltage는 Voltage 속성으로 보존하고 임의 Voltage Rating으로 바꾸지 않습니다.

실제 요청 순서(환경의 import 반환 ID를 사용):

```sh
curl http://localhost:8080/api/designs/$DESIGN_ID/components/U16
curl http://localhost:8080/api/designs/$DESIGN_ID/components/U16/pins/5
curl http://localhost:8080/api/designs/$DESIGN_ID/components/R107
curl http://localhost:8080/api/designs/$DESIGN_ID/nets
curl http://localhost:8080/api/designs/$DESIGN_ID/issues
```

pin context 응답은 status=CONNECTED, net.name=U16_EN과 component/specification/pin/issues를
포함합니다. U29-5는 status=TERMINATED/net=null이고 없는 pin은 MISSING_PIN입니다.
NetView의 connectedPins는 reference/number 쌍입니다. Net ID는 응답 그대로 다음 조회에 사용합니다.
HTTP는 기존 net/component/pin 조회로 인접 부품을 탐색하며 제한적 다단계 탐색은 Java snapshot
계약입니다. 신호 방향·FB/EN 기능 해석·PASS/FAIL은 Stage 03의 책임입니다.

V3 migration은 기존 핀을 UNSPECIFIED/빈 metadata로 보존합니다. 새 import는 disposition과
원문 행 metadata를 MySQL JSON에 저장/복원합니다.

## 정형 Audit PoC 추가

Stage02 모델/파서/기존 REST는 보존한다. 새 `CircuitGraph.designSnapshot()`으로 불변 design/version/hash 입력을 조회한다. Rule 실행당 snapshot1회 정책 및 결과·근거·API는 [Stage03 통합 계약](stage03-integration.md)에 있다. 별도 audit.poc는 실제 Stage01/팀원 Rule Parser를 대체하지 않는다.
