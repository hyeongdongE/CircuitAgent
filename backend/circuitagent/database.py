"""기존 MySQL V1~V4 테이블을 재사용한다. UUID는 Java와 같은 16-byte 배열이다."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import zlib
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from uuid import UUID, uuid4

from sqlalchemy import MetaData, create_engine, func, select, text
from sqlalchemy.engine import URL

from .audit import AuditExecution
from .domain import (
    CircuitDesign,
    CircuitNet,
    ComponentInstance,
    ComponentPin,
    ImportIssue,
    PartSpecification,
    PinConnection,
    PinKey,
    wire,
)

MIGRATIONS = Path(__file__).parent / "resources/db/migration"


def make_engine(url=None):
    """비밀번호는 URL 문자열에 이어 붙이지 않고 SQLAlchemy URL 객체로 인코딩한다."""
    if url is None:
        url = os.getenv("DATABASE_URL")
    if not url:
        url = URL.create(
            "mysql+pymysql",
            username=os.getenv("DB_USERNAME", "circuitagent"),
            password=os.getenv("DB_PASSWORD", ""),
            host=os.getenv("DB_HOST", "127.0.0.1"),
            port=int(os.getenv("DB_PORT", "3306")),
            database=os.getenv("DB_NAME", "circuitagent"),
            query={"charset": "utf8mb4"},
        )
    return create_engine(
        url,
        pool_pre_ping=True,
        json_serializer=lambda x: json.dumps(wire(x), ensure_ascii=False),
        json_deserializer=lambda x: json.loads(x, parse_float=Decimal),
    )


def flyway_checksum(data):
    """Flyway의 기존 UTF-8 line CRC32를 확인한다. 기존 history를 수정하지 않는다."""
    crc = 0
    for line in data.decode("utf-8-sig").splitlines():
        crc = zlib.crc32(line.encode("utf-8"), crc)
    return crc if crc < 2**31 else crc - 2**32


def migrate(engine):
    """새 DB에는 동일 SQL을 적용한다. 기존 Flyway DB는 checksum 검증 후 Python 이력으로 인계한다."""
    if engine.dialect.name != "mysql":
        raise ValueError("MySQL 8 is required; SQLite/H2 is not a persistence substitute")
    with engine.connect() as conn:
        if conn.scalar(text("SELECT GET_LOCK('circuitagent_python_migration',30)")) != 1:
            raise RuntimeError("Migration lock unavailable")
        try:
            tables = set(conn.scalars(text("SHOW TABLES")))
            history = {}
            if "flyway_schema_history" in tables:
                rows = conn.execute(
                    text(
                        "SELECT version,checksum,success FROM flyway_schema_history WHERE version IS NOT NULL"
                    )
                ).mappings()
                for row in rows:
                    if not row["success"]:
                        raise RuntimeError("Failed Flyway migration must be resolved before handoff")
                    history[str(row["version"])] = row["checksum"]
                if set(history) - {"1", "2", "3", "4"}:
                    raise RuntimeError("Unknown Flyway version; migration contract needs review")
            if "circuit_design" in tables and not history and "python_schema_history" not in tables:
                raise RuntimeError("Unmanaged existing database; do not guess schema version")
            conn.execute(
                text(
                    "CREATE TABLE IF NOT EXISTS python_schema_history (version INT PRIMARY KEY, sha256 CHAR(64) NOT NULL, applied_at DATETIME(6) NOT NULL)"
                )
            )
            conn.commit()
            applied = dict(conn.execute(text("SELECT version,sha256 FROM python_schema_history")).all())
            for p in sorted(MIGRATIONS.glob("V*.sql")):
                version = int(p.name.split("__")[0][1:])
                data = p.read_bytes()
                sha = hashlib.sha256(data).hexdigest()
                if version in applied:
                    if applied[version] != sha:
                        raise RuntimeError("Previously applied Python migration was modified")
                    continue
                if str(version) in history:
                    if history[str(version)] != flyway_checksum(data):
                        raise RuntimeError("Flyway checksum mismatch; cannot safely adopt")
                else:
                    # SQL 원본은 고정·검증된 내부 migration이며 사용자 파일이나 문자열을 실행하지 않는다.
                    script = re.sub(r"--[^\n]*", "", data.decode("utf-8"))
                    for statement in script.split(";"):
                        if statement.strip():
                            conn.execute(text(statement))
                conn.execute(
                    text("INSERT INTO python_schema_history VALUES (:version,:sha,:time)"),
                    {"version": version, "sha": sha, "time": datetime.now(UTC).replace(tzinfo=None)},
                )
                conn.commit()
        finally:
            conn.execute(text("SELECT RELEASE_LOCK('circuitagent_python_migration')"))
            conn.commit()


def binary(id_):
    return UUID(str(id_)).bytes


def uid(value):
    return UUID(bytes=bytes(value))


def utc(value):
    return value.replace(tzinfo=UTC).isoformat().replace("+00:00", "Z")


class CircuitStore:
    """ORM reflection으로 기존 테이블을 그대로 사용한다. 저장/복원은 설계 ID로 격리한다."""

    def __init__(self, engine):
        self.engine = engine
        self.meta = MetaData()
        self.meta.reflect(bind=engine)
        self.tables = self.meta.tables
        required = {
            "circuit_design",
            "part_specification",
            "component_instance",
            "component_pin",
            "circuit_net",
            "pin_connection",
            "import_issue",
            "import_file",
            "circuit_design_specification",
            "audit_execution",
            "audit_result",
        }
        if not required <= set(self.tables):
            raise RuntimeError("Run python -m circuitagent.database migrate first")

    def insert(self, conn, table, rows):
        if rows:
            conn.execute(self.tables[table].insert(), rows)

    def save(self, design, files):
        d = design
        did = d.id.bytes
        # FK 부모→자식 순서, 전체 데이터 및 원본 파일을 하나의 트랜잭션으로 저장한다.
        with self.engine.begin() as c:
            self.insert(
                c,
                "circuit_design",
                [
                    dict(
                        id=did,
                        name=d.name,
                        version=d.version,
                        imported_at=datetime.now(UTC).replace(tzinfo=None),
                        source_metadata=wire(d.source_metadata),
                    )
                ],
            )
            for s in d.specifications.values():
                existing = (
                    c.execute(
                        select(self.tables["part_specification"]).where(
                            self.tables["part_specification"].c.id == s.id.bytes
                        )
                    )
                    .mappings()
                    .first()
                )
                if existing:
                    restored = PartSpecification(
                        id=uid(existing["id"]),
                        manufacturer=existing["manufacturer"],
                        mpn=existing["mpn"],
                        type=existing["component_type"],
                        attributes=existing["attributes"],
                    )
                    if restored != s:
                        raise ValueError("Specification UUID conflict")
                else:
                    self.insert(
                        c,
                        "part_specification",
                        [
                            dict(
                                id=s.id.bytes,
                                manufacturer=s.manufacturer,
                                mpn=s.mpn,
                                component_type=s.type,
                                attributes=wire(s.attributes),
                            )
                        ],
                    )
            self.insert(
                c,
                "circuit_design_specification",
                [dict(design_id=did, specification_id=s.bytes) for s in d.specifications],
            )
            refs = {ref: uuid4().bytes for ref in d.components}
            pins = {p.key: uuid4().bytes for p in d.pins}
            nets = {id_: uuid4().bytes for id_ in d.nets}
            self.insert(
                c,
                "component_instance",
                [
                    dict(
                        id=refs[r],
                        design_id=did,
                        specification_id=v.part_specification_id.bytes,
                        component_reference=r,
                        component_value=v.value,
                        source_path=v.source_path,
                        raw_sources=wire(v.raw_sources),
                        assembly_status=v.assembly_status,
                    )
                    for r, v in d.components.items()
                ],
            )
            self.insert(
                c,
                "component_pin",
                [
                    dict(
                        id=pins[p.key],
                        component_id=refs[p.key.reference],
                        design_id=did,
                        number=p.key.number,
                        name=p.name,
                        electrical_type=p.electrical_type,
                        disposition=p.disposition,
                        source_metadata=wire(p.source_metadata),
                    )
                    for p in d.pins
                ],
            )
            self.insert(
                c,
                "circuit_net",
                [
                    dict(id=nets[n.id], design_id=did, net_code=n.id, name=n.name, source_path=n.source_path)
                    for n in d.nets.values()
                ],
            )
            self.insert(
                c,
                "pin_connection",
                [
                    dict(id=uuid4().bytes, design_id=did, pin_id=pins[x.pin], net_id=nets[x.net_id])
                    for x in d.connections
                ],
            )
            self.insert(
                c,
                "import_issue",
                [
                    dict(
                        id=uuid4().bytes,
                        design_id=did,
                        issue_order=i,
                        severity=x.severity,
                        code=x.code,
                        component_reference=x.reference,
                        source=x.source,
                        message=x.message,
                    )
                    for i, x in enumerate(d.issues)
                ],
            )
            self.insert(
                c,
                "import_file",
                [
                    dict(
                        id=uuid4().bytes,
                        design_id=did,
                        role=role,
                        filename=name,
                        sha256=hashlib.sha256(data).hexdigest(),
                        content=data,
                    )
                    for role, name, data in files
                ],
            )
        return d.id

    def load(self, id_):
        did = binary(id_)
        with self.engine.connect() as c:
            table = self.tables["circuit_design"]
            d = c.execute(select(table).where(table.c.id == did)).mappings().first()
            if not d:
                raise LookupError("Design not found")

            def rows(name):
                t = self.tables[name]
                return list(c.execute(select(t).where(t.c.design_id == did)).mappings())

            st, links = self.tables["part_specification"], self.tables["circuit_design_specification"]
            srows = c.execute(
                select(st).join(links, links.c.specification_id == st.c.id).where(links.c.design_id == did)
            ).mappings()
            specs = {
                uid(s["id"]): PartSpecification(
                    id=uid(s["id"]),
                    manufacturer=s["manufacturer"],
                    mpn=s["mpn"],
                    type=s["component_type"],
                    attributes=s["attributes"],
                )
                for s in srows
            }
            comp_rows = rows("component_instance")
            refs = {bytes(r["id"]): r["component_reference"] for r in comp_rows}
            comps = {
                r["component_reference"]: ComponentInstance(
                    reference=r["component_reference"],
                    value=r["component_value"],
                    part_specification_id=uid(r["specification_id"]),
                    raw_sources=r["raw_sources"],
                    source_path=r["source_path"],
                    assembly_status=r["assembly_status"],
                )
                for r in comp_rows
            }
            prows = rows("component_pin")
            pkeys = {
                bytes(p["id"]): PinKey(reference=refs[bytes(p["component_id"])], number=p["number"])
                for p in prows
            }
            pins = [
                ComponentPin(
                    key=pkeys[bytes(p["id"])],
                    name=p["name"],
                    electrical_type=p["electrical_type"],
                    disposition=p["disposition"],
                    source_metadata=p["source_metadata"],
                )
                for p in prows
            ]
            nrows = rows("circuit_net")
            ncodes = {bytes(n["id"]): n["net_code"] for n in nrows}
            nets = {
                n["net_code"]: CircuitNet(id=n["net_code"], name=n["name"], source_path=n["source_path"])
                for n in nrows
            }
            connections = [
                PinConnection(pin=pkeys[bytes(x["pin_id"])], net_id=ncodes[bytes(x["net_id"])])
                for x in rows("pin_connection")
            ]
            issues = [
                ImportIssue(
                    severity=x["severity"],
                    code=x["code"],
                    reference=x["component_reference"],
                    source=x["source"],
                    message=x["message"],
                )
                for x in sorted(rows("import_issue"), key=lambda x: x["issue_order"])
            ]
            return CircuitDesign(
                id=uid(d["id"]),
                name=d["name"],
                version=d["version"],
                components=comps,
                specifications=specs,
                pins=pins,
                nets=nets,
                connections=connections,
                issues=issues,
                source_metadata=d["source_metadata"],
            )

    def require_design(self, id_):
        table = self.tables["circuit_design"]
        with self.engine.connect() as connection:
            if connection.scalar(select(table.c.id).where(table.c.id == binary(id_))) is None:
                raise LookupError("Design not found")

    def source_files(self, id_):
        self.require_design(id_)
        t = self.tables["import_file"]
        with self.engine.connect() as c:
            return tuple(
                dict(r)
                for r in c.execute(
                    select(t).where(t.c.design_id == binary(id_)).order_by(t.c.role)
                ).mappings()
            )

    def save_audit(self, a):
        if not re.fullmatch("[0-9a-f]{64}", a.snapshot_hash) or len(a.rule_snapshot) != len(a.results):
            raise ValueError("Invalid execution snapshot")

        def envelope(x):
            return {"json": json.dumps(wire(x), ensure_ascii=False, separators=(",", ":"))}

        # 문자열 envelope는 기존 Java 계약과 같고 34자리 Decimal/Instant 원문을 보존한다.
        meta = wire(a)
        meta["results"] = []
        with self.engine.begin() as c:
            self.insert(
                c,
                "audit_execution",
                [
                    dict(
                        id=a.id.bytes,
                        design_id=a.design_id.bytes,
                        design_version=a.design_version,
                        snapshot_hash=a.snapshot_hash,
                        executed_at=datetime.fromisoformat(a.executed_at.replace("Z", "+00:00")).replace(
                            tzinfo=None
                        ),
                        metadata=envelope(meta),
                    )
                ],
            )
            for i, (rule, r) in enumerate(zip(a.rule_snapshot, a.results, strict=True)):
                if (rule.id, rule.version, rule.target.reference) != (r.rule_id, r.rule_version, r.reference):
                    raise ValueError("Rule snapshot/result mismatch")
                self.insert(
                    c,
                    "audit_result",
                    [
                        dict(
                            id=r.id.bytes,
                            execution_id=a.id.bytes,
                            design_id=a.design_id.bytes,
                            result_order=i,
                            rule_id=r.rule_id,
                            rule_version=r.rule_version,
                            component_reference=r.reference,
                            status=r.status,
                            payload=envelope(r),
                        )
                    ],
                )

    def load_audit(self, design_id, audit_id):
        t = self.tables["audit_execution"]
        r = self.tables["audit_result"]
        with self.engine.connect() as c:
            row = (
                c.execute(select(t).where(t.c.id == binary(audit_id), t.c.design_id == binary(design_id)))
                .mappings()
                .first()
            )
            if not row:
                raise LookupError("Audit not found")
            # 큰 JSON을 DB filesort 대상으로 쓰지 않고 최대100개 결과를 Python에서 정렬한다.
            results = sorted(
                c.execute(
                    select(r).where(r.c.execution_id == binary(audit_id), r.c.design_id == binary(design_id))
                ).mappings(),
                key=lambda x: x["result_order"],
            )
            meta = json.loads(row["metadata"]["json"], parse_float=Decimal)
            meta["results"] = [json.loads(x["payload"]["json"], parse_float=Decimal) for x in results]
            return AuditExecution.model_validate(meta)

    def history(self, id_):
        self.require_design(id_)
        t = self.tables["audit_execution"]
        r = self.tables["audit_result"]
        with self.engine.connect() as c:
            rows = c.execute(
                select(t.c.id, t.c.design_id, t.c.design_version, t.c.executed_at)
                .where(t.c.design_id == binary(id_))
                .order_by(t.c.executed_at.desc())
                .limit(50)
            ).mappings()
            result = []
            for row in rows:
                counts = dict(
                    c.execute(
                        select(r.c.status, func.count())
                        .where(r.c.execution_id == row["id"])
                        .group_by(r.c.status)
                    ).all()
                )
                result.append(
                    dict(
                        id=uid(row["id"]),
                        designId=uid(row["design_id"]),
                        designVersion=row["design_version"],
                        executedAt=utc(row["executed_at"]),
                        counts=counts,
                    )
                )
            return result


def main():
    p = argparse.ArgumentParser()
    p.add_argument("command", choices=["migrate"])
    p.parse_args()
    engine = make_engine()
    migrate(engine)
    engine.dispose()
    print("MySQL schema V1–V4 verified/applied")


if __name__ == "__main__":
    main()
