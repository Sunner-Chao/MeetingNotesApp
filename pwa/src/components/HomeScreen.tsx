import { FileAudio, FileText, Gift, Mic, Plus, Sparkles } from "lucide-react";
import { useRef } from "react";
import { MEETING_TEMPLATES } from "../templates";
import type { AccountProfile, GrowthOverview, Meeting, TemplateKey } from "../types";
import { formatMeetingDate } from "../lib/format";

interface HomeScreenProps {
  profile: AccountProfile;
  growth?: GrowthOverview;
  meetings: Meeting[];
  selectedTemplate: TemplateKey;
  onTemplateChange: (key: TemplateKey) => void;
  onCreate: (mode: "record" | "text") => void;
  onImportAudio: (file: File) => void;
  onOpenMeeting: (meeting: Meeting) => void;
  onOpenHistory: () => void;
  onOpenCampaign: (campaignId: string) => void;
}

export function HomeScreen({
  profile,
  growth,
  meetings,
  selectedTemplate,
  onTemplateChange,
  onCreate,
  onImportAudio,
  onOpenMeeting,
  onOpenHistory,
  onOpenCampaign
}: HomeScreenProps) {
  const audioInput = useRef<HTMLInputElement>(null);
  const displayName = profile.display_name.trim() || profile.username;
  const pointsRemaining = profile.usage?.points_remaining;
  const recent = meetings.slice(0, 4);
  const featured = growth?.campaigns?.[0];

  return (
    <div className="screen home-screen">
      <header className="screen-header home-header">
        <div>
          <h1>{displayName}，开始记录</h1>
        </div>
        <div className="plan-chip">{pointsRemaining === undefined ? "积分账户" : `${pointsRemaining.toLocaleString()} 积分`}</div>
      </header>

      {featured && <section className="growth-announcement"><span className="announcement-mark"><Gift /></span><span><strong>本周福利 · {featured.title}</strong><small>{featured.summary}</small></span><button className="text-button" onClick={() => onOpenCampaign(featured.id)}>查看活动</button></section>}

      <section className="quick-actions" aria-label="创建会议">
        <button className="quick-action primary-action" onClick={() => onCreate("record")}>
          <span className="action-icon"><Mic /></span>
          <span><strong>快速录音</strong><small>前台会议记录</small></span>
          <Plus className="action-arrow" />
        </button>
        <button className="quick-action" onClick={() => audioInput.current?.click()}>
          <span className="action-icon blue"><FileAudio /></span>
          <span><strong>导入音频</strong><small>生成最终转写</small></span>
        </button>
        <button className="quick-action" onClick={() => onCreate("text")}>
          <span className="action-icon amber"><FileText /></span>
          <span><strong>整理文字</strong><small>粘贴或导入文本</small></span>
        </button>
        <input
          ref={audioInput}
          hidden
          type="file"
          accept="audio/*,.m4a,.mp3,.wav,.webm,.ogg,.flac,.mp4"
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) onImportAudio(file);
            event.currentTarget.value = "";
          }}
        />
      </section>

      {growth?.campaigns?.length ? <section className="home-campaign-list"><div className="section-heading"><div><Gift /><h2>近期活动</h2></div><span className="notice-badge">{growth.campaigns.length}</span></div>{growth.campaigns.slice(0, 3).map((campaign) => <button className="home-campaign-row" key={campaign.id} onClick={() => onOpenCampaign(campaign.id)}><span><strong>{campaign.title}</strong><small>{campaign.summary}</small></span><span className="campaign-arrow">›</span></button>)}</section> : null}

      <section className="template-section">
        <div className="section-heading">
          <div><Sparkles /><h2>纪要模板</h2></div>
        </div>
        <div className="template-tabs" role="radiogroup" aria-label="纪要模板">
          {MEETING_TEMPLATES.map((template) => (
            <button
              key={template.key}
              role="radio"
              aria-checked={selectedTemplate === template.key}
              className={selectedTemplate === template.key ? "active" : ""}
              onClick={() => onTemplateChange(template.key)}
            >
              <strong>{template.name}</strong>
              <small>{template.subtitle}</small>
            </button>
          ))}
        </div>
      </section>

      <section className="recent-section">
        <div className="section-heading">
          <div><h2>最近记录</h2><span>{meetings.length}</span></div>
          {meetings.length > 0 && <button className="text-button" onClick={onOpenHistory}>全部</button>}
        </div>
        {recent.length === 0 ? (
          <button className="empty-meetings" onClick={() => onCreate("record")}>
            <Mic />
            <span>创建第一条会议记录</span>
          </button>
        ) : (
          <div className="meeting-list compact-list">
            {recent.map((meeting) => (
              <button key={meeting.id} className="meeting-row" onClick={() => onOpenMeeting(meeting)}>
                <span className={`meeting-type type-${meeting.templateKey}`}>{MEETING_TEMPLATES.find((item) => item.key === meeting.templateKey)?.name.slice(0, 2)}</span>
                <span className="meeting-copy">
                  <strong>{meeting.title}</strong>
                  <small>{formatMeetingDate(meeting.updatedAt)} · {meeting.report ? "纪要已生成" : meeting.transcript ? "转写已完成" : meeting.audio ? "音频待转写" : "待记录"}</small>
                </span>
              </button>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
