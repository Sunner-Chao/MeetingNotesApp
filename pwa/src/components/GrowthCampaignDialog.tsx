import { Check, CircleHelp, Gift, LoaderCircle, Sparkles, Trophy, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { AuthSession, GrowthCampaignDetail, RuntimeConfig } from "../types";
import { answerGrowthCampaign, checkinGrowthCampaign, drawGrowthCampaign, fetchGrowthCampaignDetail, joinGrowthCampaign } from "../lib/api";

interface Props {
  campaignId: string;
  config: RuntimeConfig;
  session: AuthSession;
  onClose: () => void;
  onChanged: () => Promise<void>;
  onNotify: (message: string, kind?: "success" | "error") => void;
}

export function GrowthCampaignDialog({ campaignId, config, session, onClose, onChanged, onNotify }: Props) {
  const [campaign, setCampaign] = useState<GrowthCampaignDetail>();
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const reload = async () => {
    setLoading(true);
    try { setCampaign(await fetchGrowthCampaignDetail(config, session, campaignId)); }
    catch (error) { onNotify(error instanceof Error ? error.message : "活动加载失败", "error"); onClose(); }
    finally { setLoading(false); }
  };
  useEffect(() => { void reload(); }, [campaignId]);
  const rules = useMemo(() => campaign?.rules || {}, [campaign]);
  const questions = Array.isArray(rules.questions) ? rules.questions as Array<{ key: string; question: string; options?: string[] }> : [];
  const run = async (key: string, task: () => Promise<{ message: string }>) => {
    setBusy(key);
    try { const result = await task(); onNotify(result.message); await reload(); await onChanged(); }
    catch (error) { onNotify(error instanceof Error ? error.message : "操作失败", "error"); }
    finally { setBusy(""); }
  };
  return <div className="growth-dialog-backdrop" onMouseDown={onClose}><section className="growth-dialog" role="dialog" aria-modal="true" onMouseDown={(event) => event.stopPropagation()}>
    <header className="growth-dialog-header"><div><span className="eyebrow">近期活动</span><h2>{campaign?.title || "活动详情"}</h2></div><button className="icon-button" title="关闭" onClick={onClose}><X /></button></header>
    {loading || !campaign ? <div className="growth-dialog-loading"><LoaderCircle className="spin" /><span>正在加载活动</span></div> : <>
      <div className="growth-dialog-meta"><span className="campaign-status"><span />{campaign.status === "active" ? "进行中" : campaign.status}</span><span>{new Date(campaign.starts_at * 1000).toLocaleDateString("zh-CN")} - {new Date(campaign.ends_at * 1000).toLocaleDateString("zh-CN")}</span></div>
      <div className="growth-dialog-body"><p className="campaign-summary">{campaign.summary}</p><div className="campaign-rule-grid"><div><span>我的积分</span><strong>{campaign.my_score}</strong></div><div><span>当前排名</span><strong>{campaign.my_rank || "未上榜"}</strong></div><div><span>签到奖励</span><strong>{String(rules.checkin_reward || 0)} 分</strong></div></div>
        {questions.length > 0 && <div className="campaign-quiz"><div className="campaign-subhead"><CircleHelp /><h3>今日答题</h3></div>{questions.map((question) => <article className="quiz-question" key={question.key}><strong>{question.question}</strong><div className="quiz-options">{(question.options || []).map((option) => <button key={option} className={answers[question.key] === option ? "selected" : ""} onClick={() => setAnswers((current) => ({ ...current, [question.key]: option }))}>{answers[question.key] === option && <Check />}{option}</button>)}</div><button className="primary-button compact-button" disabled={!answers[question.key] || Boolean(busy)} onClick={() => void run(`answer-${question.key}`, () => answerGrowthCampaign(config, session, campaign.id, question.key, answers[question.key]))}>{busy === `answer-${question.key}` ? "提交中" : "提交答案"}</button></article>)}</div>}
        <div className="campaign-leaderboard"><div className="campaign-subhead"><Trophy /><h3>当前排行</h3></div>{campaign.leaderboard.length === 0 ? <p className="empty-copy">还没有参与记录</p> : campaign.leaderboard.slice(0, 5).map((item, index) => <div className="leaderboard-row" key={item.user_id}><span className={`rank rank-${index + 1}`}>{index + 1}</span><strong>{item.display_name}</strong><span>{item.score} 分</span></div>)}</div>
      </div><footer className="growth-dialog-footer">{!campaign.joined && <button className="secondary-button" disabled={Boolean(busy)} onClick={() => void run("join", () => joinGrowthCampaign(config, session, campaign.id).then(() => ({ message: "已加入活动" })))}><Sparkles />加入活动</button>}<button className="secondary-button" disabled={!campaign.joined || Boolean(busy)} onClick={() => void run("checkin", () => checkinGrowthCampaign(config, session, campaign.id))}><Check />每日签到</button><button className="primary-button" disabled={!campaign.joined || Boolean(busy)} onClick={() => void run("draw", () => drawGrowthCampaign(config, session, campaign.id))}><Gift />抽一次</button></footer>
    </>}
  </section></div>;
}
