import {
  ArrowLeft,
  Bot,
  Check,
  Clipboard,
  CloudDownload,
  Download,
  FileAudio,
  FileText,
  ImagePlus,
  Mic,
  Printer,
  Share2,
  Square,
  Trash2,
  Upload,
  X
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { downloadArchivedAudio, generateReport, listArchivedAudio, transcribeAudio, type ArchivedAudio } from "../lib/api";
import { exportDocx, printPdf, renderMarkdown, shareAudio, shareImages, shareReport } from "../lib/export";
import { audioExtension, formatDuration, safeFilename } from "../lib/format";
import { MEETING_TEMPLATES, templateFor } from "../templates";
import type { AuthSession, Meeting, MeetingImage, ProcessingStage, RuntimeConfig, TemplateKey } from "../types";
import { useRecorder } from "../hooks/useRecorder";

interface MeetingWorkspaceProps {
  meeting: Meeting;
  session: AuthSession;
  config: RuntimeConfig;
  startMode?: "record" | "text" | "transcribe";
  onBack: () => void;
  onChange: (meeting: Meeting) => void;
  onRefreshSession: () => Promise<AuthSession>;
  onNotify: (message: string, kind?: "success" | "error") => void;
}

function ImagePreview({ image, onDelete }: { image: MeetingImage; onDelete: () => void }) {
  const url = useMemo(() => URL.createObjectURL(image.blob), [image.blob]);
  useEffect(() => () => URL.revokeObjectURL(url), [url]);
  return (
    <figure className="image-preview">
      <img src={url} alt={image.name} />
      <figcaption>{image.name}</figcaption>
      <button className="icon-button danger" title="删除图片" onClick={onDelete}><Trash2 /></button>
    </figure>
  );
}

export function MeetingWorkspace({
  meeting,
  session,
  config,
  startMode,
  onBack,
  onChange,
  onRefreshSession,
  onNotify
}: MeetingWorkspaceProps) {
  const [processing, setProcessing] = useState<ProcessingStage>({ kind: "idle" });
  const [reportMode, setReportMode] = useState<"preview" | "edit">("preview");
  const [archivedAudio, setArchivedAudio] = useState<ArchivedAudio[]>([]);
  const [loadingArchive, setLoadingArchive] = useState(false);
  const operationRef = useRef<AbortController>();
  const audioInput = useRef<HTMLInputElement>(null);
  const textInput = useRef<HTMLInputElement>(null);
  const imageInput = useRef<HTMLInputElement>(null);
  const transcriptArea = useRef<HTMLTextAreaElement>(null);
  const autoStartedRef = useRef(false);
  const meetingRef = useRef(meeting);
  meetingRef.current = meeting;
  const audioIdentity = meeting.audio
    ? [meeting.id, meeting.audioName, meeting.audio.type, meeting.audio.size, meeting.durationSeconds].join(":")
    : "";
  const [audioUrl, setAudioUrl] = useState("");

  useEffect(() => {
    if (!meeting.audio) {
      setAudioUrl("");
      return;
    }
    const url = URL.createObjectURL(meeting.audio);
    setAudioUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [audioIdentity]);

  useEffect(() => {
    let active = true;
    void listArchivedAudio(config, session, meeting.id)
      .then((items) => { if (active) setArchivedAudio(items); })
      .catch(() => { if (active) setArchivedAudio([]); });
    return () => { active = false; };
  }, [config, meeting.id, session]);

  const update = useCallback((patch: Partial<Meeting>) => {
    const updated = { ...meetingRef.current, ...patch, updatedAt: Date.now() };
    meetingRef.current = updated;
    onChange(updated);
    return updated;
  }, [onChange]);

  const runTranscription = useCallback(async (targetMeeting: Meeting = meetingRef.current) => {
    if (!targetMeeting.audio || operationRef.current) return;
    const controller = new AbortController();
    operationRef.current = controller;
    setProcessing({ kind: "uploading", progress: 0 });
    try {
      const freshSession = await onRefreshSession();
      const transcript = await transcribeAudio(
        config,
        freshSession,
        targetMeeting,
        (progress) => setProcessing(progress >= 100 ? { kind: "transcribing" } : { kind: "uploading", progress }),
        controller.signal
      );
      update({ transcript });
      onNotify("最终转写已生成", "success");
    } catch (error) {
      if (!(error instanceof DOMException && error.name === "AbortError")) {
        onNotify(error instanceof Error ? error.message : "转写失败", "error");
      }
    } finally {
      operationRef.current = undefined;
      setProcessing({ kind: "idle" });
    }
  }, [config, onNotify, onRefreshSession, update]);

  const recorder = useRecorder(session.user.id, useCallback(({ blob, durationSeconds, mimeType }) => {
    const extension = audioExtension(mimeType);
    const completedMeeting = update({
      audio: blob,
      audioType: mimeType,
      audioName: `${safeFilename(meetingRef.current.title)}.${extension}`,
      durationSeconds
    });
    onNotify("会议音频已保存，正在生成最终转写", "success");
    void runTranscription(completedMeeting);
  }, [onNotify, runTranscription, update]), {
    config,
    sttAccessToken: session.stt_access_token,
    contextHint: meeting.title
  });

  useEffect(() => {
    if (autoStartedRef.current) return;
    autoStartedRef.current = true;
    if (startMode === "record") {
      void recorder.start(meeting.id).catch((error) => onNotify(error instanceof Error ? error.message : "无法开始录音", "error"));
    }
    if (startMode === "text") window.setTimeout(() => transcriptArea.current?.focus(), 150);
    if (startMode === "transcribe") void runTranscription(meetingRef.current);
  }, [meeting.id, onNotify, recorder, runTranscription, startMode]);

  const runReport = async () => {
    if (!meeting.transcript.trim()) return;
    const controller = new AbortController();
    operationRef.current = controller;
    setProcessing({ kind: "generating" });
    try {
      const freshSession = await onRefreshSession();
      const report = await generateReport(config, freshSession, meeting, controller.signal);
      update({ report });
      setReportMode("preview");
      onNotify("会议纪要已生成", "success");
    } catch (error) {
      if (!(error instanceof DOMException && error.name === "AbortError")) {
        onNotify(error instanceof Error ? error.message : "纪要生成失败", "error");
      }
    } finally {
      operationRef.current = undefined;
      setProcessing({ kind: "idle" });
    }
  };

  const withErrorToast = async (action: () => Promise<void>) => {
    try {
      await action();
    } catch (error) {
      onNotify(error instanceof Error ? error.message : "操作失败", "error");
    }
  };

  const importAudio = (file: File) => {
    const importedMeeting = update({
      audio: file,
      audioName: file.name,
      audioType: file.type || "application/octet-stream",
      durationSeconds: 0
    });
    onNotify("音频已导入，正在生成最终转写", "success");
    void runTranscription(importedMeeting);
  };

  const addImages = (files: FileList) => {
    const additions: MeetingImage[] = [...files].map((file) => ({
      id: crypto.randomUUID(),
      name: file.name,
      type: file.type,
      blob: file,
      updatedAt: Date.now()
    }));
    update({ images: [...meeting.images, ...additions] });
  };

  const restoreArchivedAudio = async (archive: ArchivedAudio) => {
    setLoadingArchive(true);
    try {
      const audio = await downloadArchivedAudio(config, session, archive);
      update({
        audio,
        audioName: archive.filename,
        audioType: audio.type || "application/octet-stream",
        durationSeconds: Math.round(archive.duration_sec || meeting.durationSeconds)
      });
      onNotify("云端会议录音已载入", "success");
    } catch (error) {
      onNotify(error instanceof Error ? error.message : "无法载入云端录音", "error");
    } finally {
      setLoadingArchive(false);
    }
  };

  const busy = processing.kind !== "idle";
  const activeTemplate = templateFor(meeting.templateKey);

  return (
    <div className="workspace">
      <header className="workspace-header">
        <button className="icon-button" onClick={onBack} title="返回"><ArrowLeft /></button>
        <div className="workspace-title">
          <input value={meeting.title} aria-label="会议标题" onChange={(event) => update({ title: event.target.value })} />
          <span>{activeTemplate.name} · {formatDuration(recorder.isRecording ? recorder.elapsedSeconds : meeting.durationSeconds)}</span>
        </div>
        <span className="save-state"><Check /> 已保存</span>
      </header>

      <main className="workspace-body">
        <section className="workspace-section audio-section">
          <div className="section-heading">
            <div><FileAudio /><h2>会议音频</h2></div>
            {meeting.audio && <button className="text-button" onClick={() => void withErrorToast(() => shareAudio(meeting))}><Share2 /> 分享</button>}
          </div>

          <div className={`recorder-panel ${recorder.isRecording ? "recording" : ""}`}>
            <div className="record-status">
              <span className="record-indicator">{recorder.isRecording ? <span className="pulse-dot" /> : <Mic />}</span>
              <div>
                <strong>{recorder.isRecording ? "正在录音" : meeting.audio ? "音频已保存" : "等待录音"}</strong>
                <span>{formatDuration(recorder.isRecording ? recorder.elapsedSeconds : meeting.durationSeconds)}</span>
              </div>
              {recorder.isRecording ? (
                <button className="stop-button" onClick={recorder.stop}><Square /> 结束</button>
              ) : (
                <button className="primary-button compact-button" onClick={() => void recorder.start(meeting.id).catch((error) => onNotify(error.message, "error"))}><Mic /> 开始</button>
              )}
            </div>
            {recorder.backgroundRisk && <div className="risk-banner">录音期间页面曾进入后台，请结束后检查音频完整性。</div>}
            {recorder.isRecording && recorder.liveStatus !== "idle" && (
              <div className={`live-preview ${recorder.liveStatus === "error" ? "error" : ""}`} aria-live="polite">
                <div className="live-preview-heading"><span className="live-preview-dot" /><strong>{recorder.liveStatus === "connected" ? "实时转录" : recorder.liveStatus === "connecting" ? "正在连接实时转录" : "实时转录暂时不可用"}</strong></div>
                <p>{recorder.liveTranscript || (recorder.liveStatus === "connected" ? "正在等待语音内容..." : "录音不会中断，结束后仍会生成完整转写")}</p>
              </div>
            )}
            {meeting.audio && !recorder.isRecording && <audio className="audio-player" controls preload="metadata" src={audioUrl} />}
            {!meeting.audio && archivedAudio.length > 0 && !recorder.isRecording && (
              <div className="cloud-audio-row">
                <span><CloudDownload /><strong>云端录音可用</strong></span>
                <button className="text-button" disabled={loadingArchive} onClick={() => void restoreArchivedAudio(archivedAudio[0])}>
                  {loadingArchive ? "载入中" : "载入"}
                </button>
              </div>
            )}
            <div className="audio-tools">
              <button className="secondary-button" onClick={() => audioInput.current?.click()}><Upload /> 导入音频</button>
              <button className="secondary-button" onClick={() => imageInput.current?.click()}><ImagePlus /> 会议图片</button>
            </div>
          </div>
          <input ref={audioInput} hidden type="file" accept="audio/*,.m4a,.mp3,.wav,.webm,.ogg,.flac,.mp4" onChange={(event) => { const file = event.target.files?.[0]; if (file) importAudio(file); event.currentTarget.value = ""; }} />
          <input ref={imageInput} hidden multiple type="file" accept="image/*" onChange={(event) => { if (event.target.files) addImages(event.target.files); event.currentTarget.value = ""; }} />

          {meeting.images.length > 0 && (
            <section className="meeting-images-section">
              <div className="section-heading">
                <div><ImagePlus /><h2>会议图片</h2><span>{meeting.images.length} 张</span></div>
                <button className="text-button" onClick={() => void withErrorToast(() => shareImages(meeting))}><Download /> 导出图片</button>
              </div>
              <div className="image-strip">
                {meeting.images.map((image) => <ImagePreview key={image.id} image={image} onDelete={() => update({
                  images: meeting.images.filter((item) => item.id !== image.id),
                  deletedImageIds: [...new Set([...(meeting.deletedImageIds || []), image.id])]
                })} />)}
              </div>
            </section>
          )}

          {meeting.audio && !recorder.isRecording && (
            <button className="primary-button full-button" disabled={busy} onClick={() => void runTranscription()}><FileText /> 生成最终转写</button>
          )}
        </section>

        {busy && (
          <section className="processing-panel" aria-live="polite">
            <div>
              <strong>{processing.kind === "uploading" ? "正在上传音频" : processing.kind === "transcribing" ? "正在生成最终稿" : "正在整理会议"}</strong>
              <span>{processing.kind === "uploading" ? `${processing.progress}%` : "处理中"}</span>
            </div>
            <div className={`processing-track ${processing.kind !== "uploading" ? "indeterminate" : ""}`}>
              <span style={processing.kind === "uploading" ? { width: `${processing.progress}%` } : undefined} />
            </div>
            <button className="danger-text-button" onClick={() => operationRef.current?.abort()}><X /> 停止等待</button>
          </section>
        )}

        <section className="workspace-section transcript-section">
          <div className="section-heading">
            <div><FileText /><h2>转写内容</h2><span>{meeting.transcript.length.toLocaleString()} 字</span></div>
            <div className="heading-tools">
              <button className="icon-button" title="导入文本文件" onClick={() => textInput.current?.click()}><Upload /></button>
              <button className="icon-button" title="复制全文" disabled={!meeting.transcript} onClick={async () => { await navigator.clipboard.writeText(meeting.transcript); onNotify("转写内容已复制", "success"); }}><Clipboard /></button>
            </div>
          </div>
          <textarea
            ref={transcriptArea}
            className="transcript-editor"
            value={meeting.transcript}
            onChange={(event) => update({ transcript: event.target.value })}
            placeholder="粘贴会议文字，或通过录音和音频文件生成转写"
          />
          <input ref={textInput} hidden type="file" accept="text/plain,text/markdown,.txt,.md" onChange={async (event) => { const file = event.target.files?.[0]; if (file) update({ transcript: await file.text() }); event.currentTarget.value = ""; }} />
        </section>

        <section className="workspace-section template-picker-section">
          <div className="section-heading"><div><Bot /><h2>会议整理</h2></div></div>
          <div className="template-radio-grid">
            {MEETING_TEMPLATES.map((template) => (
              <button key={template.key} className={meeting.templateKey === template.key ? "active" : ""} onClick={() => update({ templateKey: template.key as TemplateKey })}>
                <strong>{template.name}</strong><small>{template.subtitle}</small>
              </button>
            ))}
          </div>
          <button className="primary-button full-button" disabled={busy || !meeting.transcript.trim()} onClick={() => void runReport()}><Bot /> 生成会议纪要</button>
        </section>

        {meeting.report && (
          <section className="workspace-section report-section">
            <div className="section-heading">
              <div><h2>会议纪要</h2></div>
              <div className="segmented compact-segmented">
                <button className={reportMode === "preview" ? "active" : ""} onClick={() => setReportMode("preview")}>预览</button>
                <button className={reportMode === "edit" ? "active" : ""} onClick={() => setReportMode("edit")}>编辑</button>
              </div>
            </div>
            {reportMode === "preview" ? (
              <article className="markdown-report" dangerouslySetInnerHTML={{ __html: renderMarkdown(meeting.report) }} />
            ) : (
              <textarea className="report-editor" value={meeting.report} onChange={(event) => update({ report: event.target.value })} />
            )}
            <div className="export-toolbar">
              <button className="secondary-button" onClick={() => void withErrorToast(() => exportDocx(meeting))}><Download /> Word</button>
              <button className="secondary-button" onClick={() => void withErrorToast(() => printPdf(meeting))}><Printer /> PDF</button>
              <button className="secondary-button" onClick={() => void withErrorToast(() => shareReport(meeting))}><Share2 /> 分享</button>
            </div>
          </section>
        )}
      </main>
    </div>
  );
}
