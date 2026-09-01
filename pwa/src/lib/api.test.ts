import { afterEach, describe, expect, it, vi } from "vitest";
import { createCommunityDraft, createCommunityMediaManifest, fetchMyCommunityPosts, fetchPublicCommunityPosts, login, requestRegistrationCode, sha256Hex, updateCommunityDraft, uploadCommunityMediaVariant, verifyEmailRegistration } from "./api";
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

describe("public community", () => {
  it("builds the public post search query", async () => {
    const payload = { items: [], next_cursor: null };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(payload), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchPublicCommunityPosts(config, { query: "杭州", destination: "杭州", hasMedia: true })).resolves.toEqual(payload);
    expect(fetchMock).toHaveBeenCalledWith("/api/community/posts?q=%E6%9D%AD%E5%B7%9E&destination=%E6%9D%AD%E5%B7%9E&has_media=true", { headers: undefined });
  });

  it("loads owner-scoped community posts with the account token", async () => {
    const payload = { items: [], next_cursor: null };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(payload), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);
    const session = {
      access_token: "account-token",
      agent_access_token: "agent-token",
      expires_at: 2_000_000_000,
      user: { id: "user-1" }
    } as never;

    await expect(fetchMyCommunityPosts(config, session, 100)).resolves.toEqual(payload);
    expect(fetchMock).toHaveBeenCalledWith("/api/account/community/posts?limit=50", {
      headers: { Authorization: "Bearer account-token" }
    });
  });

  it("creates and updates a community draft with the account token", async () => {
    const payload = {
      client_snapshot_id: "web-meeting-1",
      journey_id: "web-journey-meeting-1",
      journey_edition_id: "web-edition-meeting-1",
      source_edition_version: 1,
      title: "良渚研学记录",
      content: "现场观察与收获。",
      ai_assisted: true,
      redacted_coordinate_count: 0,
      privacy_reviewed: true,
      rights_confirmed: true
    };
    const response = { id: "post-1", title: payload.title };
    const makeResponse = () => new Response(JSON.stringify(response), {
      status: 201,
      headers: { "Content-Type": "application/json" }
    });
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(makeResponse()));
    vi.stubGlobal("fetch", fetchMock);
    const session = { access_token: "account-token" } as never;

    await expect(createCommunityDraft(config, session, payload)).resolves.toEqual(response);
    expect(fetchMock).toHaveBeenLastCalledWith("/api/account/community/drafts", {
      method: "POST",
      headers: { Authorization: "Bearer account-token", "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });

    await expect(updateCommunityDraft(config, session, "post-1", payload)).resolves.toEqual(response);
    expect(fetchMock).toHaveBeenLastCalledWith("/api/account/community/drafts/post-1", {
      method: "PUT",
      headers: { Authorization: "Bearer account-token", "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
  });

  it("uploads community media with resumable range headers", async () => {
    const blob = new Blob(["community-image"] , { type: "image/jpeg" });
    const digest = await sha256Hex(blob);
    const manifest = {
      id: "media-1",
      client_media_id: "image-1",
      display_name: "现场.jpg",
      mime_type: "image/jpeg",
      original_bytes: blob.size,
      original_sha256: digest,
      thumbnail_bytes: blob.size,
      thumbnail_sha256: digest,
      original_total_bytes: blob.size,
      original_received_bytes: 0,
      thumbnail_total_bytes: blob.size,
      thumbnail_received_bytes: 0,
      status: "pending",
      created_at: 1,
      updated_at: 1
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(manifest), { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...manifest, original_received_bytes: blob.size, status: "uploading" }), { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [{ ...manifest, original_received_bytes: blob.size, status: "uploading" }] }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const session = { access_token: "account-token" } as never;

    await createCommunityMediaManifest(config, session, "post-1", manifest);
    await uploadCommunityMediaVariant(config, session, "post-1", "media-1", "original", blob, 0);
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/account/community/posts/post-1/media/media-1/original", expect.objectContaining({
      method: "PUT",
      headers: expect.objectContaining({
        Authorization: "Bearer account-token",
        "Content-Range": `bytes 0-${blob.size - 1}/${blob.size}`,
        "X-Chunk-SHA256": digest
      })
    }));
  });
});
