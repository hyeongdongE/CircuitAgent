"""기존 /api/designs·Audit REST 계약을 유지하는 FastAPI 경계. 계산/DB는 별도 모듈이다."""

import json
import logging
import os
from contextlib import asynccontextmanager
from decimal import Decimal, DecimalException
from time import perf_counter
from urllib.parse import urlsplit
from uuid import UUID

from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from starlette.concurrency import run_in_threadpool

from .audit import AuditRule, StructuredRuleAdapter, catalog, run_audit
from .bom import MAX_BYTES, parse_bom
from .database import CircuitStore, make_engine, migrate
from .domain import Model, wire
from .graph import CircuitGraph, assemble
from .netlist import PARSERS


class AuditRequest(Model):
    schema_version: str
    rule_ids: tuple[str, ...] | None = None
    rules: tuple[AuditRule, ...] | None = None


def response(value, status=200, headers=None):
    return JSONResponse(wire(value), status_code=status, headers=headers)


def error(status, code, message):
    return response({"code": code, "message": message}, status)


class LocalBoundary:
    """로컬 PoC 전용 정책이며 기업 인증/설계별 권한을 대신하지 않는다. 실제 body bytes를 제한한다."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        path = scope.get("path", "")
        root_path = scope.get("root_path", "")
        effective_path = path[len(root_path) :] if root_path and path.startswith(root_path) else path
        if scope["type"] != "http" or not effective_path.startswith("/api/"):
            return await self.app(scope, receive, send)
        headers = dict(scope.get("headers", []))
        origin = headers.get(b"origin")
        allowed = True
        if origin:
            try:
                u = urlsplit(origin.decode())
                allowed = (
                    u.scheme == "http"
                    and u.hostname in {"localhost", "127.0.0.1", "::1"}
                    and u.port in {8080, 5173, 4173, 8501}
                )
            except (ValueError, UnicodeError):
                allowed = False
        if (
            os.getenv("CIRCUITAGENT_MODE", "local-poc") != "local-poc"
            or scope.get("client", ("", 0))[0] not in {"127.0.0.1", "::1"}
            or not allowed
        ):
            return await error(403, "POC_ACCESS_DENIED", "Only local isolated PoC access is configured")(
                scope, receive, send
            )
        is_json = headers.get(b"content-type", b"").lower().startswith(b"application/json") or (
            scope.get("method") == "POST" and effective_path.endswith("/audits")
        )
        limit = 512 * 1024 if is_json else 20 * 1024 * 1024
        messages = []
        total = 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            total += len(message.get("body", b""))
            if total > limit:
                return await error(
                    413, "JSON_TOO_LARGE" if is_json else "FILE_TOO_LARGE", "Request exceeds input size limit"
                )(scope, receive, send)
            messages.append(message)
            if not message.get("more_body"):
                break
        index = 0

        async def replay():
            nonlocal index
            if index < len(messages):
                value = messages[index]
                index += 1
                return value
            return await receive()

        async def safe_send(message):
            if message["type"] == "http.response.start":
                message["headers"] = [
                    *message.get("headers", []),
                    (b"cache-control", b"no-store"),
                    (b"x-content-type-options", b"nosniff"),
                ]
            await send(message)

        return await self.app(scope, replay, safe_send)


def create_app(store=None):
    @asynccontextmanager
    async def lifespan(app):
        if store is None:
            engine = make_engine()
            await run_in_threadpool(migrate, engine)
            app.state.store = await run_in_threadpool(CircuitStore, engine)
            try:
                yield
            finally:
                engine.dispose()
        else:
            app.state.store = store
            yield

    app = FastAPI(
        title="CircuitAgent Python PoC",
        lifespan=lifespan,
        docs_url="/api/docs",
        openapi_url="/api/openapi.json",
        redoc_url=None,
    )
    app.add_middleware(LocalBoundary)

    @app.exception_handler(LookupError)
    async def missing(request, exc):
        return error(404, "NOT_FOUND", "Requested design/component/pin/net/audit was not found")

    @app.exception_handler(ValueError)
    async def invalid(request, exc):
        return error(400, "INVALID_INPUT", "Input format or explicit data contract is invalid")

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        return error(400, "INVALID_INPUT", "Request fields or identifiers are invalid")

    @app.exception_handler(IntegrityError)
    async def conflict(request, exc):
        return error(409, "DESIGN_CONFLICT", "Duplicate design version or data integrity conflict")

    @app.exception_handler(SQLAlchemyError)
    async def db_error(request, exc):
        logging.getLogger(__name__).exception("Database request failed")
        return error(500, "PERSISTENCE_ERROR", "Database operation failed")

    @app.exception_handler(Exception)
    async def internal(request, exc):
        logging.getLogger(__name__).exception("Request failed")
        return error(500, "INTERNAL_ERROR", "Request could not be completed")

    def graph(request, id):
        return CircuitGraph(request.app.state.store.load(id))

    def component_view(g, ref):
        c = g.get_component(ref)
        return dict(
            reference=ref,
            value=c.value,
            specification=g.get_component_specification(ref),
            rawSources=c.raw_sources,
            sourcePath=c.source_path,
            assemblyStatus=c.assembly_status,
            pins=[
                dict(
                    number=p.key.number,
                    name=p.name,
                    electricalType=p.electrical_type,
                    net=g.get_net_of_pin(ref, p.key.number),
                    disposition=p.disposition,
                    sourceMetadata=p.source_metadata,
                )
                for p in sorted(g.get_component_pins(ref), key=lambda p: p.key.number)
            ],
        )

    def net_view(g, id):
        if id not in g.design.nets:
            raise LookupError("Net not found")
        n = g.design.nets[id]
        return dict(
            id=n.id,
            name=n.name,
            sourcePath=n.source_path,
            connectedPins=[p.key for p in g.get_connected_pins(id)],
            components=[c.reference for c in g.get_components_on_net(id)],
        )

    @app.get("/api/audit-rules")
    def rules():
        d, r = catalog()
        return response(
            dict(
                schemaVersion="poc-rule-v1",
                scope="Manual Sparrow FE R3 bindings; candidate identity, nominal facts not converter compliance",
                pinMapping=d["pinMapping"],
                rules=r,
            )
        )

    @app.post("/api/designs/import")
    def import_design(
        request: Request,
        name: str = Form(..., min_length=1, max_length=191),
        version: str = Form("v1", min_length=1, max_length=64),
        netlistFormat: str = Form("kicad"),
        bomMapping: str | None = Form(None, max_length=16384),
        bom: UploadFile = File(...),
        netlist: UploadFile = File(...),
    ):
        if not name.strip() or not version.strip() or netlistFormat not in PARSERS:
            raise ValueError("Unsupported format")
        files = []
        for role, upload in [("bom", bom), ("netlist", netlist)]:
            filename = (upload.filename or "").replace("\\", "/").rsplit("/", 1)[-1]
            if (
                not filename.strip()
                or len(filename) > 512
                or any(ord(c) < 32 or ord(c) == 127 for c in filename)
            ):
                raise ValueError("Invalid filename")
            data = upload.file.read(MAX_BYTES + 1)
            if len(data) > MAX_BYTES:
                return error(413, "FILE_TOO_LARGE", "Each input file must be at most 10 MiB")
            files.append((role, filename, data))
        ext = files[1][1].lower()
        if (
            netlistFormat == "qcv"
            and not ext.endswith(".qcv")
            or netlistFormat == "kicad"
            and not ext.endswith(".net")
        ):
            raise ValueError("Netlist extension mismatch")
        d = assemble(
            name,
            version,
            parse_bom(files[0][2], files[0][1], json.loads(bomMapping) if bomMapping else None),
            PARSERS[netlistFormat].parse(files[1][2], files[1][1]),
        )
        request.app.state.store.save(d, files)
        return response(
            dict(
                id=d.id,
                componentCount=len(d.components),
                pinCount=len(d.pins),
                netCount=len(d.nets),
                connectionCount=len(d.connections),
                terminatedPinCount=sum(p.disposition == "TERMINATED" for p in d.pins),
                issues=d.issues,
            ),
            201,
            {"Location": f"/api/designs/{d.id}"},
        )

    @app.get("/api/designs/{id}")
    def design(request: Request, id: UUID):
        return response(request.app.state.store.load(id))

    @app.get("/api/designs/{id}/components")
    def components(request: Request, id: UUID):
        g = graph(request, id)
        return response([component_view(g, r) for r in sorted(g.design.components)])

    @app.get("/api/designs/{id}/components/{ref:path}/pins")
    def pins(request: Request, id: UUID, ref: str):
        return response(component_view(graph(request, id), ref)["pins"])

    @app.get("/api/designs/{id}/components/{ref:path}/pins/{number}")
    def context(request: Request, id: UUID, ref: str, number: str):
        return response(graph(request, id).get_pin_context(ref, number))

    @app.get("/api/designs/{id}/nets")
    def nets(request: Request, id: UUID):
        g = graph(request, id)
        return response([net_view(g, n) for n in sorted(g.design.nets)])

    @app.get("/api/designs/{id}/nets/{net_id}")
    def net(request: Request, id: UUID, net_id: str):
        return response(net_view(graph(request, id), net_id))

    @app.get("/api/designs/{id}/components/{ref:path}")
    def component(request: Request, id: UUID, ref: str):
        return response(component_view(graph(request, id), ref))

    @app.get("/api/designs/{id}/issues")
    def issues(request: Request, id: UUID):
        return response(request.app.state.store.load(id).issues)

    @app.post("/api/designs/{id}/audits")
    async def execute(request: Request, id: UUID):
        # 일반 JSON float 변환 전에 Decimal로 읽어 Rule의 소수 원문을 보존한다.
        try:
            body = json.loads(
                await request.body(),
                parse_float=Decimal,
                parse_constant=lambda s: (_ for _ in ()).throw(ValueError("Nonfinite JSON")),
            )
        except (json.JSONDecodeError, UnicodeError, DecimalException) as exc:
            raise ValueError("Invalid JSON") from exc
        req = AuditRequest.model_validate(body)
        if req.schema_version != "poc-rule-v1" or (req.rule_ids is None) == (req.rules is None):
            raise ValueError("Provide exactly one of ruleIds or rules")
        if req.rule_ids is not None:
            all_rules = {r.id: r for r in catalog()[1]}
            if len(set(req.rule_ids)) != len(req.rule_ids) or any(i not in all_rules for i in req.rule_ids):
                raise ValueError("Invalid catalog selection")
            selected = StructuredRuleAdapter().validate([all_rules[i] for i in req.rule_ids])
        else:
            selected = StructuredRuleAdapter().validate(req.rules)

        def run():
            s = perf_counter()
            g = graph(request, id)
            load = (perf_counter() - s) * 1000
            a = run_audit(g, selected)
            a = type(a).model_validate({**wire(a), "timingMs": {**a.timing_ms, "snapshotLoadMs": load}})
            request.app.state.store.save_audit(a)
            return a

        a = await run_in_threadpool(run)
        return response(a, 201, {"Location": f"/api/designs/{id}/audits/{a.id}"})

    @app.get("/api/designs/{id}/audits")
    def history(request: Request, id: UUID):
        return response(request.app.state.store.history(id))

    @app.get("/api/designs/{id}/audits/{audit_id}")
    def audit(request: Request, id: UUID, audit_id: UUID):
        return response(request.app.state.store.load_audit(id, audit_id))

    @app.get("/api/designs/{id}/audits/{audit_id}/results")
    def results(request: Request, id: UUID, audit_id: UUID):
        return response(request.app.state.store.load_audit(id, audit_id).results)

    return app


app = create_app()
