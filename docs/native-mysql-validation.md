# Docker 없는 MySQL 검증

2026-10-12, Python3.12.14/Linux에서 공식 MySQL8.0.43 최소 tar 배포를 직접 설치하고 일반 mysqld 프로세스로 실행했다. Docker/Testcontainers를 실행하거나 접속하지 않았다. Linux 런타임의 libaio는 테스트 환경에 별도로 준비했으며 이 실행용 서버/라이브러리는 프로젝트나 ZIP에 포함하지 않는다. Windows 서비스 설치 설명은 제공하지만 Windows에서 직접 실행한 결과로 주장하지 않는다.

| 항목 | 실제 결과 |
| --- | --- |
| 전체 pytest | 170 PASS / FAIL0 / ERROR0 / SKIP0 |
| Chromium 실제 UI | 2 PASS / FAIL0 / ERROR0 / SKIP0 |
| MySQL 저장/복원/API/FK/롤백/설계 격리 | PASS, 기존28개 DB/API 사례 유지 |
| 신규 전용 계정 설정 검증 | 8 PASS |
| 테스트 임시 DB 정리 | 종료 후 circuitagent_test_ 접두사 스키마가 남지 않음 |
| 테스트 계정의 앱 DB 접근 | MySQL1044로 거절됨 |
| lint / wheel / sdist / 설치 후 smoke | PASS |

기존 CSV/QCV/KiCad/Excel/Rule 검증을 유지한다. 실제 FE는 BOM445/전체Component453/Net335/Connection1500/TERM135/Issue80이며 기본 Audit은 PASS1/UNKNOWN5다. 브라우저 테스트는 실제 CSV 업로드→저장→U16 Pin5/U16_EN→Rule 실행→저장된 이력 재조회다. 단위 테스트로는 필수 TEST_DB_* 미설정, 앱 DB_* 상속 방지, 특수문자 비밀번호, 잘못된 포트를 확인했다.

정확한 소스 커밋·시간·빌드 해시는 [native-mysql-test-results.json](native-mysql-test-results.json), 실제 측정 중앙값/평균은 [python-performance.json](python-performance.json)에 기록한다. 최초 Python162개 검증 JSON은 이전 Docker 환경의 역사적 기록으로 유지하며 이번 실행 수와 합산하지 않는다. Starlette/httpx 써드파티 deprecation 경고1개가 발생했으며 실패 또는 skip은 없다.

재현 절차는 [native-mysql.md](native-mysql.md)와 README를 따른다. 기존 V1~V4 SQL/API/회로 모델은 변경하지 않았다. 직접 설치 MySQL 전환으로 삼성 QCV/Stage01/SSO 미지원 범위가 바뀌지는 않는다.
