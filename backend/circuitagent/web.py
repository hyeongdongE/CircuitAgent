"""Python으로 작성한 최소 회로/Audit 화면. REST가 반환한 사실만 표시한다."""

import os
import sys
from pathlib import Path
from urllib.parse import quote, urlsplit

import requests
import streamlit as st


class ApiClient:
    """정해진 로컬 Backend만 호출한다. URL 입력/원문 수식 실행/원본 문법 해석은 하지 않는다."""

    def __init__(self, base=None):
        self.base = (base or os.getenv("API_BASE_URL", "http://127.0.0.1:8080")).rstrip("/")
        u = urlsplit(self.base)
        if (
            u.scheme != "http"
            or u.hostname not in {"127.0.0.1", "localhost", "::1"}
            or u.username
            or u.password
            or u.query
            or u.fragment
            or u.path
        ):
            raise ValueError("Only a local Backend origin is supported")
        self.session = requests.Session()
        self.session.trust_env = False

    def call(self, path, method="GET", **kwargs):
        r = self.session.request(method, self.base + path, timeout=(5, 120), **kwargs)
        if not r.ok:
            try:
                message = r.json().get("message", "Request failed")
            except ValueError:
                message = "Request failed"
            raise ValueError(f"{r.status_code}: {message}")
        return r.json()


