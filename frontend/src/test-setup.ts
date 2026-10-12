import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";
// 각 테스트의 DOM을 제거하여 이전 화면과 이벤트가 다음 테스트에 영향을 주지 않게 한다.
afterEach(() => cleanup());
