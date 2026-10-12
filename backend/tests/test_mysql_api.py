"""실제 MySQL 8 Testcontainers: 모델/원본/감사 결과의 복원, FK·롤백·API를 검증한다."""

from uuid import uuid4

import pytest
from circuitagent.api import create_app
from circuitagent.audit import catalog, run_audit, snapshot_hash
from circuitagent.database import CircuitStore, make_engine, migrate
from circuitagent.domain import CircuitDesign, wire
from circuitagent.graph import CircuitGraph
from conftest import DATA
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from testcontainers.community.mysql import MySqlContainer


@pytest.fixture(scope="module")
def store():
    with MySqlContainer(
        "mysql:8.0.43",
        username="test",
        password="test",
        dbname="circuitagent",
        dialect="pymysql",
        docker_client_kw={"timeout": 240},
    ) as container:
        engine = make_engine(container.get_connection_url())
        migrate(engine)
        migrate(engine)  # 반복 실행은 schema/data를 중복 생성하지 않는다.
        yield CircuitStore(engine)
        engine.dispose()


def unique(design):
    raw = wire(design)
    raw.update(id=str(uuid4()), name="Test " + str(uuid4()))
    return CircuitDesign.model_validate(raw)


@pytest.mark.parametrize("board", ["fe", "ntop", "fe-ntop"])
def test_roundtrip(store, boards, board):
    d = unique(boards[board][2])
    files = [
        ("bom", board + ".xlsx", (DATA / (board + ".xlsx")).read_bytes()),
        ("netlist", board + ".qcv", (DATA / (board + ".qcv")).read_bytes()),
    ]
    store.save(d, files)
    restored = store.load(d.id)
    assert snapshot_hash(CircuitGraph(d)) == snapshot_hash(CircuitGraph(restored))
    assert {p.key: wire(p) for p in d.pins} == {p.key: wire(p) for p in restored.pins} and set(
        d.connections
    ) == set(restored.connections)
    assert (
        d.components == restored.components
        and d.specifications == restored.specifications
        and d.issues == restored.issues
    )
    original = {r["role"]: r for r in store.source_files(d.id)}
    for role, _, data in files:
        assert bytes(original[role]["content"]) == data


def test_duplicate_and_rollback(store, boards):
    d = unique(boards["fe"][2])
    store.save(d, [])
    raw = wire(d)
    raw["id"] = str(uuid4())
    duplicate = CircuitDesign.model_validate(raw)
    with pytest.raises(IntegrityError):
        store.save(duplicate, [])
    with pytest.raises(LookupError):
        store.load(duplicate.id)
    d = unique(d)
    with pytest.raises(IntegrityError):
        store.save(d, [("bom", "one", b"x"), ("bom", "two", b"y")])
    with pytest.raises(LookupError):
        store.load(d.id)


def test_cross_design_fk(store, boards):
    a, b = unique(boards["fe"][2]), unique(boards["fe"][2])
    store.save(a, [])
    store.save(b, [])
    p, n = store.tables["component_pin"], store.tables["circuit_net"]
    with pytest.raises(IntegrityError), store.engine.begin() as c:
        pin = c.scalar(select(p.c.id).where(p.c.design_id == a.id.bytes).limit(1))
        net = c.scalar(select(n.c.id).where(n.c.design_id == b.id.bytes).limit(1))
        store.insert(
            c, "pin_connection", [dict(id=uuid4().bytes, design_id=b.id.bytes, pin_id=pin, net_id=net)]
        )


def test_audit_roundtrip_isolation_rollback(store, boards):
    d = unique(boards["fe"][2])
    store.save(d, [])
    a = run_audit(CircuitGraph(store.load(d.id)), catalog()[1])
    store.save_audit(a)
    assert wire(a) == wire(store.load_audit(d.id, a.id))
    assert store.history(d.id)[0]["counts"] == {"UNKNOWN": 5, "PASS": 1}
    with pytest.raises(LookupError):
        store.load_audit(uuid4(), a.id)
    bad = a.model_copy(update={"id": uuid4(), "results": tuple(reversed(a.results))})
    with pytest.raises(ValueError):
        store.save_audit(bad)
    with pytest.raises(LookupError):
        store.load_audit(d.id, bad.id)


@pytest.fixture
def client(store):
    with TestClient(create_app(store), client=("127.0.0.1", 50000), raise_server_exceptions=False) as c:
        yield c


def upload(client, name=None):
    return client.post(
        "/api/designs/import",
        data={"name": name or "API " + str(uuid4()), "version": "R3", "netlistFormat": "qcv"},
        files={
            "bom": ("fe.xlsx", (DATA / "fe.xlsx").read_bytes()),
            "netlist": ("fe.qcv", (DATA / "fe.qcv").read_bytes()),
        },
    )


