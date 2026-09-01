import { Bell, CheckCheck, Gift, Users } from "lucide-react";
import { useMemo, useState } from "react";
import type { GrowthOverview, SystemMessage } from "../types";

interface NotificationScreenProps {
  growth?: GrowthOverview;
  messages: SystemMessage[];
  onOpenCampaign: (campaignId: string) => void;
  onMarkRead: (messageId: string) => Promise<void>;
  onMarkAllRead: () => Promise<void>;
}

type NotificationFilter = "all" | "benefits";

export function NotificationScreen({ growth, messages, onOpenCampaign, onMarkRead, onMarkAllRead }: NotificationScreenProps) {
  const [filter, setFilter] = useState<NotificationFilter>("all");
  const campaigns = useMemo(() => growth?.campaigns ?? [], [growth]);
  const channel = growth?.private_channel;

  return (
    <div className="screen notification-screen">
      <header className="screen-header notification-header">
        <div><span className="eyebrow">及时了解新动态</span><h1>通知中心</h1></div>
        <div className="notification-header-actions">
          <span className="notification-status"><Bell /> {messages.filter((message) => !message.read_at).length} 条未读</span>
          {messages.some((message) => !message.read_at) && <button className="text-button" onClick={() => void onMarkAllRead()}><CheckCheck /> 全部已读</button>}
        </div>
      </header>

      <div className="notification-tabs" role="tablist" aria-label="通知类型">
        <button role="tab" aria-selected={filter === "all"} className={filter === "all" ? "active" : ""} onClick={() => setFilter("all")}>全部</button>
        <button role="tab" aria-selected={filter === "benefits"} className={filter === "benefits" ? "active" : ""} onClick={() => setFilter("benefits")}>活动与福利</button>
      </div>

      {filter === "all" && messages.length > 0 && (
        <section className="notification-message-list" aria-label="系统通知">
          {messages.map((message) => (
            <article className={`notification-message${message.read_at ? " is-read" : ""}`} key={message.id}>
              <span className="notification-icon"><Bell /></span>
              <div><strong>{message.title}</strong><p>{message.body}</p><small>{new Date(message.created_at * 1000).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })}</small></div>
              {!message.read_at && <button className="icon-button" title="标记已读" onClick={() => void onMarkRead(message.id)}><CheckCheck /></button>}
            </article>
          ))}
        </section>
      )}

      {filter === "all" && messages.length === 0 && (
        <section className="notification-welcome">
          <span className="notification-icon"><Bell /></span>
          <div><strong>智悟本服务通知</strong><p>会议转写、纪要生成和账户变更会在这里提醒你。</p></div>
          <small>刚刚</small>
        </section>
      )}

      {(filter === "all" || filter === "benefits") && (
        <section className="notification-section">
          <div className="section-heading"><div><Gift /><h2>活动与福利</h2></div><span>{campaigns.length}</span></div>
          {campaigns.length > 0 ? (
            <div className="notification-campaign-list">
              {campaigns.map((campaign) => (
                <button className="notification-campaign" key={campaign.id} onClick={() => onOpenCampaign(campaign.id)}>
                  <span className="notification-campaign-mark"><Gift /></span>
                  <span className="notification-campaign-copy"><strong>{campaign.title}</strong><small>{campaign.summary}</small><em>{new Date(campaign.starts_at * 1000).toLocaleDateString("zh-CN")} - {new Date(campaign.ends_at * 1000).toLocaleDateString("zh-CN")}</em></span>
                  <span className="campaign-arrow" aria-hidden="true">›</span>
                </button>
              ))}
            </div>
          ) : <div className="empty-state">暂无进行中的活动</div>}
        </section>
      )}

      {channel && (filter === "all" || filter === "benefits") && (
        <section className="notification-channel">
          <div className="notification-channel-copy"><span className="eyebrow">私域福利</span><h2>{channel.name}</h2><p>{channel.slogan}</p><small>入群即送 {channel.reward?.quantity ?? 0} 积分</small></div>
          {channel.qr_image_url ? <img src={channel.qr_image_url} alt={`${channel.name}二维码`} /> : <div className="notification-channel-placeholder"><Users /></div>}
          {channel.join_url && <a className="secondary-button" href={channel.join_url} target="_blank" rel="noreferrer"><Users />打开入群链接</a>}
        </section>
      )}
    </div>
  );
}
