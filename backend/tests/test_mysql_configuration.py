"""전용 DB 계정 누락 시 실행을 막고, 앱 접속정보와 특수문자 비밀번호를 분리한다."""

import pytest
from mysql_support import test_server_url


@pytest.mark.parametrize(
    "env",
    [
        {},
        {"DB_USERNAME": "app", "DB_PASSWORD": "secret"},
        {"TEST_DB_USERNAME": "test"},
        {"TEST_DB_USERNAME": "", "TEST_DB_PASSWORD": "x"},
    ],
)
def test_explicit_test_credentials_required(env):
    with pytest.raises(ValueError, match="TEST_DB_USERNAME"):
        test_server_url(env)


def test_native_url_ignores_application_database():
    env = {
        "TEST_DB_USERNAME": "tester",
        "TEST_DB_PASSWORD": "a@:/%",
        "DB_HOST": "other",
        "DB_NAME": "production",
        "DATABASE_URL": "mysql+pymysql://app@other/production",
    }
    url = test_server_url(env)
    assert url.host == "127.0.0.1" and url.port == 3306 and url.database is None
    assert url.username == "tester" and url.password == "a@:/%"
    assert "a@:/%" not in str(url)  # SQLAlchemy 표현은 비밀번호를 가린다.


@pytest.mark.parametrize("port", ["0", "65536", "abc"])
def test_invalid_mysql_port(port):
    with pytest.raises(ValueError):
        test_server_url({"TEST_DB_USERNAME": "tester", "TEST_DB_PASSWORD": "x", "TEST_DB_PORT": port})
