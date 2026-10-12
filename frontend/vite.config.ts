import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
// 개발 서버는 로컬에서만 열고 /api 요청을 Backend로 전달한다. jsdom 테스트와 실제 브라우저 테스트는 별도로 실행한다.
export default defineConfig({
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.BACKEND_URL ?? "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    include: ["src/**/*.test.ts*"],
    restoreMocks: true,
  },
});
