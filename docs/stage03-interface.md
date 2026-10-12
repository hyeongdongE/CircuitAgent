# Python Stage 03 조회 계약

```python
from circuitagent.database import CircuitStore
from circuitagent.graph import CircuitGraph
from circuitagent.domain import PinKey

# store는 이미 초기화한 MySQL engine으로 생성한다. 검증 실행당 한 번만 복원한다.
graph = CircuitGraph(store.load(design_id))
component = graph.get_component(reference)
specification = graph.get_component_specification(reference)
attributes = graph.get_component_attributes(reference)
pins = graph.get_component_pins(reference)
context = graph.get_pin_context(reference, pin_number)
if context['status'] == 'CONNECTED':
    peers = graph.get_connected_pins(context['net'].id)
    components = graph.get_components_on_net(context['net'].id)
issues = graph.get_component_issues(reference)
```

메서드 목록은 기존 개념을 Python snake_case로 옮긴다. 조회의 불변 CircuitDesign은 graph.design이다. pin name은 실제 원본에 없으면 None/빈 원문이며 FB/EN 이름을 생성하지 않는다. 데이터시트 근거로 지정한 pin number를 사용한다.

PinContext 상태: CONNECTED, UNCONNECTED, TERMINATED, AMBIGUOUS, UNRESOLVED, MISSING_PIN. 컴포넌트 미존재는 LookupError다. 데이터 부족은 Rule의 UNKNOWN 사유로 전달한다. spec.attributes에는 raw/normalized/unit가 있으며 제조사 MPN attestation과 내부 Part Number는 구분한다.

제한 깊이 탐색:

```python
result = graph.explore(PinKey(reference=reference, number=pin_number),
                       max_depth=3, allow_components=[resistor_reference])
```

NET edge는 실제 같은 Net, COMPONENT edge는 허용한 부품 양쪽 핀의 위상 관계다. 기본적으로 DNP는 경유하지 않는다. include_dnp=True는 명시적 논리 탐색이며 실제 도통을 뜻하지 않는다. 경로 source에는 Net ID/Component Reference를 반환한다. 깊이8/허용목록100/방문20000 한도가 있다.

RuleInputAdapter의 정형 poc-rule-v1은 Stage01의 실제 Parser가 확정되면 연결한다. FB/EN/Rule 의미 판단은 Stage03의 책임이다. 지원 연산과 누락/DNP/신원 처리 및 기존 DB는 [전환 문서](python-migration.md), HTTP 계약은 [API](api.md)를 참조한다.

고밀도 Net/부품은 각 한 번만 펼쳐 모든 핀 쌍의 조합을 생성하지 않는다. 반환 edges는 실제 도달 경로의 근거이며 같은 Net의 완전한 핀 목록은 get_connected_pins로 조회한다.
