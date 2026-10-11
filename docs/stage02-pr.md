# PR 준비 설명 (원격 저장소 미생성)

제목: Stage 02 회로 모델링 PoC: 실제 BOM/KiCad 입력, 연결 조회 및 MySQL 저장

BOM에 묶인 Reference와 KiCad Netlist를 공통 Component/Pin/Net 모델로 통합하고,
BOM 속성과 연결 관계를 함께 조회할 수 있게 합니다. CSV/XLSX/XLS 파서는 원문을
보존하며 R1의 `1k`를 1000 Ω으로 정규화하고 누락 속성을 0으로 대체하지 않습니다.

설계 버전, 공유 사양, 핀/Net/연결, ImportIssue, 원본 파일과 체크섬을 MySQL에
트랜잭션으로 저장합니다. Flyway가 schema를 관리하며 REST multipart import와
설계/Component/Pin/Net/Issue 조회를 제공합니다. Reference 불일치와 모호한 연결은
이슈로 남기고 저항 양쪽의 Net을 자동 병합하지 않습니다.

검증: Java 21, `./gradlew clean test bootJar --no-daemon` 성공; 27개 테스트 전부
PASS, failures/errors/skipped 0. Docker/Testcontainers 실제 MySQL 8.0.43에서
저장·복원, 원본 바이트, 버전 격리, 롤백 및 API 조회를 검증했습니다. 실제 5V 회로는
BOM/Netlist 각각 12개 모두 매칭, Pin 25 / Net 6 / 연결 25입니다. 두 번째 실제
Shift Indicator는 BOM 27개 모두 Netlist에 있고 추가 H1~H4를 이슈로 기록합니다.
원본 자료는 커밋과 SHA-256으로 고정했습니다.

QCV Parser는 실제 샘플/명세가 없어 미구현이며 API는 422로 명시적으로 거절합니다.
KiCad 검증을 삼성 QCV 지원 완료로 간주하지 않습니다. React 및 Stage 01/03/04는
현재 범위 밖입니다. QCV의 계층/전원/미연결 핀 규칙은 실제 자료 확보 후 구현합니다.

원격 GitHub 저장소 생성 권한이 없어 PR을 아직 생성하지 못했습니다.
작업 브랜치: `feat/stage02-design-modeling`. 이 파일은 원격 생성 이후 PR 본문으로
사용할 수 있습니다. 기본 브랜치 직접 병합은 수행하지 않았습니다.
