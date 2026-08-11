export type TemplateKey = "project" | "administrative" | "brainstorming" | "inspection";
export type AgentProvider = "codex-cli" | "claude-cli";
export type ReasoningEffort = "low" | "medium" | "high";

export interface AccountQuota {
  request_limit: number;
  requests_used: number;
  requests_remaining: number;
}

export interface AccountProfile {
  id: string;
  username: string;
  display_name: string;
  avatar_data_url?: string | null;
  role: string;
  is_admin: boolean;
  vip_enabled: boolean;
  plan_code?: string;
  plan_name: string;
  quota: AccountQuota;
}

export interface AuthSession {
  access_token: string;
  agent_access_token: string;
  stt_access_token?: string | null;
  expires_at: number;
  user: AccountProfile;
}

export interface RuntimeConfig {
  apiBase: string;
  agentProvider: AgentProvider;
  reasoningEffort: ReasoningEffort;
  defaultTemplate: TemplateKey;
}

export interface MeetingImage {
  id: string;
  name: string;
  type: string;
  blob: Blob;
}

export interface Meeting {
  id: string;
  title: string;
  templateKey: TemplateKey;
  createdAt: number;
  updatedAt: number;
  durationSeconds: number;
  transcript: string;
  report: string;
  audio?: Blob;
  audioName?: string;
  audioType?: string;
  images: MeetingImage[];
}

export interface MeetingTemplate {
  key: TemplateKey;
  name: string;
  subtitle: string;
  content: string;
}

export type ProcessingStage =
  | { kind: "idle" }
  | { kind: "uploading"; progress: number }
  | { kind: "transcribing" }
  | { kind: "generating" };
