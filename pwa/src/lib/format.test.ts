import { describe, expect, it } from "vitest";
import { MEETING_TEMPLATES, templateFor } from "../templates";
import type { Meeting } from "../types";
import { audioExtension, formatDuration, localFileTimestamp, reportFilename, safeFilename } from "./format";

describe("meeting file formatting", () => {
  it("formats local timestamps without a timezone suffix", () => {
    expect(localFileTimestamp(new Date(2026, 6, 24, 13, 5, 9))).toBe("20260724-130509");
  });

  it("creates a safe type-title-timestamp report filename", () => {
    const meeting: Meeting = {
      id: "meeting-1",
      title: "周会:研发/交付?",
      templateKey: "project",
      createdAt: 0,
      updatedAt: 0,
      durationSeconds: 0,
      transcript: "",
      report: "",
      images: []
    };
    expect(reportFilename(meeting, "pdf")).toMatch(/^项目管理-周会-研发-交付--\d{8}-\d{6}\.pdf$/);
  });

  it("normalizes durations, filenames and common audio types", () => {
    expect(formatDuration(65.9)).toBe("01:05");
    expect(formatDuration(3661)).toBe("01:01:01");
    expect(safeFilename("  A/B:*?  ")).toBe("A-B---");
    expect(audioExtension("audio/mp4")).toBe("m4a");
    expect(audioExtension("audio/ogg;codecs=opus")).toBe("ogg");
  });
});

describe("meeting templates", () => {
  it("ships the four light-edition meeting templates", () => {
    expect(MEETING_TEMPLATES.map((template) => template.key)).toEqual([
      "project",
      "administrative",
      "brainstorming",
      "inspection"
    ]);
    expect(templateFor("administrative").content).toContain("时间节点总表");
    expect(templateFor("brainstorming").content).toContain("创意池");
    expect(templateFor("inspection").content).toContain("行程总览");
    expect(templateFor("inspection").content).toContain("分段考察记录");
  });
});
