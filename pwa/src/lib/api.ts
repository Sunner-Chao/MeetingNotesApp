import type { AccountProfile, AuthCodeDelivery, AuthSession, GrowthCampaignDetail, GrowthOverview, Meeting, PrivateChannel, RuntimeConfig, SocialAuthProvider } from "../types";
import { templateFor } from "../templates";

interface SessionRefreshResponse {
  agent_access_token: string;
  stt_access_token?: string | null;
  expires_at: number;
  user: AccountProfile;
}

interface AgentResponse {
  text?: string;
  report?: { rawContent?: string; content?: string };
}

export interface CloudMeeting {
  id: string;
  title: string;
  template_key: Meeting["templateKey"];
  created_at: number;
  updated_at: number;
  duration_seconds: number;
  transcript: string;
  report: string;
}

export interface CloudMeetingSnapshot {
  meetings: CloudMeeting[];
  deleted: Array<{ meeting_id: string; deleted_at: number }>;
}

export interface ArchivedAudio {
  id: string;
  meeting_id: string;
  created_at: string;
  bytes: number;
  duration_sec?: number | null;
  filename: string;
  source: string;
  download_path: string;
}

export class CloudMeetingDeletedError extends Error {}

function apiUrl(config: RuntimeConfig, path: string): string {
  const base = config.apiBase.trim().replace(/\/+$/, "");
  if (!base) return path;
  const normalized = base.endsWith("/api") ? base.slice(0, -4) : base;
  return `${normalized}${path}`;
}

async function errorMessage(response: Response): Promise<string> {
  const fallback = `服务请求失败（HTTP ${response.status}）`;
  try {
    const body = await response.json() as {
      detail?: string | Array<{ loc?: Array<string | number>; msg?: string; type?: string }>;
    };
    if (typeof body.detail === "string") return body.detail.trim() || fallback;
    if (Array.isArray(body.detail) && body.detail.length > 0) {
      const issue = body.detail[0];
      const field = issue.loc?.at(-1);
      if (field === "username") return "用户名不能为空";
      if (field === "password") return "密码需要 8 至 128 个字符";
      if (field === "identifier") return "请输入有效的邮箱地址";
      if (field === "code") return "请输入 6 位邮箱验证码";
      return issue.msg?.trim() || fallback;
    }
    return fallback;
  } catch {
    return fallback;
  }
}

async function jsonRequest<T>(url: string, init: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  if (!response.ok) throw new Error(await errorMessage(response));
  return response.json() as Promise<T>;
}

export async function login(
  config: RuntimeConfig,
  username: string,
  password: string
): Promise<AuthSession> {
  return jsonRequest<AuthSession>(apiUrl(config, "/api/auth/login"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
}

export async function fetchSocialAuthProviders(config: RuntimeConfig): Promise<SocialAuthProvider[]> {
  return jsonRequest<SocialAuthProvider[]>(apiUrl(config, "/api/auth/providers"), { method: "GET" });
}

export async function exchangeSocialAuthTicket(config: RuntimeConfig, ticket: string): Promise<AuthSession> {
  return jsonRequest<AuthSession>(apiUrl(config, "/api/auth/social/exchange"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ticket })
  });
}

export async function requestRegistrationCode(
  config: RuntimeConfig,
  email: string
): Promise<AuthCodeDelivery> {
  return jsonRequest<AuthCodeDelivery>(apiUrl(config, "/api/auth/code/request"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ channel: "email", identifier: email, purpose: "register" })
  });
}

export async function verifyEmailRegistration(
  config: RuntimeConfig,
  username: string,
  email: string,
  password: string,
  code: string,
  referralCode?: string
): Promise<AuthSession> {
  return jsonRequest<AuthSession>(apiUrl(config, "/api/auth/register/verify"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ channel: "email", identifier: email, code, username, password, referral_code: referralCode?.trim() || undefined })
  });
}

export async function fetchGrowthOverview(config: RuntimeConfig, session: AuthSession): Promise<GrowthOverview> {
  return jsonRequest<GrowthOverview>(apiUrl(config, "/api/account/growth/overview"), { headers: { Authorization: `Bearer ${session.access_token}` } });
}

