# Stage 02 진행 기록

## STEP 0 — 완료

- 배경: 기존 저장소가 없음을 사용자에게 확인하고 새 프로젝트 생성 권한을 받음.
- 구현한 기능: Java 21 / Spring Boot 3.5.16 / Gradle 8.14.6 프로젝트, JPA/MySQL 설정, DB를 제외한 부트스트랩 테스트.
- 변경 파일: README.md, .gitignore, backend/build.gradle, settings.gradle, Gradle Wrapper, Application, application.yml, ApplicationBootstrapTest.
- 기존 구조/기능: 기존 소스, Entity, API, Service, Frontend, AGENTS.md, 기존 테스트 없음. Stage 02는 backend/src 아래에 추가 예정.
- 테스트 명령: `cd backend && ./gradlew clean test --no-daemon` (환경별 JAVA_HOME, GRADLE_USER_HOME, Java proxy 및 시스템 trustStore 설정).
- 테스트 결과: 새 부트스트랩 테스트 1개 PASS, BUILD SUCCESSFUL. 기존 테스트는 없음.
- 미해결 문제: MySQL 저장 검증과 QCV 샘플은 후속 단계에서 확인.
- 다음 단계: STEP 1 실제 PoC 데이터 확보.

- 실행 환경 문제 및 해결: 기본 캐시 경로 쓰기 제한 → /workspace 캐시 사용; JRE만 설치됨 → 체크섬 검증한 Temurin JDK 21 설치; Java proxy 설정 및 시스템 인증서 지정으로 다운로드 문제 해결.
- GitHub: `gh repo create hyeongdongE/CircuitAgent --private` 실패 (`CreateRepository` 권한 부족). 로컬 저장소와 feat/stage02-design-modeling 브랜치 생성 완료. 원격 저장소/PR 없음.
