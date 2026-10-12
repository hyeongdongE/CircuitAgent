"""Python UI의 오류/빈 화면/주소 경계를 검사한다. 실제 업로드는 browser_tests에서 검증한다."""

from pathlib import Path

import pytest
import requests
from circuitagent.web import ApiClient
from streamlit.testing.v1 import AppTest


@pytest.mark.parametrize(
    "url",
    [
        "https://127.0.0.1:8080",
        "http://evil.example",
        "http://user:pass@127.0.0.1",
        "http://127.0.0.1/extra",
        "http://127.0.0.1?x=1",
        "http://127.0.0.1#x",
    ],
)
def test_no_remote_backend(url):
    with pytest.raises(ValueError):
        ApiClient(url)


def test_http_error_sanitization(monkeypatch):
    class Response:
        ok = False
        status_code = 500

        def json(self):
            raise ValueError("<html>private traceback</html>")

    client = ApiClient()
    monkeypatch.setattr(client.session, "request", lambda *a, **kw: Response())
    with pytest.raises(ValueError, match="500: Request failed"):
        client.call("/api/audit-rules")


def test_empty_screen_and_missing_files():
    test = AppTest.from_file(str(Path(__file__).parents[1] / "circuitagent/web.py")).run(timeout=20)
    assert not test.exception and test.title[0].value == "회로와 검증 근거"
    test.button[0].click().run(timeout=20)
    assert not test.exception and test.error[0].value == "BOM XLSX와 QCV 파일을 모두 선택하세요."


def test_connection_failure(monkeypatch):
    def fail(*args, **kw):
        raise requests.ConnectionError("private secret")

    monkeypatch.setattr(requests.Session, "request", fail)
    test = AppTest.from_file(str(Path(__file__).parents[1] / "circuitagent/web.py")).run(timeout=20)
    next(x for x in test.text_input if x.label == "설계 ID").set_value("123").run(timeout=20)
    assert (
        not test.exception
        and "Backend" in test.error[0].value
        and "private secret" not in test.error[0].value
    )