export async function redeemGrowthCode(config: RuntimeConfig, session: AuthSession, code: string): Promise<{ message: string; profile: AccountProfile; private_channel?: PrivateChannel | null }> {
  return jsonRequest(apiUrl(config, "/api/account/redeem"), {
    method: "POST", headers: { Authorization: `Bearer ${session.access_token}`, "Content-Type": "application/json" }, body: JSON.stringify({ code })
  });
}

export async function fetchGrowthCampaignDetail(config: RuntimeConfig, session: AuthSession, campaignId: string): Promise<GrowthCampaignDetail> {
  return jsonRequest<GrowthCampaignDetail>(apiUrl(config, `/api/growth/campaigns/${encodeURIComponent(campaignId)}`), { headers: { Authorization: `Bearer ${session.access_token}` } });
}

async function growthAction<T>(config: RuntimeConfig, session: AuthSession, path: string, body?: unknown): Promise<T> {
  return jsonRequest<T>(apiUrl(config, path), { method: "POST", headers: { Authorization: `Bearer ${session.access_token}`, "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body) });
}

export function joinGrowthCampaign(config: RuntimeConfig, session: AuthSession, campaignId: string) { return growthAction<{ status: string }>(config, session, `/api/growth/campaigns/${encodeURIComponent(campaignId)}/join`); }
export function checkinGrowthCampaign(config: RuntimeConfig, session: AuthSession, campaignId: string) { return growthAction<{ message: string; quantity: number }>(config, session, `/api/growth/campaigns/${encodeURIComponent(campaignId)}/checkin`); }
export function answerGrowthCampaign(config: RuntimeConfig, session: AuthSession, campaignId: string, questionKey: string, answer: string) { return growthAction<{ message: string; correct: boolean; quantity: number }>(config, session, `/api/growth/campaigns/${encodeURIComponent(campaignId)}/answer`, { question_key: questionKey, answer }); }
export function drawGrowthCampaign(config: RuntimeConfig, session: AuthSession, campaignId: string) { return growthAction<{ message: string; won: boolean; quantity: number; probability: number }>(config, session, `/api/growth/campaigns/${encodeURIComponent(campaignId)}/draw`); }

export async function refreshSession(config: RuntimeConfig, current: AuthSession): Promise<AuthSession> {
  const refreshed = await jsonRequest<SessionRefreshResponse>(apiUrl(config, "/api/account/session"), {
    headers: { Authorization: `Bearer ${current.access_token}` }
  });
  return {
    ...current,
    agent_access_token: refreshed.agent_access_token,
    stt_access_token: refreshed.stt_access_token,
    expires_at: refreshed.expires_at,
    user: refreshed.user
  };
}

export async function updateProfile(
  config: RuntimeConfig,
  session: AuthSession,
  displayName: string,
  avatarDataUrl?: string | null
): Promise<AccountProfile> {
  return jsonRequest<AccountProfile>(apiUrl(config, "/api/account/me"), {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${session.access_token}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ display_name: displayName, avatar_data_url: avatarDataUrl ?? null })
  });
}

export async function fetchCloudMeetings(
  config: RuntimeConfig,
  session: AuthSession
): Promise<CloudMeetingSnapshot> {
  return jsonRequest<CloudMeetingSnapshot>(apiUrl(config, "/api/account/meetings"), {
    headers: { Authorization: `Bearer ${session.access_token}` }
  });
}

export async function saveCloudMeeting(
  config: RuntimeConfig,
  session: AuthSession,
  meeting: Meeting
): Promise<CloudMeeting> {
  const response = await fetch(apiUrl(config, `/api/account/meetings/${encodeURIComponent(meeting.id)}`), {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${session.access_token}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      title: meeting.title.trim() || "未命名会议",
      template_key: meeting.templateKey,
      created_at: meeting.createdAt,
      updated_at: meeting.updatedAt,
      duration_seconds: meeting.durationSeconds,
      transcript: meeting.transcript,
      report: meeting.report
    })
  });
  if (response.status === 409) throw new CloudMeetingDeletedError(await errorMessage(response));
  if (!response.ok) throw new Error(await errorMessage(response));
  return response.json() as Promise<CloudMeeting>;
}

export async function deleteCloudMeeting(
  config: RuntimeConfig,
  session: AuthSession,
  meetingId: string,
  deletedAt: number
): Promise<void> {
  const response = await fetch(apiUrl(config, `/api/account/meetings/${encodeURIComponent(meetingId)}?deleted_at=${deletedAt}`), {
    method: "DELETE",
    headers: { Authorization: `Bearer ${session.access_token}` }
  });
  if (!response.ok) throw new Error(await errorMessage(response));
}

