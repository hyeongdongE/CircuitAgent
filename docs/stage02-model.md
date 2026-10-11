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

PR #1 리뷰 개선: ComponentInstance의 assemblyStatus는 인스턴스별
DNP/FITTED/UNSPECIFIED이며 공유 PartSpecification의 동적 속성과 분리된다.
원문 BOM 행은 그대로 유지하고 rawSources.provenance.bomLocation에 행 위치를 보존한다.
Netlist rawSources의 pinInventory는 library에서 알려진 완전한 핀 목록 여부를 나타내는
파생 메타데이터다. MPN 사양의 numeric 속성은 SI 의미로 공유하므로 사양 AttributeValue.raw는
첫 사양 원문이며 개별 부품 원문은 해당 ComponentInstance.rawSources.bom을 사용한다.
공통 조회/연결 상태 및 탐색의 의미는 [Stage 03 계약](stage03-interface.md)을 참고한다.

공개 QCV 확장: ComponentPin의 disposition(UNSPECIFIED/UNCONNECTED/TERMINATED)과
sourceMetadata는 연결정보와 독립된 선언/근거입니다. `(by TERM)` PIN은 실제 Pin을 보존하되
PinConnection/가짜 Net을 만들지 않습니다. NET/PIN에 등장한 모든 부품을 유지하므로 BOM
445개를 매칭한 FE 모델에는 PIN-only PCB8개까지 453개 Component가 있습니다.
QCV에는 library 전체 핀/핀 기능/Value가 없어 임의로 채우지 않습니다.
카탈로그 BOM의 PartNumber는 내부 Part Number 속성 및 Rev 기반 사양 재사용 키이고,
제조사 MPN은 별도 명시 데이터가 없으면 null입니다. NoMount는 Component의 실장 상태입니다.
V3는 핀 선언과 source JSON을 저장하고 기존 V1/V2 모델/관계는 유지합니다.
