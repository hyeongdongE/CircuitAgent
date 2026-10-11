공개 PADS QCV와 대응 BOM XLSX를 기존 Stage 02 파이프라인에 연결합니다. Sparrow 3개 Revision R3 설계를 Java로 파싱하고 Component/Pin/Net의 출처·미실장·종단 상태를 유지한 채 실제 MySQL 저장·복원과 REST 업로드/조회를 검증합니다. 기존 KiCad 및 CSV/XLSX/XLS 계약을 유지합니다.

- PR #1 최신 HEAD를 `10a3636e3988a9ba8bae4bcc1e3c2cc040348ff2`로 확인하고 기존 51개 테스트/JAR를 먼저 검증했습니다.
- 독립 Java QCV adapter: 관측된 NET/PIN 문법, UTF-8, 원문 행, 중복·모호 연결 검증. 별도 PIN과 `(by TERM)`을 Net 연결과 구분합니다.
- Commons CSV/POI 변환은 세미콜론 원본의 모든 문자열·공백·빈 셀을 XLSX에 보존하고 다시 열어 비교합니다. RefDes/QtyOnBoard/NoMount, 내부 PartNumber+Rev, 2n2/3V6/2,5를 지원합니다. 누락 값/MPN을 추측하지 않습니다.
- 기본 DNP 제외 탐색을 유지하고 명시적인 INCLUDE_DNP_SCHEMATIC 모드와 근거 edge를 추가합니다. 부품 경유가 Net 병합/도통 판정은 아닙니다.
- Flyway V3는 pin disposition/source JSON을 additive 추가합니다. 모델/원본 트랜잭션과 FK/버전 격리를 유지하며 Hibernate/JDBC batch 저장을 적용했습니다.
- 실제 FE: BOM/매칭445, NET refs445/QCV 전체453(PCB1~8 포함), Net335, 연결1500, 별도 PIN143 중TERM135, NoMount51. N-Top: BOM296/Net233/NM67. FE N-Top: BOM161/Net193/NM37. 세 보드 BOM ref 전체 매칭.
- 원본6파일 checksum/pinned commit과 다운로드 절차를 기록합니다. 명시적 라이선스가 없어 원본/파생 XLSX/참고 Python은 `.local/`에서만 사용하고 PR에 재배포하지 않습니다. 외부 코드 복제/새 Python 서비스/새 라이브러리 없음.

재현(Java21 JDK와 Docker):

```sh
python3 scripts/fetch-qcv-fixtures.py
cd backend
./gradlew convertQcvBoms
./gradlew clean test bootJar
```

검증: `clean test bootJar` 성공, **80 PASS (기존51 + 추가29), failure/error/skipped0**, 실제 MySQL8.0.43/JAR 성공. 최종 FE 3회 중앙값(ms)은 BOM64.890/QCV18.740/통합4.577/Graph1.887/MySQL1560.244/API379.926입니다. 전체 수량 및 성능은 [PoC 검증](https://github.com/hyeongdongE/CircuitAgent/blob/feat/stage02-design-modeling/docs/qcv-poc-validation.md)과 [테스트 집계](https://github.com/hyeongdongE/CircuitAgent/blob/feat/stage02-design-modeling/docs/qcv-poc-test-results.json)에 기록합니다.

[Stage 03 계약](https://github.com/hyeongdongE/CircuitAgent/blob/feat/stage02-design-modeling/docs/stage03-interface.md) · [단계 기록](https://github.com/hyeongdongE/CircuitAgent/blob/feat/stage02-design-modeling/docs/stage02-progress.md)

한계: 공개 PADS dialect의 검증이며 삼성 QCV 전체 변형 호환은 미검증입니다. 계층별 반복 local Ref/Net scope, 다른 인코딩·핀 alias·줄바꿈 레코드는 실제 삼성 샘플이 필요합니다. 파일 두 개가 같은 revision인지 자동 증명할 수 없습니다. API 인증/설계 소유권/원문 정책이 없어 신뢰된 PoC 환경용이며 공개 배포하지 않았습니다. React 프로젝트가 없어 API 검증·예시를 제공했습니다. Stage 01/03 검증 엔진/04는 변경하지 않습니다.

작업 브랜치 `feat/stage02-design-modeling`, base `main`. 기존 PR을 업데이트하며 기본 브랜치에 직접 병합하지 않습니다.
