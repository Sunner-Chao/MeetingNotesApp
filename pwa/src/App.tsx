import { Share, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AppShell, type AppTab } from "./components/AppShell";
import { AuthScreen } from "./components/AuthScreen";
import { BrandMark } from "./components/BrandMark";
import { ConfirmDialog } from "./components/ConfirmDialog";
import { HistoryScreen } from "./components/HistoryScreen";
import { HomeScreen } from "./components/HomeScreen";
import { MeetingWorkspace } from "./components/MeetingWorkspace";
import { ProfileScreen } from "./components/ProfileScreen";
import { GrowthCampaignDialog } from "./components/GrowthCampaignDialog";
import { Toast, type ToastState } from "./components/Toast";
import { exchangeSocialAuthTicket, fetchGrowthOverview, login, redeemGrowthCode, refreshSession, requestRegistrationCode, updateProfile, verifyEmailRegistration } from "./lib/api";
import { claimLegacyGuestData, clearMeetings, deleteMeetingRecord, listMeetings, recoverInterruptedRecordings, saveMeeting } from "./lib/db";
import { synchronizeCloudMeetings } from "./lib/cloudSync";
import { audioExtension } from "./lib/format";
import type { AuthCodeDelivery, AuthSession, GrowthOverview, Meeting, RuntimeConfig, TemplateKey } from "./types";

const CONFIG_KEY = "zhiwuben.pwa.config";
const SESSION_KEY = "zhiwuben.pwa.session";
const DEFAULT_CONFIG: RuntimeConfig = {
  apiBase: "",
  agentProvider: "codex-cli",
  reasoningEffort: "medium",
  defaultTemplate: "project"
};

function loadStored<T>(key: string): T | undefined {
  try {
    const value = localStorage.getItem(key);
    return value ? JSON.parse(value) as T : undefined;
  } catch {
    return undefined;
  }
}

function newMeeting(templateKey: TemplateKey): Meeting {
  const now = Date.now();
  const title = `会议记录 ${new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(now)}`;
  return {
    id: crypto.randomUUID(),
    title,
    templateKey,
    createdAt: now,
    updatedAt: now,
    durationSeconds: 0,
    transcript: "",
    report: "",
    images: []
  };
}

