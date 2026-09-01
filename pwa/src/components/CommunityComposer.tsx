import { Check, FileText, Image as ImageIcon, Save, Send, ShieldCheck, UploadCloud, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { createCommunityDraft, createCommunityMediaManifest, publishMyCommunityPost, sha256Hex, updateCommunityDraft, uploadCommunityMediaVariant, type CommunityDraftPayload } from "../lib/api";
import type { AuthSession, Meeting, OwnerCommunityPost, RuntimeConfig } from "../types";

interface CommunityComposerProps {
  config: RuntimeConfig;
  session: AuthSession;
  meetings: Meeting[];
  initialMeetingId?: string;
  initialPost?: OwnerCommunityPost;
  onClose: () => void;
  onSaved: (post: OwnerCommunityPost) => void;
  onNotify: (message: string, kind?: "success" | "error") => void;
}

function beijingDate(timestamp: number): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(timestamp);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${values.year}-${values.month}-${values.day}`;
}

function redactCoordinates(content: string): { content: string; count: number } {
  let count = 0;
  const sanitized = content.replace(/(?<![\d.])[-+]?(?:[0-8]?\d(?:\.\d{4,})?|90(?:\.0{4,})?)\s*[,，]\s*[-+]?(?:1[0-7]\d(?:\.\d{4,})?|\d?\d(?:\.\d{4,})?|180(?:\.0{4,})?)(?![\d.])/g, () => {
    count += 1;
    return "（位置已脱敏）";
  }).replace(/(纬度|经度|latitude|longitude|lat|lng|lon)\s*[:=：]\s*[-+]?\d{1,3}\.\d{4,}/gi, (match) => {
    count += 1;
    return `${match.split(/[:=：]/, 1)[0]}：已脱敏`;
  });
  return { content: sanitized, count };
}

function splitMetadata(value: string): string[] {
  return value.split(/[，,\n]/).map((item) => item.trim()).filter(Boolean).slice(0, 50);
}

function buildInitialValues(meeting: Meeting | undefined, post?: OwnerCommunityPost) {
  const source = meeting?.report.trim() || meeting?.transcript.trim() || "";
  const redacted = redactCoordinates(source);
  return {
    title: post?.title || meeting?.title || "我的研学记录",
    content: post?.content || redacted.content,
    destination: post?.destination || "",
    travelDate: post?.travel_date || (meeting ? beijingDate(meeting.createdAt) : ""),
    travelDays: post?.travel_days ? String(post.travel_days) : "",
    tags: post?.tags?.join(", ") || "",
    pois: post?.pois?.join(", ") || "",
    privacyReviewed: post?.privacy_reviewed ?? false,
    rightsConfirmed: post?.rights_confirmed ?? false,
    redactedCount: post?.content ? 0 : redacted.count
  };
}

async function createThumbnail(file: Blob): Promise<Blob> {
  try {
    const bitmap = await createImageBitmap(file);
    const maxEdge = 1280;
    const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const context = canvas.getContext("2d");
    if (!context) return file;
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close();
    return await new Promise<Blob>((resolve) => canvas.toBlob((blob) => resolve(blob || file), "image/jpeg", 0.82));
  } catch {
    return file;
  }
}

function ComposerImage({ image, selected, onToggle }: { image: Meeting["images"][number]; selected: boolean; onToggle: () => void }) {
  const [url, setUrl] = useState("");
  useEffect(() => {
    const next = URL.createObjectURL(image.blob);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [image.blob]);
  return <button type="button" className={`composer-image ${selected ? "selected" : ""}`} onClick={onToggle} aria-pressed={selected}>
    {url && <img src={url} alt={image.name} />}
    <span><i>{selected ? "已选择" : "未选择"}</i><b>{image.name}</b></span>
  </button>;
}

export function CommunityComposer({
  config,
  session,
  meetings,
  initialMeetingId,
  initialPost,
  onClose,
  onSaved,
  onNotify
}: CommunityComposerProps) {
  const [meetingId, setMeetingId] = useState(initialMeetingId || meetings[0]?.id || "");
  const meeting = useMemo(() => meetings.find((item) => item.id === meetingId), [meetingId, meetings]);
  const initial = useMemo(() => buildInitialValues(meeting, initialPost), [meeting, initialPost]);
  const [title, setTitle] = useState(initial.title);
  const [content, setContent] = useState(initial.content);
  const [destination, setDestination] = useState(initial.destination);
  const [travelDate, setTravelDate] = useState(initial.travelDate);
  const [travelDays, setTravelDays] = useState(initial.travelDays);
  const [tags, setTags] = useState(initial.tags);
  const [pois, setPois] = useState(initial.pois);
  const [privacyReviewed, setPrivacyReviewed] = useState(initial.privacyReviewed);
  const [rightsConfirmed, setRightsConfirmed] = useState(initial.rightsConfirmed);
  const [redactedCount, setRedactedCount] = useState(initial.redactedCount);
  const [busy, setBusy] = useState(false);
  const [post, setPost] = useState<OwnerCommunityPost | undefined>(initialPost);
  const [selectedImageIds, setSelectedImageIds] = useState<string[]>(() => meeting?.images.map((image) => image.id) || []);
  const [uploadStates, setUploadStates] = useState<Record<string, { status: "等待上传" | "上传中" | "已完成" | "失败"; progress: number; error?: string }>>({});

  useEffect(() => {
    if (!post) setSelectedImageIds(meeting?.images.map((image) => image.id) || []);
  }, [meeting, post]);

  const selectMeeting = (nextId: string) => {
    setMeetingId(nextId);
    if (post) return;
    const nextMeeting = meetings.find((item) => item.id === nextId);
    const next = buildInitialValues(nextMeeting);
    setTitle(next.title);
    setContent(next.content);
    setTravelDate(next.travelDate);
    setRedactedCount(next.redactedCount);
    setSelectedImageIds(nextMeeting?.images.map((image) => image.id) || []);
  };

  const payload = (): CommunityDraftPayload => {
    const redacted = redactCoordinates(content);
    if (redacted.content !== content) {
      setContent(redacted.content);
      setRedactedCount((current) => current + redacted.count);
    }
    const stableMeetingId = meeting?.id || post?.journey_id || `manual-${Date.now()}`;
    return {
      client_snapshot_id: `web-${stableMeetingId}`,
      journey_id: post?.journey_id || `web-journey-${stableMeetingId}`,
      journey_edition_id: post?.journey_edition_id || `web-edition-${stableMeetingId}`,
      source_edition_version: post?.source_edition_version || 1,
      title: title.trim(),
      content: redacted.content,
      ai_assisted: Boolean(meeting?.report.trim()),
      redacted_coordinate_count: redactedCount + redacted.count,
      privacy_reviewed: privacyReviewed,
      rights_confirmed: rightsConfirmed,
      destination: destination.trim(),
      travel_date: travelDate,
      travel_days: Number.parseInt(travelDays, 10) || 0,
      stage_titles: [],
      tags: splitMetadata(tags),
      pois: splitMetadata(pois)
    };
  };

  const uploadSelectedMedia = async (postId: string) => {
    const selected = (meeting?.images || []).filter((image) => selectedImageIds.includes(image.id));
    for (const image of selected) {
      setUploadStates((current) => ({ ...current, [image.id]: { status: "上传中", progress: 1 } }));
      try {
        const thumbnail = await createThumbnail(image.blob);
        const [originalSha, thumbnailSha] = await Promise.all([sha256Hex(image.blob), sha256Hex(thumbnail)]);
        const manifest = await createCommunityMediaManifest(config, session, postId, {
          client_media_id: image.id,
          display_name: image.name,
          mime_type: image.type || "image/jpeg",
          original_bytes: image.blob.size,
          original_sha256: originalSha,
          thumbnail_bytes: thumbnail.size,
          thumbnail_sha256: thumbnailSha
        });
        const original = await uploadCommunityMediaVariant(config, session, postId, manifest.id, "original", image.blob, manifest.original_received_bytes, (percent) => setUploadStates((current) => ({ ...current, [image.id]: { status: "上传中", progress: Math.round(percent * 0.72) } })));
        await uploadCommunityMediaVariant(config, session, postId, manifest.id, "thumbnail", thumbnail, original.thumbnail_received_bytes, (percent) => setUploadStates((current) => ({ ...current, [image.id]: { status: "上传中", progress: 72 + Math.round(percent * 0.28) } })));
        setUploadStates((current) => ({ ...current, [image.id]: { status: "已完成", progress: 100 } }));
      } catch (error) {
        const message = error instanceof Error ? error.message : "图片上传失败";
        setUploadStates((current) => ({ ...current, [image.id]: { status: "失败", progress: 0, error: message } }));
        throw new Error(`${image.name}：${message}`);
      }
    }
  };

  const save = async (submit: boolean) => {
    if (busy) return;
    if (!title.trim() || !content.trim()) {
      onNotify("请填写标题和正文", "error");
      return;
    }
    if (!privacyReviewed || !rightsConfirmed) {
      onNotify("请先完成隐私与内容权利确认", "error");
      return;
    }
    setBusy(true);
    try {
      const next = post
        ? await updateCommunityDraft(config, session, post.id, payload())
        : await createCommunityDraft(config, session, payload());
      setPost(next);
      onSaved(next);
      await uploadSelectedMedia(next.id);
      if (submit) {
        const published = await publishMyCommunityPost(config, session, next.id);
        setPost(published);
        onSaved(published);
        onNotify("内容已提交审核", "success");
        onClose();
      } else {
        onNotify("草稿已保存", "success");
      }
    } catch (error) {
      onNotify(error instanceof Error ? error.message : "内容保存失败", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="community-composer-backdrop" onMouseDown={onClose}>
      <section className="community-composer" role="dialog" aria-modal="true" aria-labelledby="community-composer-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="community-composer-header">
          <div><span className="eyebrow">社区创作工作台</span><h2 id="community-composer-title">创建内容</h2><p>从已有会议记录整理一篇可审核的研学分享。</p></div>
          <button className="icon-button" title="关闭" onClick={onClose}><X /></button>
        </header>
        <div className="community-composer-body">
          <label className="composer-field"><span>内容来源</span><select value={meetingId} onChange={(event) => selectMeeting(event.target.value)} disabled={Boolean(post)}><option value="">手动创建</option>{meetings.map((item) => <option key={item.id} value={item.id}>{item.title}</option>)}</select></label>
          {meeting && <div className="composer-source-note"><FileText /><span>已载入会议正文{meeting.report.trim() ? "与纪要" : "与转写"}，可继续编辑。</span><em>{meeting.images.length} 张现场图片</em></div>}
          <label className="composer-field"><span>标题</span><input value={title} onChange={(event) => setTitle(event.target.value)} placeholder="例如：良渚博物院半日研学记录" maxLength={200} /></label>
          <div className="composer-field-row"><label className="composer-field"><span>目的地</span><input value={destination} onChange={(event) => setDestination(event.target.value)} placeholder="杭州·良渚" maxLength={120} /></label><label className="composer-field"><span>行程日期（北京时间）</span><input type="date" value={travelDate} onChange={(event) => setTravelDate(event.target.value)} /></label><label className="composer-field composer-days"><span>天数</span><input type="number" min={0} max={31} value={travelDays} onChange={(event) => setTravelDays(event.target.value)} placeholder="1" /></label></div>
          <label className="composer-field"><span>正文</span><textarea value={content} onChange={(event) => setContent(event.target.value)} rows={12} maxLength={100000} placeholder="写下现场观察、讲解要点和自己的收获……" /></label>
          <div className="composer-field-row"><label className="composer-field"><span>主题标签</span><input value={tags} onChange={(event) => setTags(event.target.value)} placeholder="历史建筑，亲子研学" /></label><label className="composer-field"><span>地点 / 参观点</span><input value={pois} onChange={(event) => setPois(event.target.value)} placeholder="博物院，水利遗址" /></label></div>
          <div className="composer-media-note"><ImageIcon /><div><strong>现场影像</strong><span>{meeting?.images.length ? `已选择 ${selectedImageIds.length}/${meeting.images.length} 张，保存草稿时会同步到社区媒体存储。` : post?.media?.length ? `${post.media.length} 张图片已关联` : "这篇内容暂未关联现场图片。"}</span></div></div>
          {meeting && meeting.images.length > 0 && <div className="composer-image-grid" aria-label="选择现场影像">{meeting.images.map((image) => <div className="composer-image-wrap" key={image.id}><ComposerImage image={image} selected={selectedImageIds.includes(image.id)} onToggle={() => setSelectedImageIds((current) => current.includes(image.id) ? current.filter((id) => id !== image.id) : [...current, image.id])} />{uploadStates[image.id] && <small className={`composer-image-status status-${uploadStates[image.id].status === "失败" ? "error" : uploadStates[image.id].status === "已完成" ? "done" : "busy"}`}><UploadCloud />{uploadStates[image.id].status}{uploadStates[image.id].status === "上传中" ? ` ${uploadStates[image.id].progress}%` : ""}</small>}</div>)}</div>}
          <div className="composer-checks"><label><input type="checkbox" checked={privacyReviewed} onChange={(event) => setPrivacyReviewed(event.target.checked)} /><ShieldCheck />正文不含个人信息或精确位置</label><label><input type="checkbox" checked={rightsConfirmed} onChange={(event) => setRightsConfirmed(event.target.checked)} /><Check />我拥有正文及图片的发布权利</label></div>
          {redactedCount > 0 && <small className="composer-safety-note">已自动脱敏 {redactedCount} 处位置信息，发布前仍建议再检查一次正文。</small>}
        </div>
        <footer className="community-composer-footer"><button className="secondary-button" disabled={busy} onClick={() => void save(false)}><Save />保存草稿</button><button className="primary-button" disabled={busy} onClick={() => void save(true)}>{busy ? "保存中…" : <><Send />提交审核</>}</button></footer>
      </section>
    </div>
  );
}
