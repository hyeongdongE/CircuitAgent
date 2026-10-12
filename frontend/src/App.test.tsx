// 화면 단위 테스트: API 응답을 대체하여 상태 표시와 사용자 조작을 검증한다. 실제 회로 업로드 검증은 e2e 테스트가 담당한다.
import { beforeEach, afterEach, describe, it, expect, vi } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
const id = "11111111-1111-4111-8111-111111111111";
const source = {
  url: "https://example.org/own-ui-fixture",
  revision: "own-fixture",
  page: 1,
  section: "test",
  summary: "Synthetic UI contract",
  sha256: "0".repeat(64),
};
const net = { id: "own-net", name: "OWN_NET", sourcePath: "own.qcv:line:1" };
const component = {
  reference: "U1",
  value: "own",
  assemblyStatus: "FITTED",
  specification: {
    mpn: null,
    manufacturer: null,
    type: null,
    attributes: {
      "Part Name": { raw: "Own test", normalized: null, unit: null },
    },
  },
  pins: [
    {
      number: "1",
      name: null,
      electricalType: null,
      disposition: "UNSPECIFIED",
      net,
      sourceMetadata: { filename: "own.qcv" },
    },
  ],
  rawSources: { bom: { Reference: "U1" } },
  sourcePath: "own.xlsx:2",
};
const rule = {
  id: "own-ui-rule",
  version: "v1",
  type: "PIN_CONNECTED",
  target: { reference: "U1", pinNumber: "1" },
  condition: "Own fixture only",
  source,
};
const execution = {
  id: "22222222-2222-4222-8222-222222222222",
  designId: id,
  designVersion: "own",
  snapshotHash: "0".repeat(64),
  executedAt: "2026-10-11T00:00:00Z",
  ruleSnapshot: [rule],
  timingMs: {},
  results: [
    {
      id: "result",
      ruleId: rule.id,
      reference: "U1",
      status: "UNKNOWN",
      measured: null,
      unit: null,
      expected: null,
      reason: "Own missing input",
      missingInputs: ["Own MPN unavailable"],
      executedAt: "2026-10-11T00:00:00Z",
      evidence: {
        datasheet: source,
        condition: rule.condition,
        components: {},
        specifications: {},
        pins: [],
        calculation: {},
        issues: [],
      },
    },
  ],
};
function response(data: unknown, status = 200) {
  return Promise.resolve(
    new Response(JSON.stringify(data), {
      status,
      headers: { "Content-Type": "application/json" },
    }),
  );
}
function mockApi() {
  const fn = vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
    const path = String(url);
    if (path === "/api/designs/import")
      return response(
        {
          id,
          componentCount: 2,
          pinCount: 2,
          netCount: 1,
          connectionCount: 2,
          terminatedPinCount: 0,
          issues: [],
        },
        201,
      );
    if (path === "/api/audit-rules")
      return response({
        schemaVersion: "poc-rule-v1",
        scope: "Own fixture catalog",
        pinMapping: {},
        rules: [rule],
      });
    if (path.endsWith("/components"))
      return response([component, { ...component, reference: "R1" }]);
    if (path.endsWith("/components/U1")) return response(component);
    if (path.endsWith("/issues")) return response([]);
    if (path.endsWith("/nets/own-net"))
      return response({
        ...net,
        connectedPins: [
          { reference: "U1", number: "1" },
          { reference: "R1", number: "1" },
        ],
        components: ["U1", "R1"],
      });
    if (path.endsWith("/audits") && init?.method === "POST")
      return response(execution, 201);
    if (path.endsWith("/audits"))
      return response([
        {
          id: execution.id,
          designId: id,
          designVersion: "own",
          executedAt: execution.executedAt,
          counts: { UNKNOWN: 1 },
        },
      ]);
    if (path.endsWith("/audits/" + execution.id)) return response(execution);
    throw new Error("Unmocked own-fixture API " + path);
  });
  vi.stubGlobal("fetch", fn);
  return fn;
}
function show() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: {
            queries: { retry: false, gcTime: 0 },
            mutations: { retry: false },
          },
        })
      }
    >
      <App />
    </QueryClientProvider>,
  );
}
beforeEach(() => localStorage.clear());
afterEach(() => vi.unstubAllGlobals());
describe("own UI contract fixtures", () => {
  it("shows an empty state without fabricated imports or results", () => {
    mockApi();
    show();
    expect(
      screen.getByText("먼저 파일을 업로드하거나 기존 설계 ID를 입력하세요."),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("audit-results")).not.toBeInTheDocument();
  });
  it("uploads selected files using FormData then shows server summary", async () => {
    const fn = mockApi();
    show();
    await userEvent.upload(
      screen.getByLabelText("BOM XLSX"),
      new File(["own"], "own.xlsx", {
        type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      }),
    );
    await userEvent.upload(
      screen.getByLabelText("QCV Netlist"),
      new File(["own"], "own.qcv", { type: "text/plain" }),
    );
    fireEvent.submit(
      screen.getByRole("button", { name: "회로 업로드" }).closest("form")!,
    );
    expect(await screen.findByTestId("import-summary")).toHaveTextContent(
      "Component 2",
    );
    expect(
      fn.mock.calls.find((x) => String(x[0]) === "/api/designs/import")?.[1]
        ?.body,
    ).toBeInstanceOf(FormData);
  });
  it("searches components and retrieves actual API net neighbors", async () => {
    mockApi();
    localStorage.setItem("circuitagent.designIds", JSON.stringify([id]));
    show();
    await userEvent.type(screen.getByLabelText("Component 검색"), "U1");
    await userEvent.click(
      await screen.findByRole("button", { name: "U1 · FITTED" }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "OWN_NET" }),
    );
    expect(await screen.findByTestId("net-detail")).toHaveTextContent("R1-1");
  });
  it("runs selected rule and exposes unknown cause and evidence", async () => {
    const fn = mockApi();
    localStorage.setItem("circuitagent.designIds", JSON.stringify([id]));
    show();
    await screen.findByText("Own fixture catalog");
    await userEvent.click(
      screen.getByRole("button", { name: "Rule 검증 실행" }),
    );
    expect(await screen.findByTestId("audit-results")).toHaveTextContent(
      "Own MPN unavailable",
    );
    expect(
      fn.mock.calls.find((x) => x[1]?.method === "POST")?.[1]?.body,
    ).toContain("own-ui-rule");
  });
  it("loads stored history detail without running a new audit", async () => {
    const fn = mockApi();
    localStorage.setItem("circuitagent.designIds", JSON.stringify([id]));
    show();
    await userEvent.click(
      await screen.findByRole("button", { name: "검사 상세" }),
    );
    expect(await screen.findByTestId("audit-results")).toHaveTextContent(
      "Own missing input",
    );
    expect(fn.mock.calls.filter((x) => x[1]?.method === "POST")).toHaveLength(
      0,
    );
  });
  it("shows backend errors instead of a fake success", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => response({ message: "Own input rejected" }, 400)),
    );
    show();
    fireEvent.change(screen.getByLabelText("BOM XLSX"), {
      target: { files: [new File(["x"], "own.xlsx")] },
    });
    fireEvent.change(screen.getByLabelText("QCV Netlist"), {
      target: { files: [new File(["x"], "own.qcv")] },
    });
    fireEvent.submit(
      screen.getByRole("button", { name: "회로 업로드" }).closest("form")!,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Own input rejected",
    );
    expect(screen.queryByTestId("import-summary")).not.toBeInTheDocument();
  });
});