export default function App() {
  const [config, setConfig] = useState<RuntimeConfig>(() => ({ ...DEFAULT_CONFIG, ...loadStored<RuntimeConfig>(CONFIG_KEY) }));
  const [session, setSession] = useState<AuthSession | undefined>(() => {
    const stored = loadStored<AuthSession>(SESSION_KEY);
    return stored && stored.expires_at * 1000 > Date.now() ? stored : undefined;
  });
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [selectedMeetingId, setSelectedMeetingId] = useState<string>();
  const [selectedCampaignId, setSelectedCampaignId] = useState<string>();
  const [growth, setGrowth] = useState<GrowthOverview>();
  const [startMode, setStartMode] = useState<"record" | "text" | "transcribe">();
  const [tab, setTab] = useState<AppTab>("home");
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const [online, setOnline] = useState(navigator.onLine);
  const [cloudState, setCloudState] = useState<"idle" | "syncing" | "synced" | "pending">("idle");
  const [toast, setToast] = useState<ToastState>();
  const [confirm, setConfirm] = useState<{ kind: "delete"; meeting: Meeting } | { kind: "clear" }>();
  const [installPrompt, setInstallPrompt] = useState<BeforeInstallPromptEvent>();
  const [showIosInstall, setShowIosInstall] = useState(false);
  const toastTimer = useRef<number>();
  const cloudSyncTimer = useRef<number>();
  const cloudSyncRunning = useRef(false);
  const meetingRevision = useRef(0);
  const sessionRef = useRef(session);
  const configRef = useRef(config);
  const onlineRef = useRef(online);
  const claimedAccountId = useRef<string>();
  const socialCallbackHandled = useRef(false);
  sessionRef.current = session;
  configRef.current = config;
  onlineRef.current = online;
  const selectedMeeting = useMemo(() => meetings.find((meeting) => meeting.id === selectedMeetingId), [meetings, selectedMeetingId]);
  const standalone = window.matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
  const isIos = /iphone|ipad|ipod/i.test(navigator.userAgent);

  const notify = useCallback((message: string, kind: ToastState["kind"] = "success") => {
    if (toastTimer.current) window.clearTimeout(toastTimer.current);
    setToast({ message, kind });
    toastTimer.current = window.setTimeout(() => setToast(undefined), 3600);
  }, []);

  useEffect(() => {
    if (socialCallbackHandled.current) return;
    const params = new URLSearchParams(window.location.search);
    const ticket = params.get("social_ticket");
    const socialError = params.get("social_error");
    if (!ticket && !socialError) return;
    socialCallbackHandled.current = true;
    const clearCallbackParameters = () => {
      const next = new URL(window.location.href);
      next.searchParams.delete("social_ticket");
      next.searchParams.delete("social_provider");
      next.searchParams.delete("social_error");
      window.history.replaceState({}, "", `${next.pathname}${next.search}${next.hash}`);
    };
    if (socialError) {
      notify(socialError, "error");
      clearCallbackParameters();
      return;
    }
    setBusy(true);
    void exchangeSocialAuthTicket(configRef.current, ticket || "")
      .then((authenticated) => {
        setReady(false);
        setMeetings([]);
        setSelectedMeetingId(undefined);
        setSession(authenticated);
        notify("第三方登录成功", "success");
      })
      .catch((error) => notify(error instanceof Error ? error.message : "第三方登录失败", "error"))
      .finally(() => {
        setBusy(false);
        clearCallbackParameters();
      });
  }, [notify]);

  useEffect(() => {
    let active = true;
    void (async () => {
      const accountId = session?.user.id;
      if (accountId && claimedAccountId.current !== accountId) {
        claimedAccountId.current = accountId;
        await claimLegacyGuestData(accountId);
      }
      if (!accountId) {
        if (active) {
          setMeetings([]);
          setSelectedMeetingId(undefined);
          setReady(true);
        }
        return;
      }
      const recovered = await recoverInterruptedRecordings(accountId);
      const storedMeetings = await listMeetings(accountId);
      if (!active) return;
      setMeetings(storedMeetings);
      if (recovered.length > 0) notify(`已恢复 ${recovered.length} 条中断录音`, "success");
      setReady(true);
    })().catch((error) => {
      notify(error instanceof Error ? error.message : "无法读取本机会议库", "error");
      setReady(true);
    });
    return () => { active = false; };
  }, [notify, session?.user.id]);

  useEffect(() => {
    const onOnline = () => setOnline(true);
    const onOffline = () => setOnline(false);
    const onInstallPrompt = (event: Event) => {
      event.preventDefault();
      setInstallPrompt(event as BeforeInstallPromptEvent);
    };
    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);
    window.addEventListener("beforeinstallprompt", onInstallPrompt);
    return () => {
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
      window.removeEventListener("beforeinstallprompt", onInstallPrompt);
    };
  }, []);

  useEffect(() => {
    localStorage.setItem(CONFIG_KEY, JSON.stringify(config));
  }, [config]);

  useEffect(() => {
    if (session) localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    else localStorage.removeItem(SESSION_KEY);
  }, [session]);

  const syncCloud = useCallback(async () => {
    const activeSession = sessionRef.current;
    if (!activeSession || !onlineRef.current || cloudSyncRunning.current) return;
    const revisionAtStart = meetingRevision.current;
    cloudSyncRunning.current = true;
    setCloudState("syncing");
    try {
      const synchronized = await synchronizeCloudMeetings(configRef.current, activeSession);
      const sameAccount = sessionRef.current?.user.id === activeSession.user.id;
      if (sameAccount && meetingRevision.current === revisionAtStart) {
        setMeetings(synchronized);
        setCloudState("synced");
      } else if (sameAccount) {
        setCloudState("pending");
      }
    } catch {
      setCloudState("pending");
    } finally {
      cloudSyncRunning.current = false;
      if (sessionRef.current?.user.id && sessionRef.current.user.id !== activeSession.user.id) {
        window.setTimeout(() => void syncCloud(), 0);
      }
    }
  }, []);

  const scheduleCloudSync = useCallback(() => {
    if (cloudSyncTimer.current) window.clearTimeout(cloudSyncTimer.current);
    if (!sessionRef.current || !onlineRef.current) {
      setCloudState("pending");
      return;
    }
    setCloudState("pending");
    cloudSyncTimer.current = window.setTimeout(() => void syncCloud(), 900);
  }, [syncCloud]);

  useEffect(() => {
    if (ready && session && online) void syncCloud();
    if (!online) setCloudState("pending");
  }, [online, ready, session, syncCloud]);

  useEffect(() => () => {
    if (cloudSyncTimer.current) window.clearTimeout(cloudSyncTimer.current);
  }, []);

  const loginUser = async (nextConfig: RuntimeConfig, username: string, password: string) => {
    setBusy(true);
    setConfig(nextConfig);
    try {
      const authenticated = await login(nextConfig, username, password);
      setReady(false);
      setMeetings([]);
      setSelectedMeetingId(undefined);
      setSession(authenticated);
      notify("登录成功", "success");
    } catch (error) {
      notify(error instanceof Error ? error.message : "登录失败", "error");
    } finally {
      setBusy(false);
    }
  };

  const requestRegistrationEmailCode = async (nextConfig: RuntimeConfig, email: string): Promise<AuthCodeDelivery | undefined> => {
    setBusy(true);
    setConfig(nextConfig);
    try {
      const delivery = await requestRegistrationCode(nextConfig, email);
      notify(`验证码已发送至 ${delivery.masked_identifier}`, "success");
      return delivery;
    } catch (error) {
      notify(error instanceof Error ? error.message : "验证码发送失败", "error");
      return undefined;
    } finally {
      setBusy(false);
    }
  };

  const registerUser = async (nextConfig: RuntimeConfig, username: string, email: string, password: string, code: string, referralCode?: string) => {
    setBusy(true);
    setConfig(nextConfig);
    try {
      const authenticated = await verifyEmailRegistration(nextConfig, username, email, password, code, referralCode);
      setReady(false);
      setMeetings([]);
      setSelectedMeetingId(undefined);
      setSession(authenticated);
      const points = authenticated.user.usage?.points_remaining;
      notify(points === undefined ? "账户已创建，体验积分已到账" : `账户已创建，已获得 ${points.toLocaleString()} 体验积分`, "success");
    } catch (error) {
      notify(error instanceof Error ? error.message : "注册失败", "error");
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    if (!session) { setGrowth(undefined); return; }
    void fetchGrowthOverview(config, session).then(setGrowth).catch(() => undefined);
  }, [config, session]);

  const refreshGrowth = useCallback(async () => {
    const active = sessionRef.current;
    if (!active) return;
    const latest = await fetchGrowthOverview(configRef.current, active);
    setGrowth(latest);
    setSession(await refreshSession(configRef.current, active));
  }, []);

  const refresh = useCallback(async () => {
    if (!session) throw new Error("请先登录账户");
    const refreshed = await refreshSession(config, session);
    setSession(refreshed);
    return refreshed;
  }, [config, session]);

  const updateMeeting = useCallback((meeting: Meeting) => {
    meetingRevision.current += 1;
    setMeetings((current) => [meeting, ...current.filter((item) => item.id !== meeting.id)].sort((left, right) => right.updatedAt - left.updatedAt));
    const accountId = sessionRef.current?.user.id;
    if (!accountId) {
      notify("请先登录账户", "error");
      return;
    }
    void saveMeeting(meeting, accountId).catch((error) => notify(error instanceof Error ? error.message : "会议保存失败", "error"));
    scheduleCloudSync();
  }, [notify, scheduleCloudSync]);

  const create = useCallback((mode: "record" | "text") => {
    const meeting = newMeeting(config.defaultTemplate);
    updateMeeting(meeting);
    setStartMode(mode);
    setSelectedMeetingId(meeting.id);
  }, [config.defaultTemplate, updateMeeting]);

  const importAudio = useCallback((file: File) => {
    const meeting = newMeeting(config.defaultTemplate);
    const extension = file.name.includes(".") ? file.name.split(".").pop() : audioExtension(file.type);
    const updated: Meeting = {
      ...meeting,
      title: file.name.replace(/\.[^.]+$/, "") || meeting.title,
      audio: file,
      audioName: file.name || `${meeting.id}.${extension}`,
      audioType: file.type
    };
    updateMeeting(updated);
    setStartMode("transcribe");
    setSelectedMeetingId(updated.id);
  }, [config.defaultTemplate, updateMeeting]);

  const confirmAction = async () => {
    if (!confirm) return;
    if (confirm.kind === "delete") {
      meetingRevision.current += 1;
      const accountId = sessionRef.current?.user.id;
      if (!accountId) return;
      await deleteMeetingRecord(confirm.meeting.id, accountId);
      setMeetings((current) => current.filter((meeting) => meeting.id !== confirm.meeting.id));
      if (selectedMeetingId === confirm.meeting.id) setSelectedMeetingId(undefined);
      notify("会议记录已删除", "success");
    } else {
      meetingRevision.current += 1;
      const accountId = sessionRef.current?.user.id;
      if (!accountId) return;
      await clearMeetings(accountId);
      setMeetings([]);
      setSelectedMeetingId(undefined);
      notify("会议记录已清空", "success");
    }
    setConfirm(undefined);
    if (onlineRef.current) void syncCloud();
  };

  const install = async () => {
    if (installPrompt) {
      await installPrompt.prompt();
      await installPrompt.userChoice;
      setInstallPrompt(undefined);
      return;
    }
    if (isIos && !standalone) setShowIosInstall(true);
  };

  if (!ready) {
    return <main className="loading-screen"><BrandMark size={76} /><span>智悟本</span></main>;
  }

  if (!session) {
    return <><AuthScreen config={config} busy={busy} onLogin={loginUser} onRequestRegistrationCode={requestRegistrationEmailCode} onRegister={registerUser} /><Toast toast={toast} onClose={() => setToast(undefined)} /></>;
  }

  if (selectedMeeting) {
    return (
      <>
        <MeetingWorkspace
          meeting={selectedMeeting}
          session={session}
          config={config}
          startMode={startMode}
          onBack={() => { setSelectedMeetingId(undefined); setStartMode(undefined); }}
          onChange={updateMeeting}
          onRefreshSession={refresh}
          onNotify={notify}
        />
        <Toast toast={toast} onClose={() => setToast(undefined)} />
      </>
    );
  }

  return (
    <>
      <AppShell tab={tab} onTabChange={setTab}>
        {tab === "home" && (
          <HomeScreen
            profile={session.user}
            growth={growth}
            meetings={meetings}
            selectedTemplate={config.defaultTemplate}
            onTemplateChange={(defaultTemplate) => setConfig((current) => ({ ...current, defaultTemplate }))}
            onCreate={create}
            onImportAudio={importAudio}
            onOpenMeeting={(meeting) => { setStartMode(undefined); setSelectedMeetingId(meeting.id); }}
            onOpenHistory={() => setTab("history")}
            onOpenCampaign={(campaignId) => setSelectedCampaignId(campaignId)}
          />
        )}
        {tab === "history" && (
          <HistoryScreen
            meetings={meetings}
            onOpen={(meeting) => setSelectedMeetingId(meeting.id)}
            onRename={(meeting, title) => updateMeeting({ ...meeting, title: title.trim() || meeting.title, updatedAt: Date.now() })}
            onDelete={(meeting) => setConfirm({ kind: "delete", meeting })}
            onClear={() => setConfirm({ kind: "clear" })}
          />
        )}
        {tab === "profile" && (
          <ProfileScreen
            profile={session.user}
            growth={growth}
            config={config}
            online={online}
            cloudState={cloudState}
            installAvailable={!standalone && (Boolean(installPrompt) || isIos)}
            onInstall={() => void install()}
            onSaveProfile={async (displayName, avatarDataUrl) => {
              try {
                const user = await updateProfile(config, session, displayName, avatarDataUrl);
                setSession({ ...session, user });
                notify("个人资料已更新", "success");
              } catch (error) {
                notify(error instanceof Error ? error.message : "资料更新失败", "error");
              }
            }}
            onSaveConfig={(next) => { setConfig(next); notify("服务设置已保存", "success"); }}
            onRedeem={async (code) => {
              const result = await redeemGrowthCode(config, session, code);
              setSession((current) => current ? { ...current, user: result.profile } : current);
              notify(result.message, "success");
              await refreshGrowth();
            }}
            onOpenCampaign={(campaignId) => setSelectedCampaignId(campaignId)}
            onLogout={() => {
              if (cloudSyncTimer.current) window.clearTimeout(cloudSyncTimer.current);
              meetingRevision.current += 1;
              setMeetings([]);
              setSelectedMeetingId(undefined);
              setSession(undefined);
              setCloudState("idle");
              setTab("home");
              notify("已退出当前账户", "success");
            }}
          />
        )}
      </AppShell>

      <ConfirmDialog
        open={Boolean(confirm)}
        title={confirm?.kind === "clear" ? "清空全部会议？" : "删除这条会议？"}
        message={confirm?.kind === "clear" ? "本机保存的音频、转写和纪要将一并删除。" : "删除后无法从本机会议库恢复。"}
        confirmText={confirm?.kind === "clear" ? "全部清空" : "删除"}
        danger
        onConfirm={() => void confirmAction()}
        onCancel={() => setConfirm(undefined)}
      />

      {selectedCampaignId && <GrowthCampaignDialog campaignId={selectedCampaignId} config={config} session={session} onClose={() => setSelectedCampaignId(undefined)} onChanged={refreshGrowth} onNotify={notify} />}

      {showIosInstall && (
        <div className="dialog-backdrop" onMouseDown={() => setShowIosInstall(false)}>
          <section className="install-dialog" role="dialog" aria-modal="true" onMouseDown={(event) => event.stopPropagation()}>
            <button className="icon-button dialog-close" onClick={() => setShowIosInstall(false)} title="关闭"><X /></button>
            <Share />
            <h2>安装智悟本</h2>
            <ol><li>点击 Safari 底部的分享按钮</li><li>选择“添加到主屏幕”</li><li>确认名称并点击“添加”</li></ol>
          </section>
        </div>
      )}
      <Toast toast={toast} onClose={() => setToast(undefined)} />
    </>
  );
}
