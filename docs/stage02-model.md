# 공통 회로 모델

CircuitDesign은 설계 ID와 버전별로 ComponentInstance, PartSpecification, ComponentPin,
CircuitNet, PinConnection, ImportIssue 및 Source 메타데이터를 소유합니다.

- ComponentInstance: Reference를 키로 사용하고 PartSpecification ID를 참조합니다. 표시 값과 BOM/Netlist 원본 레코드는 인스턴스에 보존합니다.
- PartSpecification: 제조사/MPN/Type 핵심 필드와 동적 AttributeValue Map을 갖습니다. MPN이 명시된 경우 사양을 공유할 수 있습니다.
- AttributeValue: 원문, 정규화된 BigDecimal 및 SI 단위를 보존합니다. 누락된 숫자나 단위는 null/미등록이며 0을 만들지 않습니다.
- ComponentPin: Component Reference + Pin Number 복합 키. Pin Name과 전기적 타입은 별도 선택 필드입니다.
- CircuitNet: 설계 안에서 고유한 ID, 원래 Net 이름과 계층/source 경로. 전원 이름도 일반 Net 이름으로 보존합니다.
- PinConnection: PinKey → Net ID. 핀은 최대 하나의 Net에 연결되고, 미연결 핀은 연결 레코드 없이 존재할 수 있습니다.
- ImportIssue: 코드, 심각도, Reference, 입력 위치, 메시지.

모델 생성자는 관계 무결성, 중복 핀 및 모호한 연결을 검증합니다.
파서는 문제가 있는 입력을 ImportIssue로 기록하고 무효 관계를 모델에 삽입하지 않습니다.
핀 두 개가 같은 부품에 속해 있어도 두 Net은 합치지 않습니다.
모델은 DB와 독립적이며 컬렉션을 방어적으로 복사합니다.
