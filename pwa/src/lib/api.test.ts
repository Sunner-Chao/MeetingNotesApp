import { afterEach, describe, expect, it, vi } from "vitest";
import { login, requestRegistrationCode, verifyEmailRegistration } from "./api";
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

describe("account authentication", () => {
  it("logs in with username and password", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ access_token: "token" }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await login(config, "user", "password");

    expect(fetchMock).toHaveBeenCalledWith("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "user", password: "password" })
    });
  });

  it("requests an email code for registration", async () => {
    const delivery = {
      status: "sent",
      channel: "email",
      masked_identifier: "u***@example.com",
      expires_in: 600,
      retry_after: 60
    };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(delivery), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(requestRegistrationCode(config, "user@example.com")).resolves.toEqual(delivery);
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/code/request", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ channel: "email", identifier: "user@example.com", purpose: "register" })
    });
  });

  it("verifies the email code and creates the account", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ access_token: "token" }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await verifyEmailRegistration(config, "新用户", "user@example.com", "password", "123456");

    expect(fetchMock).toHaveBeenCalledWith("/api/auth/register/verify", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        channel: "email",
        identifier: "user@example.com",
        code: "123456",
        username: "新用户",
        password: "password"
      })
    });
  });

  it("translates FastAPI password validation details", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      detail: [{
        type: "string_too_short",
        loc: ["body", "password"],
        msg: "String should have at least 8 characters"
      }]
    }), { status: 422, headers: { "Content-Type": "application/json" } })));

    await expect(verifyEmailRegistration(config, "user", "user@example.com", "short", "123456"))
      .rejects.toThrow("密码需要 8 至 128 个字符");
  });

  it("keeps account-service error messages", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      detail: "用户名或密码错误"
    }), { status: 401, headers: { "Content-Type": "application/json" } })));

    await expect(login(config, "user", "password"))
      .rejects.toThrow("用户名或密码错误");
  });
});
