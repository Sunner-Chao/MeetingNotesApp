import DOMPurify from "dompurify";
import { marked } from "marked";
import type { Meeting } from "../types";
import { reportFilename } from "./format";
import { templateFor } from "../templates";

export function renderMarkdown(markdown: string): string {
  const html = marked.parse(markdown, { async: false, gfm: true }) as string;
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function exportDocx(meeting: Meeting): Promise<void> {
  const { exportDocxDocument } = await import("./docxExport");
  await exportDocxDocument(meeting);
}

function blobDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#039;"
  })[character] ?? character);
}

export async function printPdf(meeting: Meeting): Promise<void> {
  const content = meeting.report.trim() || meeting.transcript.trim();
  if (!content) throw new Error("当前没有可导出的内容");
  const popup = window.open("", "_blank");
  if (!popup) throw new Error("浏览器阻止了打印窗口，请允许弹出窗口后重试");
  popup.opener = null;
  try {
    const images = await Promise.all(meeting.images.map(async (image) => ({
      name: image.name,
      src: await blobDataUrl(image.blob)
    })));
    if (popup.closed) throw new Error("打印窗口已关闭");
    const safeTitle = escapeHtml(meeting.title);
    popup.document.write(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${safeTitle}</title>
      <style>@page{size:A4;margin:18mm}body{font-family:"Noto Sans SC","PingFang SC",sans-serif;color:#17211d;line-height:1.65;font-size:12pt}h1{text-align:center;font-size:22pt}h2{font-size:16pt;margin-top:24px;border-bottom:1px solid #c9d3ce;padding-bottom:6px}table{width:100%;border-collapse:collapse;margin:12px 0;font-size:10.5pt}th,td{border:1px solid #8e9c95;padding:7px;vertical-align:top}th{background:#edf3f0}img{display:block;max-width:100%;max-height:220mm;margin:14px auto 4px}.caption{text-align:center;color:#59665f;font-size:9pt}.meta{text-align:center;color:#59665f;margin-bottom:28px}</style>
      </head><body><h1>${safeTitle}</h1><div class="meta">${templateFor(meeting.templateKey).name} · 会议整理</div>${renderMarkdown(content)}${images.length ? "<h2>会议图片</h2>" : ""}${images.map((image) => `<img src="${image.src}" alt=""><div class="caption">${escapeHtml(image.name)}</div>`).join("")}</body></html>`);
    popup.document.close();
    popup.addEventListener("load", () => window.setTimeout(() => popup.print(), 250), { once: true });
  } catch (error) {
    if (!popup.closed) popup.close();
    throw error;
  }
}

export async function shareReport(meeting: Meeting): Promise<void> {
  const content = meeting.report.trim() || meeting.transcript.trim();
  if (!content) throw new Error("当前没有可分享的内容");
  const filename = reportFilename(meeting, "md");
  const file = new File([content], filename, { type: "text/markdown;charset=utf-8" });
  if (navigator.share && (!navigator.canShare || navigator.canShare({ files: [file] }))) {
    await navigator.share({ title: meeting.title, text: `${templateFor(meeting.templateKey).name}会议纪要`, files: [file] });
    return;
  }
  downloadBlob(file, filename);
}

export async function shareAudio(meeting: Meeting): Promise<void> {
  if (!meeting.audio) throw new Error("当前会议没有音频");
  const file = new File([meeting.audio], meeting.audioName || `meeting-${meeting.id}.webm`, { type: meeting.audioType || meeting.audio.type });
  if (navigator.share && (!navigator.canShare || navigator.canShare({ files: [file] }))) {
    await navigator.share({ title: meeting.title, files: [file] });
    return;
  }
  downloadBlob(file, file.name);
}
