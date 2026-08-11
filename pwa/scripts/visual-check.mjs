import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import { chromium, devices } from "playwright";

const baseUrl = (process.env.PWA_VISUAL_BASE_URL || "http://127.0.0.1:4173/app/").replace(/\/?$/, "/");
const browserChannel = process.env.PWA_VISUAL_BROWSER_CHANNEL || "chrome";
const outputDir = resolve(process.env.PWA_VISUAL_OUTPUT_DIR || "artifacts/visual");
const session = {
  access_token: "visual-account-token",
  agent_access_token: "visual-agent-token",
  expires_at: 2_000_000_000,
  user: {
    id: "visual-user",
    username: "visual_user",
    display_name: "林项目",
    role: "user",
    is_admin: false,
    vip_enabled: false,
    plan_name: "Free",
    quota: { request_limit: 10, requests_used: 2, requests_remaining: 8 }
  }
};

function silentWav(sampleCount = 1600) {
  const buffer = Buffer.alloc(44 + sampleCount * 2);
  buffer.write("RIFF", 0);
  buffer.writeUInt32LE(buffer.length - 8, 4);
  buffer.write("WAVE", 8);
  buffer.write("fmt ", 12);
  buffer.writeUInt32LE(16, 16);
  buffer.writeUInt16LE(1, 20);
  buffer.writeUInt16LE(1, 22);
  buffer.writeUInt32LE(16000, 24);
  buffer.writeUInt32LE(32000, 28);
  buffer.writeUInt16LE(2, 32);
  buffer.writeUInt16LE(16, 34);
  buffer.write("data", 36);
  buffer.writeUInt32LE(sampleCount * 2, 40);
  return buffer;
}

function trackErrors(page, label) {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("requestfailed", (request) => errors.push(`${request.url()}: ${request.failure()?.errorText || "request failed"}`));
  page.on("response", (response) => {
    if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`);
  });
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  return () => {
    if (errors.length > 0) throw new Error(`${label} browser errors:\n${errors.join("\n")}`);
  };
}

async function assertLayout(page, label) {
  await page.evaluate(() => document.fonts.ready);
  const layout = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth,
    markLoaded: [...document.querySelectorAll("img.brand-mark")].every((image) => image.complete && image.naturalWidth > 0)
  }));
  if (layout.documentWidth > layout.viewportWidth + 1) {
    throw new Error(`${label} overflows horizontally: ${layout.documentWidth}px > ${layout.viewportWidth}px`);
  }
  if (!layout.markLoaded) throw new Error(`${label} app icon did not render`);
}

async function installSession(page) {
  await page.addInitScript((value) => {
    localStorage.setItem("zhiwuben.pwa.session", JSON.stringify(value));
  }, session);
  await page.route("**/api/account/meetings**", async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ meetings: [], deleted: [] }) });
      return;
    }
    if (request.method() === "PUT") {
      const body = request.postDataJSON();
      const id = new URL(request.url()).pathname.split("/").pop();
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id, ...body }) });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
  });
  await page.route("**/api/stt/audio-archive*", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ items: [] })
  }));
  await page.route("**/api/account/session", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({
      agent_access_token: session.agent_access_token,
      stt_access_token: "visual-stt-token",
      expires_at: session.expires_at,
      user: session.user
    })
  }));
  await page.route("**/api/stt/transcribe", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ text: "自动转写已完成，会议内容可继续整理。" })
  }));
}

await mkdir(outputDir, { recursive: true });
const browser = await chromium.launch({ channel: browserChannel, headless: true });
try {
  const authContext = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: "zh-CN" });
  const authPage = await authContext.newPage();
  const assertAuthErrors = trackErrors(authPage, "desktop auth");
  await authPage.goto(baseUrl, { waitUntil: "domcontentloaded" });
  await authPage.getByRole("heading", { name: "智悟本" }).waitFor();
  await assertLayout(authPage, "desktop auth");
  await authPage.screenshot({ path: resolve(outputDir, "desktop-auth.png"), fullPage: true });
  await authPage.getByRole("button", { name: "注册" }).click();
  await authPage.getByLabel("用户名").fill("ab");
  await authPage.getByLabel("密码").fill("123456");
  await authPage.getByRole("button", { name: /创建账户/ }).click();
  await authPage.getByRole("alert").filter({ hasText: "用户名至少需要 3 个字符" }).waitFor();
  assertAuthErrors();
  await authContext.close();

  const desktopContext = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: "zh-CN" });
  const desktopPage = await desktopContext.newPage();
  await installSession(desktopPage);
  const assertDesktopErrors = trackErrors(desktopPage, "desktop home");
  await desktopPage.goto(baseUrl, { waitUntil: "domcontentloaded" });
  await desktopPage.getByRole("heading", { name: "林项目，开始记录" }).waitFor();
  await assertLayout(desktopPage, "desktop home");
  await desktopPage.screenshot({ path: resolve(outputDir, "desktop-home.png"), fullPage: true });
  assertDesktopErrors();
  await desktopContext.close();

  const transcriptionContext = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: "zh-CN" });
  const transcriptionPage = await transcriptionContext.newPage();
  await installSession(transcriptionPage);
  const assertTranscriptionErrors = trackErrors(transcriptionPage, "automatic transcription");
  await transcriptionPage.goto(baseUrl, { waitUntil: "domcontentloaded" });
  await transcriptionPage.locator('input[type="file"][accept^="audio/"]').setInputFiles({
    name: "meeting.wav",
    mimeType: "audio/wav",
    buffer: silentWav()
  });
  const automaticTranscript = transcriptionPage.getByPlaceholder("粘贴会议文字，或通过录音和音频文件生成转写");
  await automaticTranscript.waitFor();
  await transcriptionPage.waitForFunction(
    (element) => element instanceof HTMLTextAreaElement && element.value.includes("自动转写已完成"),
    await automaticTranscript.elementHandle()
  );
  assertTranscriptionErrors();
  await transcriptionContext.close();

  const mobileContext = await browser.newContext({ ...devices["iPhone 14"], locale: "zh-CN" });
  const mobilePage = await mobileContext.newPage();
  await installSession(mobilePage);
  const assertMobileErrors = trackErrors(mobilePage, "iPhone workspace");
  await mobilePage.goto(baseUrl, { waitUntil: "domcontentloaded" });
  await mobilePage.getByRole("heading", { name: "林项目，开始记录" }).waitFor();
  await assertLayout(mobilePage, "iPhone home");
  await mobilePage.screenshot({ path: resolve(outputDir, "iphone-home.png"), fullPage: true });
  await mobilePage.getByRole("button", { name: /整理文字/ }).click();
  const transcript = mobilePage.getByPlaceholder("粘贴会议文字，或通过录音和音频文件生成转写");
  await transcript.fill("项目组确认本周完成接口联调，下周一上午十点进行阶段验收。负责人和风险项将在会后补齐。");
  await mobilePage.getByRole("button", { name: /行政会议/ }).click();
  await mobilePage.evaluate(() => window.scrollTo(0, 0));
  await assertLayout(mobilePage, "iPhone workspace");
  await mobilePage.screenshot({ path: resolve(outputDir, "iphone-workspace.png"), fullPage: true });
  assertMobileErrors();
  await mobileContext.close();
} finally {
  await browser.close();
}

console.log(`Visual checks passed. Screenshots: ${outputDir}`);
