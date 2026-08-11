import {
  AlignmentType,
  Document,
  HeadingLevel,
  ImageRun,
  Packer,
  Paragraph,
  Table,
  TableCell,
  TableRow,
  TextRun,
  WidthType
} from "docx";
import { templateFor } from "../templates";
import type { Meeting, MeetingImage } from "../types";
import { reportFilename } from "./format";

function plainInline(value: string): string {
  return value
    .replace(/!\[[^\]]*]\([^)]*\)/g, "")
    .replace(/\[([^\]]+)]\([^)]*\)/g, "$1")
    .replace(/[*_~`>#]/g, "")
    .trim();
}

function tableCells(line: string): string[] {
  return line.trim().replace(/^\||\|$/g, "").split("|").map((cell) => plainInline(cell));
}

function isTableSeparator(line: string): boolean {
  return /^\s*\|?\s*:?-{3,}/.test(line) && line.includes("|");
}

function markdownToDocxBlocks(markdown: string): Array<Paragraph | Table> {
  const lines = markdown.replace(/\r\n/g, "\n").split("\n");
  const blocks: Array<Paragraph | Table> = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index].trimEnd();
    if (!line.trim()) {
      index += 1;
      continue;
    }
    if (line.includes("|") && index + 1 < lines.length && isTableSeparator(lines[index + 1])) {
      const rows: string[][] = [tableCells(line)];
      index += 2;
      while (index < lines.length && lines[index].includes("|") && lines[index].trim()) {
        rows.push(tableCells(lines[index]));
        index += 1;
      }
      blocks.push(new Table({
        width: { size: 100, type: WidthType.PERCENTAGE },
        rows: rows.map((cells, rowIndex) => new TableRow({
          tableHeader: rowIndex === 0,
          children: cells.map((cell) => new TableCell({
            children: [new Paragraph({
              children: [new TextRun({ text: cell || " ", bold: rowIndex === 0 })],
              spacing: { before: 60, after: 60 }
            })]
          }))
        }))
      }));
      continue;
    }
    const heading = /^(#{1,4})\s+(.+)$/.exec(line);
    if (heading) {
      const levels = [HeadingLevel.HEADING_1, HeadingLevel.HEADING_2, HeadingLevel.HEADING_3, HeadingLevel.HEADING_4];
      blocks.push(new Paragraph({ text: plainInline(heading[2]), heading: levels[heading[1].length - 1] }));
      index += 1;
      continue;
    }
    const bullet = /^[-*+]\s+(.+)$/.exec(line);
    if (bullet) {
      blocks.push(new Paragraph({ text: plainInline(bullet[1]), bullet: { level: 0 } }));
      index += 1;
      continue;
    }
    const numbered = /^\d+[.)]\s+(.+)$/.exec(line);
    if (numbered) {
      blocks.push(new Paragraph({ text: plainInline(numbered[1]), numbering: { reference: "meeting-numbering", level: 0 } }));
      index += 1;
      continue;
    }
    blocks.push(new Paragraph({
      children: [new TextRun(plainInline(line))],
      spacing: { after: 120, line: 360 }
    }));
    index += 1;
  }
  return blocks;
}

async function imageDimensions(image: MeetingImage): Promise<{ width: number; height: number }> {
  const bitmap = await createImageBitmap(image.blob);
  const scale = Math.min(1, 520 / bitmap.width, 360 / bitmap.height);
  const result = { width: Math.round(bitmap.width * scale), height: Math.round(bitmap.height * scale) };
  bitmap.close();
  return result;
}

async function imageBlocks(images: MeetingImage[]): Promise<Paragraph[]> {
  if (images.length === 0) return [];
  const output = [new Paragraph({ text: "会议图片", heading: HeadingLevel.HEADING_2 })];
  for (const image of images) {
    const dimensions = await imageDimensions(image);
    const type = image.type.includes("png") ? "png" : image.type.includes("gif") ? "gif" : "jpg";
    output.push(new Paragraph({
      alignment: AlignmentType.CENTER,
      children: [new ImageRun({
        data: new Uint8Array(await image.blob.arrayBuffer()),
        type,
        transformation: dimensions
      })],
      spacing: { before: 120, after: 80 }
    }));
    output.push(new Paragraph({ text: image.name, alignment: AlignmentType.CENTER, spacing: { after: 160 } }));
  }
  return output;
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function exportDocxDocument(meeting: Meeting): Promise<void> {
  const content = meeting.report.trim() || meeting.transcript.trim();
  if (!content) throw new Error("当前没有可导出的内容");
  const document = new Document({
    numbering: {
      config: [{
        reference: "meeting-numbering",
        levels: [{ level: 0, format: "decimal", text: "%1.", alignment: AlignmentType.START }]
      }]
    },
    sections: [{
      properties: {},
      children: [
        new Paragraph({ text: meeting.title, heading: HeadingLevel.TITLE, alignment: AlignmentType.CENTER }),
        new Paragraph({
          text: `${templateFor(meeting.templateKey).name} · 智能体 · 小Woo`,
          alignment: AlignmentType.CENTER,
          spacing: { after: 300 }
        }),
        ...markdownToDocxBlocks(content),
        ...await imageBlocks(meeting.images)
      ]
    }]
  });
  downloadBlob(await Packer.toBlob(document), reportFilename(meeting, "docx"));
}
