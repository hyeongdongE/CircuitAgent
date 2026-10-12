# React PoC 실행 및 검증

Node24/npm11과 Java21 JDK, Docker/MySQL8이 필요하다. React/TypeScript/Vite/TanStack Query를 사용하며 [고정 의존성·라이선스](frontend-dependencies.json)와 lockfile을 제공한다. 별도 Python API/그래프 서버는 없다.

## 실제 파일 준비

저장소 루트에서 실행한다. 공개 Sparrow 원본에는 명확한 재배포 권한이 없어 CSV/QCV/XLSX는 ignored `.local/qcv-fixtures`에만 둔다. 다운로드는 pinned commit 및 SHA를 검사하고, Apache POI 변환은 모든 원본 행·셀을 보존한다.

```sh
python3 scripts/fetch-qcv-fixtures.py
cd backend
./gradlew convertQcvBoms
./gradlew clean test bootJar --no-daemon
```

Docker가 없거나 원본을 받지 못하면 통합 테스트는 실패하며 자동 skip하지 않는다. FE 파일은 루트의 `.local/qcv-fixtures/fe.xlsx`와 `fe.qcv`이다. raw source를 공개 PR에 추가하지 않는다.

## Backend

[README의 로컬 MySQL 준비](../README.md#로컬-실행)를 따른다. 로컬용 DB_PASSWORD를 설정하고 다른 프로젝트 DB를 사용하지 않는다. 같은 포트를 사용 중이면 해당 환경의 DB_URL을 명시한다.

```sh
export DB_URL='jdbc:mysql://127.0.0.1:3306/circuitagent?connectionTimeZone=UTC'
export DB_USERNAME=circuitagent
export CIRCUITAGENT_MODE=local-poc
cd backend
java -jar build/libs/circuitagent-0.1.0-SNAPSHOT.jar
```

Flyway V4는 이전 Stage02 schema를 보존하며 Audit 테이블을 추가한다. 기본127.0.0.1:8080 바인딩을 유지한다. 신뢰된 로컬 원본만 사용하고 공개 프록시·인터넷에 배포하지 않는다. production/enterprise는 접근 거절이며 사내 SSO가 아니다.

## Frontend

다른 터미널에서 실행한다.

```sh
cd frontend
npm ci
npm run dev
```

http://127.0.0.1:5173에 접속한다. strictPort로 포트가 사용 중이면 명시적으로 실패한다. 기본 `/api` proxy는127.0.0.1:8080이다. 다른 로컬 backend 포트가 필요하면 `BACKEND_URL`을 지정한다. `VITE_API_BASE_URL`은 fetch prefix지만 기본 proxy 사용을 권장한다. CORS를 임의로 열거나 remote mode를 우회하지 않는다.

화면은 회로 업로드(XLSX/QCV/name/version), Circuit Explorer(검색/속성/실장/Pin/Net/이웃/ImportIssue), Rule Audit(catalog 선택/실행/status/계산/누락/Evidence), Audit History(저장된 검사/시간/상세)로 구성한다. 로딩·오류·empty state와 모바일 기본 레이아웃이 있다. localStorage는 선택된 design UUID만 보관하며 성공 데이터나 검사 근거를 생성하지 않는다. 원문은 API에서 받은 값만 표시한다.

FE 업로드 후 U16을 검색하고 pin5의 U16_EN에서 R107-2/R83-2를 확인한다. R107을 선택하면 DNP와140kΩ가 나타난다.6 Rule 실행 결과는 PASS1/UNKNOWN5다. C5 nominal 확인만 PASS이며 EN 저항의 실장이나 실제 전압 정상성을 뜻하지 않는다. 페이지를 새로고침하고 검사 상세에서 저장된 결과·근거를 다시 확인한다.

## 테스트

```sh
cd frontend
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

unit6개는 명시적 자체 UI mock fixture다. Playwright2개는 실제 backend/MySQL이 먼저 실행된 상태에서 실제 FE XLSX/QCV를 업로드하며 network mocks를 사용하지 않는다. Vite는 테스트가 시작/종료한다. 정상 업로드→탐색→Audit→저장 이력→reload와 모바일 잘못된 업로드를 검증한다. 기존5173 서버가 있으면 의도한 동일 backend proxy인지 확인한다.

검증 환경: Node24.19.0/npm11.9, Chromium156/Playwright1.64.0. build/test 성공은 공개 배포나 운영 인증 완료를 뜻하지 않는다. [최종 검증](stage03-poc-validation.md)과 [보안/연동 계약](stage03-integration.md)을 참조한다.
