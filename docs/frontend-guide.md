# Python 화면 실행

현재 화면은 Streamlit이며 React/Node.js 실행은 필요하지 않다. 설치·DB·API 준비는 [README](../README.md)를 따른다.

가상환경에서 `python -m pip install -e 'backend[ui]'` 또는 README의 고정 requirements.lock 설치 후 실행한다.

```sh
circuitagent-web
```

주소 http://127.0.0.1:8501, 기본 Backend http://127.0.0.1:8080. 다른 로컬 포트는 API_BASE_URL 환경변수로 설정한다. 화면은 외부 서버 URL을 받지 않는다.

1. 실제 `.local/qcv-fixtures/fe.xlsx`와 `fe.qcv`를 선택한다.
2. 설계 이름·버전을 입력하고 업로드한다. 같은 이름/버전 재업로드는 DB unique 충돌로 거절한다.
3. Component453/Net335/Connection1500/TERM135/Issue80을 확인한다.
4. U16 검색, Pin5의 U16_EN과 R107/R83을 조회한다. 두 저항은 DNP다.
5. 기본 6 Rule 실행: PASS1/UNKNOWN5. 근거와 누락 입력을 펼쳐 확인한다.
6. 설계 ID를 기록한다. 검사 이력은 DB에 저장되며 새 세션에서도 같은 ID로 조회한다. 최근 ID 목록은 브라우저 세션 편의용이며 인증/접근 권한이 아니다.

단위 화면 테스트는 `cd backend` 후 `python -m pytest -q tests/test_web.py`. 실제 브라우저 테스트는 DB/API/화면을 실행하고 `python -m playwright install chromium`, `python -m pytest -q browser_tests`를 실행한다. Python Playwright 테스트는 API 응답 mock 없이 실제 공개 자료를 업로드한다.
