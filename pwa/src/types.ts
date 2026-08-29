export type TemplateKey = "project" | "administrative" | "brainstorming" | "inspection";
export type AgentProvider = "codex-cli" | "claude-cli";
export type ReasoningEffort = "low" | "medium" | "high";

export interface AccountQuota {
  request_limit: number;
  requests_used: number;
  requests_remaining: number;
}

export interface AccountUsage {
  points_granted: number;
  points_used: number;
  points_remaining: number;
}

export interface AuthCodeDelivery {
  status: "sent";
  channel: "email";
  masked_identifier: string;
  expires_in: number;
  retry_after: number;
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
  usage?: AccountUsage;
  quota: AccountQuota;
}

export interface PrivateChannel {
  id: string;
  name: string;
  qr_image_url: string;
  join_url: string;
  short_url: string;
  slogan: string;
  reward_type: string;
  reward?: { quantity?: number };
  enabled: boolean;
}

export interface GrowthCampaign {
  id: string;
  title: string;
  campaign_type: string;
  summary: string;
  rules: Record<string, unknown>;
  reward_pool: Record<string, unknown>;
  starts_at: number;
  ends_at: number;
  status: string;
}

export interface GrowthCampaignDetail extends GrowthCampaign {
  settled_at?: number | null;
  joined: boolean;
  my_score: number;
  my_rank?: number | null;
  actions: Array<{ action_type: string; action_key: string; score: number; status: string; created_at: number }>;
  leaderboard: Array<{ user_id: string; display_name: string; score: number; rank?: number | null }>;
}

export interface GrowthOverview {
  referral: { code: string; successful_invites: number; pending_rewards: number; reward_points: number; share_path: string };
  rewards: Record<string, number>;
  campaigns: GrowthCampaign[];
  private_channel?: PrivateChannel | null;
}

export interface AuthSession {
  access_token: string;
  agent_access_token: string;
  stt_access_token?: string | null;
  expires_at: number;
  user: AccountProfile;
}

export interface SocialAuthProvider {
  id: string;
  name: string;
  enabled: boolean;
  configured: boolean;
  status: "available" | "not_configured";
  unavailable_reason: string;
  authorization_url: string;
  start_path: string;
  tier: "consumer" | "team";
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
