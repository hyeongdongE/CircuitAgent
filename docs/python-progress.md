# Python 전환 진행 기록

기준 main: `1a89f39`, 작업 브랜치: `feat/python-migration`. 사용자의 "현재 코드를 전부 Python으로" 요청에 따라 Backend와 화면을 전환한다. 화면 범위 확인 질문에 답변이 없어 원래 요청대로 Python Streamlit을 선택했다.

| 단계 | 구현·검증 | 상태 |
| --- | --- | --- |
| 0 | 기존 Java58 클래스·MySQL SQL V1~V4·REST·React·테스트/원본 확인, 별도 브랜치 준비 | PASS |
| 1 | 불변 Pydantic IR·Decimal·CSV/XLSX/XLS·KiCad/QCV·CSV→XLSX, 실제3보드 및5V/Shift 회귀 | PASS |
| 2 | Reference 통합·Pin/Net 인덱스·DNP를 구분한 제한 탐색·다섯 판정·정형 Adapter·Evidence | PASS |
| 3 | SQLAlchemy/MySQL 트랜잭션·FK·원본·Audit envelope·API, 실제 기존 Java DB/Flyway 인계 확인 | PASS |
| 4 | Streamlit 업로드·부품 검색·Pin/Net·검사/이력·오류 처리, 실제 Chromium2개 검증 | PASS |
| 5 | SQL byte 비교, pytest143/실제 browser2, lint/wheel/sdist/설치 검증, 문서·성능·PR/ZIP 준비 | PASS |

## 수정과 중간 실패

- 초기 Decimal 쉼표+SI 접두어 테스트 실패를 수정했다. 천 단위인지 모호한 쉼표는 계속 거절한다.
- XLSX 변환 검증은 모든 셀을 다시 읽어 비교한다. read-only Workbook의 임의 셀 반복 조회로 인한 불필요한 재스캔을 제거했다.
- Testcontainers 기본 MySQLdb driver를 설치된 PyMySQL로 명시했다. Docker 컨테이너 생성60초 timeout이 발생했고 docker_client_kw timeout240으로 환경 여유를 확보했다. 최종 검증은 실제 MySQL8.0.43 컨테이너에서 실행했다.
- 일반 식별자 검사를 모든 name 필드에 적용해 KiCad의 유효한 빈 Pin name을 거절하던 문제를 두 실제 KiCad 회귀 실패로 확인하고 수정했다.
- XLS 여러 시트 generator가 마지막 sheet를 참조하지 않도록 sheet argument를 분리하고 두 Excel 형식 모두 회귀 테스트를 추가했다.
- REST의 계층 Reference에서 slash를 보존하도록 path converter와 구체 Pin 라우트 우선순위를 정리했다.
- root_path가 API 로컬 정책을 우회하지 않게 처리하고, Content-Type을 바꿔도 Audit JSON body 한도를 적용한다.
- Decimal 자체의 과도한 exponent 파싱 실패를 입력 오류/ImportIssue로 처리한다. 내부 exception 문자열을 응답에 노출하지 않는다.
- 임시 Python bytecode는 커밋 전에 제거하고 ignore한다. 기존 Java/React 실행 소스와 오래된 실행 보고서는 Git 기준 버전에 보존하고 현재 Python 트리에서 제거했다.

## 실제 확인

- 기존 Java MySQL DB의6개 설계는 인계 전후 수량이 같고 Flyway version/checksum/success 이력이 같았다. 기존453/335/1500 모델과 이전 PASS1/UNKNOWN5 Audit을 Python으로 복원했다. `2026-10-11T17:08:03.844291087Z` 원문 nanos도 보존했다.
- FE445개 BOM Reference 전부 매칭, 전체453개 부품/335Net/1500연결/별도PIN143/TERM135/NM51/Issue80. NTop296/304/233/972/147/139/67/93, FE-NTop161/169/193/794/25/17/37/129.
- 실제6 Rule PASS1/UNKNOWN5. FB 조건부 nominal11.96158139534883720930232558139535V. C5 nominal100nF만 PASS이다. 출력 정상 동작이나 미실장 EN divider를 PASS로 판단하지 않는다.
- Java의103개 테스트는 이전 기록이며 Python 사례 수와 합산하지 않는다. 최종 실행 수와 source/build/browser 결과는 [python-validation.md](python-validation.md), [집계 JSON](python-test-results.json)에 기록한다.

## 미완료 범위

삼성 내부 QCV 변형, Stage01 실제 Parser 계약, 기업 SSO/설계별 권한 및 운영 배포는 BLOCKED다. Python Excel 수식 재계산은 미지원이며 XLSX 수식 입력은 거절한다. XLS 저장값과 복잡한 표시 형식의 동등성을 가정하지 않는다. Java/Python 정규 hash의 숫자 표현 차이는 전환 문서에 명시한다.

최종 pytest143 PASS/FAIL0/ERROR0/SKIP0, 실제 browser2 PASS, wheel/sdist/설치/lint PASS. 고밀도 자체 Fixture5000개 부품/10000연결에서 Net과 부품을 한 번씩만 펼쳐 quadratic 핀 쌍 생성을 피하면서 도달 관계와0Ω 부품을 유지했다.
