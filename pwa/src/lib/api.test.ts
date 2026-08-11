import { afterEach, describe, expect, it, vi } from "vitest";
import { authenticate } from "./api";
import type { RuntimeConfig } from "../types";

const config: RuntimeConfig = {
  apiBase: "",
  agentProvider: "codex-cli",
  reasoningEffort: "medium",
  defaultTemplate: "project"
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("authentication errors", () => {
  it("translates FastAPI password validation details", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      detail: [{
        type: "string_too_short",
        loc: ["body", "password"],
        msg: "String should have at least 8 characters"
      }]
    }), { status: 422, headers: { "Content-Type": "application/json" } })));

    await expect(authenticate(config, "user", "short", true))
      .rejects.toThrow("密码需要 8 至 128 个字符");
  });

  it("keeps account-service error messages", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      detail: "用户名或密码错误"
    }), { status: 401, headers: { "Content-Type": "application/json" } })));

    await expect(authenticate(config, "user", "password", false))
      .rejects.toThrow("用户名或密码错误");
  });
});
