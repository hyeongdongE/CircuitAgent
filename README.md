# CircuitAgent

회로 검증 자동화 프로젝트. 현재 개발 범위는 Stage 02 (Design Modeling)입니다.

- Backend: Java 21, Spring Boot 3.5.16, Gradle 8.14.6, MySQL 8
- `backend/`: Spring Boot 애플리케이션, 도메인/파서/그래프/저장/API 구현 위치
- `docs/`: 단계별 구현 및 검증 기록
- Frontend: 아직 생성하지 않았습니다. React 화면은 현재 필수 범위가 아닙니다.
- Stage 01/03/04: 구현 범위 밖이며 코드가 아직 없습니다.

테스트:

```sh
cd backend
./gradlew clean test
```

실행 시 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 MySQL 접속을 설정합니다.
초기 부트스트랩 테스트는 DB 자동 설정을 제외하며 MySQL 저장을 검증하지 않습니다.
원본 QCV 샘플이 확보되기 전에는 QCV 문법을 추측하지 않습니다.
