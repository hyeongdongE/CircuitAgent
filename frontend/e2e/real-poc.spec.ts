// 실제 공개 BOM/QCV와 실행 중인 Backend·MySQL을 사용하는 브라우저 테스트이다. API 응답을 가짜 데이터로 대체하지 않는다.
import { test, expect } from "@playwright/test";
import path from "node:path";
test("real Sparrow upload → graph → audit → persisted history → reload", async ({
  page,
}) => {
  await page.goto("/");
  await page
    .getByLabel("설계 이름", { exact: true })
    .fill("Browser FE " + Date.now());
  await page
    .getByLabel("BOM XLSX")
    .setInputFiles(path.resolve("../.local/qcv-fixtures/fe.xlsx"));
  await page
    .getByLabel("QCV Netlist")
    .setInputFiles(path.resolve("../.local/qcv-fixtures/fe.qcv"));
  const uploaded = page.waitForResponse(
    (r) =>
      r.url().endsWith("/api/designs/import") &&
      r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "회로 업로드", exact: true }).click();
  expect((await uploaded).status()).toBe(201);
  await expect(page.getByTestId("import-summary")).toContainText(
    "Component 453",
  );
  await expect(page.getByTestId("import-summary")).toContainText(
    "Connection 1500",
  );
  await page.getByLabel("Component 검색").fill("U16");
  await page.getByRole("button", { name: "U16 · FITTED", exact: true }).click();
  await expect(page.getByTestId("component-detail")).toContainText(
    "TPS54302DDC",
  );
  await page.getByRole("button", { name: "U16_EN", exact: true }).click();
  await expect(page.getByTestId("net-detail")).toContainText("R107-2");
  await expect(page.getByTestId("net-detail")).toContainText("R83-2");
  await page.getByRole("button", { name: "R107-2", exact: true }).click();
  await expect(page.getByTestId("component-detail")).toContainText(
    "R107 · DNP",
  );
  const audited = page.waitForResponse(
    (r) => r.url().endsWith("/audits") && r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Rule 검증 실행", exact: true })
    .click();
  expect((await audited).status()).toBe(201);
  await expect(page.getByTestId("audit-results")).toContainText(
    "11.961581395348837209",
  );
  await expect(page.getByTestId("audit-results")).toContainText(
    "Verified resistor tolerance",
  );
  await expect(
    page.getByRole("button", { name: "검사 상세", exact: true }),
  ).toHaveCount(1);
  const result = page.getByTestId("audit-results");
  await expect(result.locator(".badge.PASS")).toHaveCount(1);
  await expect(result.locator(".badge.UNKNOWN")).toHaveCount(5);
  await result.getByText("Evidence / 근거", { exact: true }).first().click();
  await expect(result).toContainText("SLVSDG6C / March 2026");
  await expect(result).toContainText("NoMount");
  await page.reload();
  await page.getByRole("button", { name: "검사 상세", exact: true }).click();
  await expect(page.getByTestId("audit-results")).toContainText(
    "11.961581395348837209",
  );
});
test("invalid upload exposes backend rejection on mobile viewport", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page
    .getByLabel("설계 이름", { exact: true })
    .fill("Bad Browser " + Date.now());
  await page
    .getByLabel("BOM XLSX")
    .setInputFiles({
      name: "invalid.xlsx",
      mimeType:
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      buffer: Buffer.from("not excel"),
    });
  await page
    .getByLabel("QCV Netlist")
    .setInputFiles({
      name: "invalid.qcv",
      mimeType: "text/plain",
      buffer: Buffer.from("bad"),
    });
  await page.getByRole("button", { name: "회로 업로드", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Input parsing failed");
});