def render():
    st.set_page_config(page_title="CircuitAgent", layout="wide")
    st.title("회로와 검증 근거")
    st.info("로컬 Python PoC입니다. UNKNOWN은 설계 오류가 아니며 PASS는 해당 Rule의 명시 조건에 한정됩니다.")
    try:
        api = ApiClient()
    except ValueError as exc:
        st.error(str(exc))
        st.stop()
    ids = st.session_state.setdefault("design_ids", [])
    st.subheader("1. 회로 업로드")
    with st.form("upload"):
        name = st.text_input("설계 이름", value="Sparrow FE", max_chars=191)
        version = st.text_input("설계 버전", value="R3", max_chars=64)
        bom = st.file_uploader("BOM XLSX", type=["xlsx"])
        net = st.file_uploader("QCV Netlist", type=["qcv"])
        submitted = st.form_submit_button("회로 업로드")
    if submitted:
        if bom is None or net is None:
            st.error("BOM XLSX와 QCV 파일을 모두 선택하세요.")
        elif bom.size > 10 * 1024 * 1024 or net.size > 10 * 1024 * 1024:
            st.error("각 파일은 10 MiB 이하이어야 합니다.")
        else:
            try:
                with st.spinner("실제 파일을 파싱하고 저장하는 중…"):
                    r = api.call(
                        "/api/designs/import",
                        "POST",
                        data={"name": name, "version": version, "netlistFormat": "qcv"},
                        files={"bom": (bom.name, bom.getvalue()), "netlist": (net.name, net.getvalue())},
                    )
                if r["id"] not in ids:
                    ids.insert(0, r["id"])
                st.session_state["active_design"] = r["id"]
                st.session_state["import_summary"] = r
            except (requests.RequestException, ValueError):
                st.error("업로드 실패: 파일 형식, 설계 버전 중복 또는 Backend 연결을 확인하세요.")
    if summary := st.session_state.get("import_summary"):
        st.success(
            f"Component {summary['componentCount']} · Net {summary['netCount']} · Connection {summary['connectionCount']} · TERM {summary['terminatedPinCount']} · Issue {len(summary['issues'])}"
        )
    st.subheader("설계 선택")
    # 저장된 ID는 UI 선택 편의용이다. 서버에서 실제 설계 존재 여부와 모든 데이터를 조회한다.
    manual = st.text_input("설계 ID", value=st.session_state.get("active_design", ""))
    if ids:
        saved = st.selectbox("최근 업로드", options=["", *ids])
        if saved:
            manual = saved
    if not manual:
        st.caption("파일을 업로드하거나 기존 설계 ID를 입력하세요.")
        return
    base = "/api/designs/" + quote(manual, safe="")
    try:
        components = api.call(base + "/components")
        issues = api.call(base + "/issues")
        st.subheader("2. Circuit Explorer")
        query = st.text_input("Component 검색", placeholder="U16, R107, C5…")
        matches = [c["reference"] for c in components if query.lower() in c["reference"].lower()]
        st.caption(f"{len(matches)}개 검색 결과")
        if matches:
            ref = st.selectbox("Component 선택", matches)
            component = api.call(base + "/components/" + quote(ref, safe=""))
            st.write(
                f"{ref} · {component['assemblyStatus']} · Value: {component['value'] if component['value'] is not None else '미확인'} · MPN: {component['specification']['mpn'] or '미확인'}"
            )
            st.dataframe(
                [
                    dict(attribute=k, raw=v["raw"], normalized=v["normalized"], unit=v["unit"])
                    for k, v in component["specification"]["attributes"].items()
                ],
                hide_index=True,
            )
            st.dataframe(
                [
                    dict(
                        number=p["number"],
                        name=p["name"],
                        status=p["disposition"],
                        net=p["net"]["name"] if p["net"] else None,
                    )
                    for p in component["pins"]
                ],
                hide_index=True,
            )
            pins = [p for p in component["pins"] if p["net"]]
            if pins:
                number = st.selectbox("연결 Pin 선택", [p["number"] for p in pins])
                pin = next(p for p in pins if p["number"] == number)
                net = api.call(base + "/nets/" + quote(pin["net"]["id"], safe=""))
                st.write("Net: " + net["name"])
                st.dataframe(net["connectedPins"], hide_index=True)
                st.caption("이웃 부품: " + ", ".join(net["components"]))
            with st.expander("BOM/QCV 출처"):
                st.json({"rawSources": component["rawSources"], "pinSources": component["pins"]})
        else:
            st.warning("일치하는 부품이 없습니다.")
        with st.expander(f"ImportIssue ({len(issues)})"):
            st.dataframe(issues, hide_index=True)
        st.subheader("3. Rule Audit")
        catalog = api.call("/api/audit-rules")
        st.caption(catalog["scope"])
        selected = st.multiselect(
            "검증 Rule 선택", [r["id"] for r in catalog["rules"]], default=[r["id"] for r in catalog["rules"]]
        )
        if st.button("Rule 검증 실행", disabled=not selected):
            with st.spinner("회로 Snapshot으로 검증하는 중…"):
                audit = api.call(
                    base + "/audits", "POST", json={"schemaVersion": "poc-rule-v1", "ruleIds": selected}
                )
            st.session_state["audit_id:" + manual] = audit["id"]
        st.subheader("4. Audit History")
        history = api.call(base + "/audits")
        st.dataframe(
            [
                dict(id=h["id"], time=h["executedAt"], version=h["designVersion"], counts=str(h["counts"]))
                for h in history
            ],
            hide_index=True,
        )
        if history:
            keys = [h["id"] for h in history]
            last = st.session_state.get("audit_id:" + manual)
            chosen = st.selectbox("검사 상세", keys, index=keys.index(last) if last in keys else 0)
            audit = api.call(base + "/audits/" + chosen)
            st.write("Snapshot " + audit["snapshotHash"])
            for r in audit["results"]:
                st.write(f"{r['status']} · {r['ruleId']} · {r['reference']}")
                st.write(
                    "계산/조회 값: "
                    + str(r["measured"] if r["measured"] is not None else "미확인")
                    + " "
                    + str(r["unit"] or "")
                )
                st.write(r["reason"])
                if r["missingInputs"]:
                    st.warning("누락/불확실 입력: " + ", ".join(r["missingInputs"]))
                with st.expander("Evidence / " + r["ruleId"]):
                    st.json({"expected": r["expected"], **r["evidence"]})
        else:
            st.caption("저장된 검사가 없습니다.")
    except (requests.RequestException, ValueError):
        st.error("조회/검증 실패: Backend 실행 상태와 설계 ID, 입력 계약을 확인하세요.")


def main():
    from streamlit.web import cli

    sys.argv = [
        "streamlit",
        "run",
        str(Path(__file__).resolve()),
        "--server.address=127.0.0.1",
        "--server.port=8501",
        "--server.maxUploadSize=10",
    ]
    raise SystemExit(cli.main())


if __name__ == "__main__":
    render()
