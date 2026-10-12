import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import "./style.css";
// 조회는 30초 동안 캐시한다. 자동 재시도는 꺼 두어 업로드·Audit 요청이 실패 후 중복 실행되지 않게 한다.
const client = new QueryClient({
  defaultOptions: {
    queries: { retry: false, staleTime: 30000 },
    mutations: { retry: false },
  },
});
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
);
