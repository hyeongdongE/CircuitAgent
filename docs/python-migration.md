# Python 전환 및 호환 경계

기준: PR1·PR2 병합 main `1a89f39936f6d07188b521c32d4f4f1d18767b4b`. Java103/React unit6/browser2는 이전 구현의 실행 기록이며 Python 테스트 수에 합산하지 않는다. 전환 브랜치 `feat/python-migration`에서 구현하고 main에는 직접 반영하지 않는다.

## 이전 → 현재

| 기능 | Python 구현 |
| --- | --- |
| Java record + Map.copyOf | Pydantic frozen 모델 + 중첩 MappingProxyType/tuple |
| Apache POI / Commons CSV | openpyxl / xlrd / 표준 csv |
| KiCad / QCV adapter | NetlistParser Protocol + 두 독립 어댑터 |
| BigDecimal DECIMAL128 | Decimal localcontext(prec=34) |
| JPA Entity / Repository | SQLAlchemy 기존 MySQL 테이블 reflection + 트랜잭션 |
| Spring Boot REST / 로컬 필터 | FastAPI / ASGI body·주소·Origin 검사 |
| React / TypeScript | Python Streamlit 화면, 같은 REST 데이터 사용 |
| JUnit / Java Testcontainers | pytest / Python Testcontainers / MySQL8.0.43 |
| Node Playwright | Python Playwright Chromium |
| Gradle / bootJar | pyproject.toml / 고정 requirements.lock / Python wheel |

SQL·JSON·원본 회로 자료는 언어 변환 대상이 아니며 그대로 보존한다. 과거 Java/React 소스와 단계별 보고서는 Git 기준 커밋에서 조회할 수 있다.

## MySQL V1~V4 인계

1. 원본 SQL 4개를 byte 변경 없이 Python 패키지 resources로 옮긴다.
2. 새 MySQL DB는 해당 SQL을 순서대로 적용한다.
3. 기존 Flyway DB는 성공 이력과 script line CRC32를 대조하고 `python_schema_history`에 SHA256 인계 이력을 기록한다.
4. 기존 Flyway 이력, 설계, 원본 bytes, PartSpecification, Pin/Net, Audit 값은 수정하지 않는다. UUID 저장은 BINARY(16), JSON은 기존 컬럼을 사용한다.
5. Python 적용 이력의 SHA 변경, 미확인 Flyway 버전, 실패 이력, 이력 없는 기존 DB는 자동 추측/초기화하지 않는다.
6. 수동 실행: `python -m circuitagent.database migrate`. API 시작 때도 동일 검사를 수행한다. Java와 Python을 동시에 같은 DB의 migration 실행자로 사용하지 않는다.

인계 전 DB 백업을 준비한다. MySQL DDL은 implicit commit이므로 schema 변경 중 실패하면 부분 적용을 조사한 뒤 복구해야 한다. 도메인 import와 Audit 저장은 전체 트랜잭션이며 실패 시 롤백한다. 다른 버전/테이블을 이미 팀원이 추가했다면 이 계약을 검토한 뒤 전환한다.

## 유지되는 사실과 변경 사항

- 부품은 Reference로만 매칭하며 BOM-only/QCV-only/DNP를 삭제하지 않는다.
- 동일 Net 연결은 무방향이다. 저항·커패시터·0Ω을 통해 Net을 자동 병합하지 않는다.
- 핵심 속성과 동적 Map을 유지한다. 누락은 None, 실제 0은 Decimal(0)이다.
- REST의 경로·camelCase 필드는 유지한다. 정규 숫자는 정밀도 보존을 위해 문자열로 반환한다. `measured`도 문자열이며 호출자가 float로 재계산하지 않는다.
- 기존 Java Audit의 JSON string envelope와 timestamp 9자리 nanos를 그대로 읽는다. 기존 저장 hash는 변경하지 않는다. 새 Python Snapshot hash는 Decimal 문자열을 사용하는 정규 JSON SHA256이므로 Java에서 다시 계산한 hash와 동일하다고 가정하지 않는다.
- Rule 판정은 PASS/FAIL/UNKNOWN/NOT_APPLICABLE/ERROR이다. MPN 문자열만으로 신원 검증을 확정하지 않는다.
- Stage01 Parser 교체 경계는 RuleInputAdapter로 유지한다. 자연어/사용자 Python 코드/eval은 실행하지 않는다.
- 공개 QCV 문법은 UTF-8 NET/PIN/TERM과 원본 행 추적이다. 삼성 내부 형식 전체 지원을 주장하지 않는다.
- Python Excel 수식 재계산은 지원하지 않는다. XLSX 수식은 명시 거절한다. XLS는 xlrd가 제공하는 저장된 값으로 읽으며 재계산하지 않는다. 스타일 기반 표시값과 POI 표시값이 완전히 같다고 보장하지 않으며, 회로 BOM은 명시 문자열/값으로 준비한다.
- 화면 port는 5173 → 8501이며 서버 포트는 8080을 유지한다. 기업 인증·설계별 사용자 권한은 추가 명세가 필요하다.

## 기존 테스트 범위 대응

| 기존 Java 검증 범위 | Python 검증 |
| --- | --- |
| 실제 Fixture·Domain·BOM·단위·DNP | test_model_parsers.py, 원본 해시/빈 값/그룹/중복/두 Excel 형식 |
| KiCad·QCV·라이브러리 inventory·계층 | 실제 5V/Shift/Sparrow3보드, alias/빈 Pin 이름/미연결/모호 연결 |
| Graph·Stage03 Snapshot | 실제 U16 탐색, DNP 경유 제외, 불변성, 순서 독립 hash |
| Rule·Datasheet·다섯 결과 상태 | test_audit.py, 기존 공식 JSON, 실제 PASS1/UNKNOWN5, 별도 자체 범위·분압 Fixture |
| MySQL·migration·FK·롤백·격리 | test_mysql_api.py + 실제 기존 Java MySQL 인계 확인 |
| REST·잘못된 입력·404·409·용량 | 실제 multipart/조회/Audit/계층형 Reference/JSON stream 한도 |
| React / 브라우저 | test_web.py의 Streamlit 검사, browser_tests의 실제 upload→저장→재조회 |

Java 테스트 파일을 Python 테스트로 그대로 복사하지 않으며 실행된 Python 사례 수와 미검증 범위를 별도로 기록한다.