def test_real_api(client):
    r = upload(client)
    assert r.status_code == 201, r.text
    d = r.json()
    assert (
        d["componentCount"],
        d["netCount"],
        d["connectionCount"],
        d["terminatedPinCount"],
        len(d["issues"]),
    ) == (453, 335, 1500, 135, 80)
    base = "/api/designs/" + d["id"]
    c = client.get(base + "/components/U16").json()
    assert c["value"] is None and c["specification"]["mpn"] is None
    pin = client.get(base + "/components/U16/pins/5").json()
    assert pin["net"]["name"] == "U16_EN"
    net = client.get(base + "/nets/" + pin["net"]["id"]).json()
    assert set(net["components"]) == {"U16", "R107", "R83"}
    rules = client.get("/api/audit-rules").json()["rules"]
    r = client.post(
        base + "/audits", json={"schemaVersion": "poc-rule-v1", "ruleIds": [r["id"] for r in rules]}
    )
    assert r.status_code == 201, r.text
    a = r.json()
    assert [r["status"] for r in a["results"]] == [
        "UNKNOWN",
        "UNKNOWN",
        "UNKNOWN",
        "PASS",
        "UNKNOWN",
        "UNKNOWN",
    ]
    assert client.get(base + "/audits/" + a["id"]).json() == a
    assert len(client.get(base + "/audits/" + a["id"] + "/results").json()) == 6
    assert client.get(base + "/components/not-found").status_code == 404
    assert client.get(base + "/components/U16/pins/999").json()["status"] == "MISSING_PIN"
    assert (
        client.get(base + "/issues").status_code == 200
        and len(client.get(base + "/components").json()) == 453
        and len(client.get(base + "/nets").json()) == 335
    )
    assert client.get(base + "/audits").json()[0]["id"] == a["id"]
    assert client.get("/api/designs/" + str(uuid4()) + "/audits/" + a["id"]).status_code == 404
    assert client.get(base).status_code == 200


def test_api_duplicate(client):
    name = "Duplicate " + str(uuid4())
    assert upload(client, name).status_code == 201
    assert upload(client, name).status_code == 409


@pytest.mark.parametrize("data,name", [(b"bad", "bad.xlsx"), (b"", "empty.xlsx"), (b"bad", "bad.txt")])
def test_bad_upload(client, data, name):
    r = client.post(
        "/api/designs/import",
        data={"name": "Bad " + str(uuid4()), "netlistFormat": "qcv"},
        files={"bom": (name, data), "netlist": ("x.qcv", b"NET: 'A' R1-1\n")},
    )
    assert r.status_code == 400


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"schemaVersion": "wrong", "ruleIds": ["x"]},
        {"schemaVersion": "poc-rule-v1", "ruleIds": []},
        {"schemaVersion": "poc-rule-v1", "ruleIds": ["unknown"]},
        {"schemaVersion": "poc-rule-v1", "ruleIds": ["x"], "rules": []},
        {"schemaVersion": "poc-rule-v1", "rules": [], "extra": 1},
    ],
)
def test_bad_audit(client, body):
    assert client.post("/api/designs/" + str(uuid4()) + "/audits", json=body).status_code == 400


def test_security_and_limits(store, monkeypatch):
    with TestClient(create_app(store), client=("8.8.8.8", 5000)) as c:
        assert c.get("/api/audit-rules").status_code == 403
    with TestClient(create_app(store), client=("127.0.0.1", 5000)) as c:
        assert c.get("/api/audit-rules", headers={"Origin": "https://evil.example"}).status_code == 403
        assert (
            c.post(
                "/api/designs/" + str(uuid4()) + "/audits",
                content=b" " * (512 * 1024 + 1),
                headers={"Content-Type": "application/json"},
            ).status_code
            == 413
        )
        assert c.get("/api/audit-rules").headers["Cache-Control"] == "no-store"
        monkeypatch.setenv("CIRCUITAGENT_MODE", "production")
        assert c.get("/api/audit-rules").status_code == 403


def test_hierarchical_reference_api(client):
    from urllib.parse import quote

    r = client.post(
        "/api/designs/import",
        data={"name": "Hierarchy " + str(uuid4()), "netlistFormat": "qcv"},
        files={
            "bom": ("bom.csv", b"Reference,Value,DNP\n/hier/U1,IC,no\n"),
            "netlist": ("x.qcv", b"NET: '/VCC' /hier/U1-A1\n"),
        },
    )
    assert r.status_code == 201, r.text
    base = "/api/designs/" + r.json()["id"] + "/components/" + quote("/hier/U1", safe="")
    assert client.get(base).json()["reference"] == "/hier/U1"
    assert client.get(base + "/pins").json()[0]["number"] == "A1"
    assert client.get(base + "/pins/A1").json()["net"]["name"] == "/VCC"


def test_chunked_json_budget(client):
    def chunks():
        for _ in range(513):
            yield b" " * 1024

    assert (
        client.post(
            "/api/designs/" + str(uuid4()) + "/audits",
            content=chunks(),
            headers={"Content-Type": "application/json"},
        ).status_code
        == 413
    )
