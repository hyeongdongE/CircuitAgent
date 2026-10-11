BOM과 KiCad Netlist를 공통 Component/Pin/Net 모델로 통합하고 부품 사양과 연결을 함께 조회할 수 있게 합니다. 실제 공개 5V 전원 회로 및 Shift Indicator 원본을 커밋/체크섬으로 고정해 CSV/XLSX/XLS, 구조적 S-expression 파싱, MySQL 저장·복원, multipart REST API를 검증했습니다.

PR #1의 기존 HEAD `85b5b9d410fcb1df11c77ce8806dfe8eacd40586`에서 기존 27개 테스트를 재실행한 뒤 리뷰 결과에 따라 필요한 부분을 보완했습니다.

- DNP를 인스턴스 실장 상태로 분리하고, 동일 MPN/동등한 SI 값은 사양을 재사용합니다. 누락은 null/미등록으로 유지하고 실제 속성 충돌은 ImportIssue로 남깁니다.
- 같은 code/name의 중복 Net 선언은 연결을 보존해 병합하고 이름 충돌은 거절합니다. library alias·계층 source·미연결 핀을 보존하며 부품 양쪽 Net은 합치지 않습니다.
- Stage 03용 design-scoped snapshot, 핀 이름 조회, 연결 상태/이슈, 명시적 경유 부품 목록을 사용하는 제한 탐색을 제공합니다. Rule 해석/FB 저항쌍/출력 전압 계산은 구현하지 않습니다.
- Flyway V1을 유지하고 V2에 실장 상태, 전체 설계-specification 소속, 교차 설계 Pin/Net 연결을 막는 복합 FK를 추가합니다.
- 파일 형식/확장자, 입력 크기·셀·token·숫자 범위 및 XLSX 압축 해제 크기를 검사하고 파싱 실패를 400으로 처리합니다. 기존 REST route/필드는 유지하고 assemblyStatus 및 pin-context 조회만 추가합니다.

검증: Java 21, `cd backend && ./gradlew clean test bootJar --no-daemon` 성공. **51개 PASS, failure/error/skipped 0** (기존 27 + 추가 24). Docker/Testcontainers 실제 MySQL 8.0.43로 roundtrip, 롤백, 설계별 값 격리, 교차 설계 FK 거절, 데이터가 있는 V1→V2 마이그레이션 및 API를 검증했습니다. JAR 빌드 성공. 공개 Fixture/V1 schema 원문 변경 없음.

5V: BOM/Netlist/Reference 매칭 12, R1=1000Ω, U1=L7805, 25 pins/6 nets/25 connections. Shift: BOM 27개 전부 Netlist에 매칭, Netlist 31개와 추가 H1~H4 이슈 확인.

미해결: **실제 QCV 샘플/명세가 없어 QCV 파서는 미구현(422)**. 삼성 QCV 성공 파싱 및 실제 삼성 회로 Audit 테스트는 미실행입니다. API에는 아직 인증/설계별 사용자 권한 검사가 없으며 원문/source 정보가 포함됩니다. 사내 SSO/접근 정책 확정 전에는 신뢰된 PoC 환경에서만 사용해야 합니다. 기존 V1 실장 상태는 UNSPECIFIED로 시작하며 필요한 과거 설계는 원본을 재import/검증해 변환해야 합니다. 외부 Stage 03 DTO 호환과 대규모 성능도 실제 입력/소비 코드 확보 후 검증해야 합니다.

[순차 리뷰·문제 분류 및 조치](https://github.com/hyeongdongE/CircuitAgent/blob/feat/stage02-design-modeling/docs/stage02-review.md) · [테스트 집계](https://github.com/hyeongdongE/CircuitAgent/blob/feat/stage02-design-modeling/docs/stage02-review-test-results.json) · [Stage 03 조회 계약](https://github.com/hyeongdongE/CircuitAgent/blob/feat/stage02-design-modeling/docs/stage03-interface.md) · [QCV 입력 요구사항](https://github.com/hyeongdongE/CircuitAgent/blob/feat/stage02-design-modeling/docs/stage02-qcv.md)

작업 브랜치 `feat/stage02-design-modeling`, 대상 `main`. 기본 브랜치 직접 병합 없음. Stage 01/03 검증 엔진/04/React 기능은 변경하지 않았습니다.
