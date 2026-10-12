#!/usr/bin/env python3
"""실제 FE 원본을 사용해 각 단계의 시간을 분리한다. SLA 또는 가짜 대형 회로 성능을 주장하지 않는다."""

import json
import platform
import statistics
import sys
from pathlib import Path
from time import perf_counter
from uuid import uuid4

from circuitagent.api import create_app
from circuitagent.audit import StructuredRuleAdapter, catalog, run_audit
from circuitagent.bom import parse_bom
from circuitagent.database import CircuitStore, make_engine
from circuitagent.graph import CircuitGraph, assemble
from circuitagent.netlist import QcvNetlistParser
from fastapi.testclient import TestClient


def main():
    root = Path(__file__).resolve().parents[1]
    bom = (root / ".local/qcv-fixtures/fe.csv").read_bytes()
    net = (root / ".local/qcv-fixtures/fe.qcv").read_bytes()
    engine = make_engine()
    store = CircuitStore(engine)
    samples = []

    def measure(row, name, fn):
        start = perf_counter()
        value = fn()
        row[name] = (perf_counter() - start) * 1000
        return value

    try:
        with TestClient(create_app(store), client=("127.0.0.1", 50000)) as client:
            for _ in range(3):
                times = {}
                b = measure(times, "bomParsingMs", lambda: parse_bom(bom, "fe.csv"))
                n = measure(
                    times,
                    "qcvParsingMs",
                    lambda: QcvNetlistParser().parse(net, "fe.qcv"),
                )
                d = measure(
                    times,
                    "integrationMs",
                    lambda: assemble("Python benchmark " + str(uuid4()), "R3", b, n),
                )
                g = measure(times, "graphConstructionMs", lambda: CircuitGraph(d))
                measure(
                    times,
                    "mysqlPersistenceMs",
                    lambda: store.save(
                        d, [("bom", "fe.csv", bom), ("netlist", "fe.qcv", net)]
                    ),
                )
                g = measure(
                    times, "snapshotLoadMs", lambda: CircuitGraph(store.load(d.id))
                )
                rules = measure(
                    times,
                    "ruleValidationMs",
                    lambda: StructuredRuleAdapter().validate(catalog()[1]),
                )
                measure(
                    times,
                    "circuitTraversalMs",
                    lambda: g.get_connected_pins(
                        g.get_pin_context("U16", "5")["net"].id
                    ),
                )
                audit = measure(
                    times, "ruleEvaluationIncludingHashMs", lambda: run_audit(g, rules)
                )
                measure(times, "auditPersistenceMs", lambda: store.save_audit(audit))
                r = measure(
                    times,
                    "auditApiResponseMs",
                    lambda: client.get(f"/api/designs/{d.id}/audits/{audit.id}"),
                )
                assert r.status_code == 200
                samples.append(times)
    finally:
        engine.dispose()
    result = {
        "environment": {
            "python": sys.version.split()[0],
            "platform": platform.platform(),
            "mysql": "8.0.43 Docker",
            "samples": 3,
            "api": "ASGI TestClient, real MySQL, no network/browser latency",
            "fixture": "Actual Sparrow FE R3",
        },
        "samplesMs": samples,
        "statisticsMs": {
            key: {
                "median": statistics.median(row[key] for row in samples),
                "mean": statistics.mean(row[key] for row in samples),
            }
            for key in samples[0]
        },
    }
    (root / "docs/python-performance.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps(result["statisticsMs"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
