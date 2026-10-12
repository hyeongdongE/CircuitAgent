"""Docker 없이 직접 설치한 MySQL에 테스트 전용 스키마를 생성하고, 자신이 생성한 것만 정리한다."""

import os
from collections.abc import Mapping
from contextlib import contextmanager
from uuid import uuid4

from sqlalchemy import text
from sqlalchemy.engine import URL

from circuitagent.database import make_engine


def test_server_url(environ: Mapping[str, str]) -> URL:
    """DB_* 운영 설정을 절대 상속하지 않는다. 비밀번호는 명시적으로 제공해야 한다."""
    required = ("TEST_DB_USERNAME", "TEST_DB_PASSWORD")
    if any(key not in environ or (key.endswith("USERNAME") and not environ[key]) for key in required):
        raise ValueError("Set TEST_DB_USERNAME and TEST_DB_PASSWORD; see docs/native-mysql.md")
    port = int(environ.get("TEST_DB_PORT", "3306"))
    if not 1 <= port <= 65535:
        raise ValueError("TEST_DB_PORT must be between 1 and 65535")
    return URL.create(
        "mysql+pymysql",
        username=environ["TEST_DB_USERNAME"],
        password=environ["TEST_DB_PASSWORD"],
        host=environ.get("TEST_DB_HOST", "127.0.0.1"),
        port=port,
        query={"charset": "utf8mb4"},
    )


# pytest가 지원 함수 이름을 테스트로 수집하지 않도록 명시한다.
test_server_url.__test__ = False


@contextmanager
def isolated_mysql():
    """CREATE 성공 이후에만 DROP한다. 고정/기존 DB를 비우거나 SQLite로 대체하지 않는다."""
    url = test_server_url(os.environ)
    server = make_engine(url)
    database = "circuitagent_test_" + uuid4().hex
    engine = None
    created = False
    try:
        with server.connect() as conn:
            conn.execute(
                text(f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
            )
            created = True
        engine = make_engine(url.set(database=database))
        yield engine
    finally:
        if engine is not None:
            engine.dispose()
        try:
            if created:
                with server.connect() as conn:
                    conn.execute(text(f"DROP DATABASE `{database}`"))
        finally:
            server.dispose()
