import { Bot, Check, Download, LogOut, Save, Server, UserRound } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { AccountProfile, RuntimeConfig } from "../types";
import { BrandMark } from "./BrandMark";

interface ProfileScreenProps {
  profile: AccountProfile;
  config: RuntimeConfig;
  online: boolean;
  cloudState: "idle" | "syncing" | "synced" | "pending";
  installAvailable: boolean;
  onInstall: () => void;
  onSaveProfile: (displayName: string, avatarDataUrl?: string | null) => Promise<void>;
  onSaveConfig: (config: RuntimeConfig) => void;
  onLogout: () => void;
}

async function resizeAvatar(file: File): Promise<string> {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 512 / bitmap.width, 512 / bitmap.height);
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  const context = canvas.getContext("2d");
  if (!context) throw new Error("无法处理头像图片");
  context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  return canvas.toDataURL("image/jpeg", 0.84);
}

export function ProfileScreen({
  profile,
  config,
  online,
  cloudState,
  installAvailable,
  onInstall,
  onSaveProfile,
  onSaveConfig,
  onLogout
}: ProfileScreenProps) {
  const [draftConfig, setDraftConfig] = useState(config);
  const [displayName, setDisplayName] = useState(profile.display_name || profile.username);
  const [avatar, setAvatar] = useState<string | null | undefined>(profile.avatar_data_url);
  const [savingProfile, setSavingProfile] = useState(false);
  const avatarInput = useRef<HTMLInputElement>(null);
  const quota = profile.quota;
  const quotaPercent = quota.request_limit > 0 ? Math.min(100, (quota.requests_used / quota.request_limit) * 100) : 0;
  const connectionLabel = !online
    ? "离线"
    : cloudState === "syncing"
      ? "同步中"
      : cloudState === "synced"
        ? "云端已同步"
        : cloudState === "pending"
          ? "等待同步"
          : "服务在线";

  useEffect(() => setDraftConfig(config), [config]);
  useEffect(() => {
    setDisplayName(profile.display_name || profile.username);
    setAvatar(profile.avatar_data_url);
  }, [profile]);

  const saveProfile = async () => {
    setSavingProfile(true);
    try {
      await onSaveProfile(displayName.trim(), avatar);
    } finally {
      setSavingProfile(false);
    }
  };

  return (
    <div className="screen profile-screen">
      <header className="screen-header profile-header">
        <div><span className="eyebrow">智悟本账户</span><h1>我的</h1></div>
        <span className={`connection-state ${!online ? "offline" : cloudState === "pending" ? "pending" : "online"}`}>{connectionLabel}</span>
      </header>

      <section className="profile-identity">
        <button className="avatar-button" onClick={() => avatarInput.current?.click()} title="修改头像">
          {avatar ? <img src={avatar} alt="当前头像" /> : <BrandMark size={72} />}
        </button>
        <div>
          <strong>{displayName || profile.username}</strong>
          <span>{profile.username}</span>
          <em>{profile.is_admin ? "管理员" : profile.vip_enabled ? profile.plan_name : "Free"}</em>
        </div>
        <input
          ref={avatarInput}
          hidden
          type="file"
          accept="image/*"
          onChange={async (event) => {
            const file = event.target.files?.[0];
            if (file) setAvatar(await resizeAvatar(file));
            event.currentTarget.value = "";
          }}
        />
      </section>

      <section className="quota-band">
        <div className="quota-copy">
          <span>AI 处理额度</span>
          <strong>{quota.requests_remaining.toLocaleString()}<small> 次可用</small></strong>
        </div>
        <div className="quota-track"><span style={{ width: `${quotaPercent}%` }} /></div>
        <small>本期已使用 {quota.requests_used.toLocaleString()} / {quota.request_limit.toLocaleString()}</small>
      </section>

      <section className="settings-section">
        <div className="section-heading"><div><UserRound /><h2>个人资料</h2></div></div>
        <div className="setting-row input-row">
          <label><span>昵称</span><input value={displayName} maxLength={40} onChange={(event) => setDisplayName(event.target.value)} /></label>
          <button className="icon-button emphasized" title="保存资料" disabled={savingProfile} onClick={saveProfile}>{savingProfile ? <Check /> : <Save />}</button>
        </div>
      </section>

      <section className="settings-section woo-settings">
        <div className="section-heading"><div><Bot /><h2>智能体 · 小Woo</h2></div></div>
        <div className="setting-row">
          <span><strong>智能体</strong><small>会议整理服务</small></span>
          <select value={draftConfig.agentProvider} onChange={(event) => setDraftConfig({ ...draftConfig, agentProvider: event.target.value as RuntimeConfig["agentProvider"] })}>
            <option value="codex-cli">小Woo · Codex</option>
            <option value="claude-cli">小Woo · Claude</option>
          </select>
        </div>
        <div className="setting-row">
          <span><strong>推理强度</strong><small>默认使用中等</small></span>
          <div className="segmented compact-segmented">
            {(["low", "medium", "high"] as const).map((effort) => (
              <button key={effort} className={draftConfig.reasoningEffort === effort ? "active" : ""} onClick={() => setDraftConfig({ ...draftConfig, reasoningEffort: effort })}>
                {{ low: "低", medium: "中", high: "高" }[effort]}
              </button>
            ))}
          </div>
        </div>
      </section>

      <section className="settings-section">
        <div className="section-heading"><div><Server /><h2>服务设置</h2></div></div>
        <label className="full-setting-field">
          <span>账户服务地址</span>
          <input
            type="url"
            inputMode="url"
            value={draftConfig.apiBase}
            onChange={(event) => setDraftConfig({ ...draftConfig, apiBase: event.target.value })}
            placeholder="留空使用当前服务器"
          />
        </label>
        <button className="secondary-button full-button" onClick={() => onSaveConfig({ ...draftConfig, apiBase: draftConfig.apiBase.trim() })}><Save /> 保存服务设置</button>
      </section>

      <section className="profile-actions">
        {installAvailable && <button className="secondary-button" onClick={onInstall}><Download /> 安装智悟本</button>}
        <button className="ghost-button danger-copy" onClick={onLogout}><LogOut /> 退出账户</button>
      </section>
    </div>
  );
}
