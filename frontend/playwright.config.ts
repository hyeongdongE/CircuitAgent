import { defineConfig, devices } from "@playwright/test";
// 실제 MySQL과 Backend는 먼저 실행한다. 이 설정은 Vite와 Chromium만 준비하며 저장된 회로를 사용하는 테스트를 순차 실행한다.
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 120000,
  expect: { timeout: 15000 },
  use: { baseURL: "http://127.0.0.1:5173", trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run dev -- --port 5173 --strictPort",
    url: "http://127.0.0.1:5173",
    reuseExistingServer: !process.env.CI,
  },
});
