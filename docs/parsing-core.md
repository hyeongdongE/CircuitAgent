# XLSX와 QCV를 연결하는 코드를 읽는 방법

## 1. cli.py — 전체 호출 흐름

```python
bom = parse_bom(bom_bytes, "BOM.xlsx", mapping)
netlist = QcvNetlistParser().parse(netlist_bytes, "Netlist.qcv")
design = assemble("My Design", "v1", bom, netlist)
graph = CircuitGraph(design)
```

BOM은 부품 속성을, QCV는 배선을 제공한다. 마지막 두 단계가 둘을 연결한다. 행 번호/MPN/부품값으로 연결 대상을 추측하지 않는다.

## 2. models.py — 저장하는 사실

- ComponentInstance: Reference `R107`, 사양UUID, 원본 BOM/QCV 정보, 실장 상태.
- PartSpecification: 제조사/MPN/Type과 attributes Map. Resistance/Voltage 등의 속성은 raw/normalized/unit을 가진다.
- PinKey: Reference+핀 번호. `U16-5`와 `U17-5`는 서로 다른 핀이다.
- ComponentPin: 실제 QCV에서 관측한 핀 및 선언 상태/원본 행. 핀 이름은 QCV에 없어 생성하지 않는다.
- CircuitNet: 원본 Net 이름과 조회용ID. 이름에 전압처럼 보이는 문자열이 있어도 실제 전압을 확정하지 않는다.
- PinConnection: 특정 PinKey가 특정 Net ID에 속한다는 사실.
- ImportIssue: 중복/누락/충돌과 원본 위치. 오류를 숨기며 임의로 배선을 만들지 않는다.

모델은 중첩 dict/list까지 읽기 전용이며 생성 시 참조 무결성을 검사한다.

## 3. bom_parser.py — XLSX 행을 부품으로 만들기

`rows_from_file()`이 시트를 순회하며 Reference 헤더를 찾는다. 헤더 위 설명은 건너뛰고 실제 Excel 시트명·행 번호를 보존한다. `schema()`가 실제 컬럼명을 공통 이름에 연결하고 `row_map()`이 행을 dict로 바꾼다. 정해진 컬럼 번호나 고정 행 수를 사용하지 않는다.

`parse_bom()`은 그룹 Reference를 개별 부품으로 펼친다. `R1,R2` 한 셀은 두 인스턴스가 된다. 핵심 필드 외 컬럼은 동적 속성으로 보존한다. 헤더 없는 추가 셀도 column:N으로 남기고 Issue를 생성한다. 빈 셀은0으로 채우지 않는다.

동일 제조사+MPN 또는 확인된 공개 양식의 내부품번+Rev는 사양 공유 후보다. Type과 전체 속성이 같을 때만 동일 PartSpecification을 재사용한다. 내부 PartNumber/Part Name을 검증된 제조사 MPN으로 승격하지 않는다. R/C/L+숫자는 수동 소자 단위 해석에만 쓰는 통상 Reference 규칙이다.

NoMount=NM과 DNP의 yes/no 등을 해석한다. 생소한 양식의 빈 NoMount는 실장 미확정이다. 공개 Sparrow의 RefDes/QtyOnBoard 양식 또는 명시 noMountBlank 정책만 빈 값을 실장으로 해석한다.

## 4. unit_normalizer.py — 원문을 보존하며 숫자로 변환

`1kΩ`는 raw="1kΩ", normalized=Decimal("1000"), unit="Ω"로 저장한다. `4R7`는4.7Ω, `100nF`는0.0000001F다. float를 거치지 않으며 M(메가)/m(밀리)을 구분한다. 단위가 알려진 속성 또는 명시 units 매핑만 변환하며, 실패하면 원문과 Issue를 유지한다.

## 5. netlist_parser.py — QCV 배선 읽기

실제 공개 원문:

```text
NET : 'U16_EN' R107-2 R83-2 U16-5
PIN : '/U29' U29-5 (by TERM)
```

NET 레코드는 같은 Net에 연결된 핀 집합이다. 첫 줄에서는 R107-2/R83-2/U16-5의 PinKey를 만들고 동일 Net ID로 연결한다. QCV에 COMP 목록이 없어도 관측된 Reference로 부품을 만든다. Net 이름은 원문 그대로 보존하고 SHA256으로 안정적인 조회ID를 만든다.

PIN 레코드는 별도 단자 선언이며 NET 연결로 취급하지 않는다. `(by TERM)`은 의도적 종단 상태다. 섹션에 따라 one-pin Net의 미확정 상태와 명시 미연결 상태를 구분한다. NET/PIN 선언 충돌이나 동일 핀의 복수 Net 연결은 임의 선택하지 않고 연결을 제외하며 Issue를 남긴다.

Reference에 하이픈이 있을 수 있으므로 단순 split("-") 대신 문법 패턴의 마지막 단자 접미부를 사용한다. 핀 번호는 A1도 가능하므로 문자열이다. 각 핀의 source_metadata에 원본 파일명·레코드·행 번호를 기록한다.

## 6. circuit_graph.py — 두 파서 결과를 결합하기

`assemble()`은 BOM 부품을 복사한 뒤 QCV의 같은 Reference에 연결 출처를 붙인다. 값/실장 상태는 BOM에서 가져온다. BOM-only/QCV-only 부품을 삭제하지 않고 각각 Issue로 기록한다.

`CircuitGraph.__init__()`에서 조회 사전4개를 만든다:

| 사전 | 키→값 |
| --- | --- |
| pins | PinKey→ComponentPin |
| by_component | Reference→핀 목록 |
| by_net | Net ID→핀 목록 |
| net_of_pin | PinKey→Net ID |

연결 조회:

```python
component = graph.get_component("U16")
pins = graph.get_component_pins("U16")
net = graph.get_net_of_pin("U16", "5")
connected = graph.get_connected_pins(net.id)
components = graph.get_components_on_net(net.id)
resistance = graph.get_component_attributes("R107")["Resistance"]
```

실제 U16_EN에는 R107-2/R83-2/U16-5가 연결된다. R107의 다른 핀은 별도 Net이다. 저항/커패시터/0Ω의 양단 Net을 자동 합치지 않는다. DNP 부품도 논리적 배선에서 보존하며 실제 도통/분압 기능은 판단하지 않는다.

## 지원 경계

BOM은 XLSX만, Netlist는 QCV만 받는다. QCV는 공개 Sparrow에서 확인한 UTF-8/BOM 인코딩·한 줄 NET/PIN 및 #섹션 제목 문법이다. 임의의 다중 줄 continuation이나 삼성 내부 QCV 변형을 지원한다고 주장하지 않는다. XLSX 수식은 계산하지 않으며 셀 스타일의 표시 문자열도 재현하지 않는다. DB·API·웹·Rule 검증은 이 코어의 책임이 아니다.