export async function clearCloudMeetings(
  config: RuntimeConfig,
  session: AuthSession,
  deletedAt: number
): Promise<void> {
  const response = await fetch(apiUrl(config, `/api/account/meetings?deleted_at=${deletedAt}`), {
    method: "DELETE",
    headers: { Authorization: `Bearer ${session.access_token}` }
  });
  if (!response.ok) throw new Error(await errorMessage(response));
}

export async function listArchivedAudio(
  config: RuntimeConfig,
  session: AuthSession,
  meetingId: string
): Promise<ArchivedAudio[]> {
  const result = await jsonRequest<{ items: ArchivedAudio[] }>(
    apiUrl(config, `/api/stt/audio-archive?meeting_id=${encodeURIComponent(meetingId)}`),
    { headers: { Authorization: `Bearer ${session.access_token}` } }
  );
  return result.items;
}

export async function downloadArchivedAudio(
  config: RuntimeConfig,
  session: AuthSession,
  archive: ArchivedAudio
): Promise<Blob> {
  const response = await fetch(apiUrl(config, `/api/stt/audio-archive/${encodeURIComponent(archive.id)}`), {
    headers: { Authorization: `Bearer ${session.access_token}` }
  });
  if (!response.ok) throw new Error(await errorMessage(response));
  return response.blob();
}

export function transcribeAudio(
  config: RuntimeConfig,
  session: AuthSession,
  meeting: Meeting,
  onProgress: (progress: number) => void,
  signal: AbortSignal
): Promise<string> {
  return new Promise((resolve, reject) => {
    if (!meeting.audio) {
      reject(new Error("请先录音或导入音频"));
      return;
    }
    const request = new XMLHttpRequest();
    const abort = () => request.abort();
    signal.addEventListener("abort", abort, { once: true });
    request.open("POST", apiUrl(config, "/api/stt/transcribe"));
    request.timeout = 30 * 60 * 1000;
    request.setRequestHeader("Authorization", `Bearer ${session.access_token}`);
    request.setRequestHeader("X-Meeting-Id", meeting.id);
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100));
    };
    request.onload = () => {
      signal.removeEventListener("abort", abort);
      try {
        const payload = JSON.parse(request.responseText) as { text?: string; detail?: string };
        if (request.status < 200 || request.status >= 300) {
          reject(new Error(payload.detail || `转写失败（HTTP ${request.status}）`));
          return;
        }
        const text = payload.text?.trim();
        if (!text) throw new Error("服务端没有返回有效转写内容");
        resolve(text);
      } catch (error) {
        reject(error instanceof Error ? error : new Error("服务端返回格式无效"));
      }
    };
    request.onerror = () => reject(new Error("无法连接转写服务"));
    request.ontimeout = () => reject(new Error("转写超时，请稍后重试"));
    request.onabort = () => reject(new DOMException("操作已取消", "AbortError"));

    const form = new FormData();
    form.append("file", meeting.audio, meeting.audioName || `meeting-${meeting.id}.webm`);
    request.send(form);
  });
}

export async function generateReport(
  config: RuntimeConfig,
  session: AuthSession,
  meeting: Meeting,
  signal: AbortSignal
): Promise<string> {
  const template = templateFor(meeting.templateKey);
  const payload = {
    provider: config.agentProvider,
    operation: "generate_report",
    model_reasoning_effort: config.reasoningEffort,
    effort: config.reasoningEffort,
    transcript: meeting.transcript,
    templateName: template.name,
    templateContent: template.content
  };
  const form = new FormData();
  form.append("request", JSON.stringify(payload));
  for (const image of meeting.images) form.append("attachments", image.blob, image.name);
  const response = await fetch(apiUrl(config, "/api/agent"), {
    method: "POST",
    headers: { Authorization: `Bearer ${session.agent_access_token}` },
    body: form,
    signal
  });
  if (!response.ok) throw new Error(await errorMessage(response));
  const result = await response.json() as AgentResponse;
  const output = result.report?.rawContent || result.report?.content || result.text || "";
  if (!output.trim()) throw new Error("没有返回有效纪要内容");
  return output.trim();
}
