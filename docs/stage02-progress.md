# Stage 02 진행 기록

## STEP 0 — 새 프로젝트 초기화 (진행 중)

- 배경: 기존 저장소가 없음을 사용자에게 확인하고 새 프로젝트 생성 권한을 받음.
- 구현한 기능: Java 21 / Spring Boot 3.5.16 / Gradle 8.14.6 프로젝트, JPA/MySQL 설정, DB를 제외한 부트스트랩 테스트.
- 변경 파일: README.md, .gitignore, backend/build.gradle, settings.gradle, Gradle Wrapper, Application, application.yml, ApplicationBootstrapTest.
- 기존 구조/기능: 기존 소스, Entity, API, Service, Frontend, AGENTS.md, 기존 테스트 없음. Stage 02는 backend/src 아래에 추가 예정.
- 테스트 명령: `cd backend && ./gradlew clean test` (다음 실행 예정).
- 테스트 결과: 대기. 기존 테스트는 없음.
- 미해결 문제: MySQL 저장 검증과 QCV 샘플은 후속 단계에서 확인.
- 다음 단계: 초기 테스트 통과 후 STEP 1 실제 PoC 데이터 확보.
