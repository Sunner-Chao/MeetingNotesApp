import { Bot, Check, Copy, Download, Gift, LogOut, Save, Server, Share2, UserRound, Users } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { AccountProfile, GrowthOverview, RuntimeConfig } from "../types";
import { BrandMark } from "./BrandMark";

interface ProfileScreenProps {
  profile: AccountProfile;
  growth?: GrowthOverview;
  config: RuntimeConfig;
  online: boolean;
  cloudState: "idle" | "syncing" | "synced" | "pending";
  installAvailable: boolean;
  onInstall: () => void;
  onSaveProfile: (displayName: string, avatarDataUrl?: string | null) => Promise<void>;
  onSaveConfig: (config: RuntimeConfig) => void;
  onLogout: () => void;
  onRedeem: (code: string) => Promise<void>;
  onOpenCampaign: (campaignId: string) => void;
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
  growth,
  config,
  online,
  cloudState,
  installAvailable,
  onInstall,
  onSaveProfile,
  onSaveConfig,
  onLogout,
  onRedeem,
  onOpenCampaign
}: ProfileScreenProps) {
  const [draftConfig, setDraftConfig] = useState(config);
  const [displayName, setDisplayName] = useState(profile.display_name || profile.username);
  const [avatar, setAvatar] = useState<string | null | undefined>(profile.avatar_data_url);
  const [savingProfile, setSavingProfile] = useState(false);
  const [redeemCode, setRedeemCode] = useState("");
  const [redeeming, setRedeeming] = useState(false);
  const avatarInput = useRef<HTMLInputElement>(null);
  const pointsGranted = Math.max(0, profile.usage?.points_granted ?? 0);
  const pointsUsed = Math.max(0, profile.usage?.points_used ?? 0);
  const pointsRemaining = Math.max(0, profile.usage?.points_remaining ?? pointsGranted - pointsUsed);
  const pointsPercent = pointsGranted > 0 ? Math.min(100, (pointsUsed / pointsGranted) * 100) : 0;
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

  const redeem = async () => {
    if (!redeemCode.trim()) return;
    setRedeeming(true);
    try { await onRedeem(redeemCode.trim()); setRedeemCode(""); } finally { setRedeeming(false); }
  };
  const copy = (value: string) => { if (value) void navigator.clipboard?.writeText(value); };
  const channel = growth?.private_channel;
  const inviteLink = growth?.referral ? `${window.location.origin}${growth.referral.share_path}` : "";

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
          <em>{profile.is_admin ? "管理员账户" : "积分账户"}</em>
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

      <section className="growth-grid">
        <article className="growth-card"><div className="section-heading"><div><Gift /><h2>兑换中心</h2></div></div><p>输入福利码，立即领取积分和专属权益。</p><div className="redeem-form"><input value={redeemCode} onChange={(event) => setRedeemCode(event.target.value.toUpperCase())} placeholder="输入礼品码 / 兑换码" /><button className="primary-button" disabled={redeeming || !redeemCode.trim()} onClick={() => void redeem()}>{redeeming ? "兑换中" : "立即兑换"}</button></div></article>
        <article className="growth-card"><div className="section-heading"><div><Users /><h2>邀请好友</h2></div></div><p>{growth ? `分享邀请链接，好友注册时会自动带入邀请码，双方各得 ${growth.referral.reward_points} 积分。` : "正在加载邀请奖励..."}</p><div className="invite-code"><strong>{growth?.referral?.code || "加载中"}</strong><button className="icon-button" title="复制邀请码" onClick={() => copy(growth?.referral?.code || "")}><Copy /></button></div><div className="growth-meta"><span>已邀请 {growth?.referral?.successful_invites ?? 0} 人</span><button className="text-button" onClick={() => copy(inviteLink)}><Share2 />复制邀请链接</button></div></article>
      </section>

      {channel && <section className="private-channel-card"><div className="channel-copy"><span className="eyebrow">福利群</span><h2>{channel.name}</h2><p>{channel.slogan}</p><small>入群即送 {channel.reward?.quantity ?? 50} 积分</small></div>{channel.qr_image_url && <img src={channel.qr_image_url} alt="福利群二维码" />}<div className="channel-actions">{channel.join_url && <a className="primary-button" href={channel.join_url} target="_blank" rel="noreferrer"><Users />打开入群链接</a>}{channel.join_url && <button className="secondary-button" onClick={() => copy(channel.join_url)}><Copy />复制链接</button>}</div></section>}

      {growth?.campaigns?.length ? <section className="growth-campaigns"><div className="section-heading"><div><Gift /><h2>近期活动</h2></div></div>{growth.campaigns.slice(0, 3).map((campaign) => <button className="growth-campaign-row" key={campaign.id} onClick={() => onOpenCampaign(campaign.id)}><span><strong>{campaign.title}</strong><p>{campaign.summary}</p><small>{new Date(campaign.starts_at * 1000).toLocaleDateString("zh-CN")} - {new Date(campaign.ends_at * 1000).toLocaleDateString("zh-CN")}</small></span><span className="campaign-arrow">›</span></button>)}</section> : null}

      <section className="points-band">
        <div className="points-copy">
          <span>可用积分</span>
          <strong>{pointsRemaining.toLocaleString()}<small> 积分</small></strong>
        </div>
        <div className="points-track"><span style={{ width: `${pointsPercent}%` }} /></div>
        <small>本期已使用 {pointsUsed.toLocaleString()} 积分，共获得 {pointsGranted.toLocaleString()} 积分</small>
      </section>

      <section className="settings-section">
        <div className="section-heading"><div><UserRound /><h2>个人资料</h2></div></div>
        <div className="setting-row input-row">
          <label><span>昵称</span><input value={displayName} maxLength={40} onChange={(event) => setDisplayName(event.target.value)} /></label>
          <button className="icon-button emphasized" title="保存资料" disabled={savingProfile} onClick={saveProfile}>{savingProfile ? <Check /> : <Save />}</button>
        </div>
      </section>

      <section className="settings-section woo-settings">
        <div className="section-heading"><div><Bot /><h2>会议整理</h2></div></div>
        <div className="setting-row">
          <span><strong>智能体</strong><small>会议整理服务</small></span>
          <select value={draftConfig.agentProvider} onChange={(event) => setDraftConfig({ ...draftConfig, agentProvider: event.target.value as RuntimeConfig["agentProvider"] })}>
            <option value="codex-cli">Codex 服务</option>
            <option value="claude-cli">Claude 服务</option>
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
