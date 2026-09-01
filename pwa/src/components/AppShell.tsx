import { Bell, Clock3, Compass, Home, UserRound } from "lucide-react";
import type { ReactNode } from "react";
import { BrandMark } from "./BrandMark";

export type AppTab = "home" | "history" | "community" | "notifications" | "profile";

interface AppShellProps {
  tab: AppTab;
  onTabChange: (tab: AppTab) => void;
  children: ReactNode;
  notificationCount?: number;
}

export function AppShell({ tab, onTabChange, children, notificationCount = 0 }: AppShellProps) {
  const notificationLabel = notificationCount > 0 ? `通知，${notificationCount} 条未读` : "通知";
  return (
    <div className="app-shell">
      <aside className="desktop-rail">
        <div className="rail-brand"><BrandMark size={42} /><span>智悟本</span></div>
        <nav>
          <button className={tab === "home" ? "active" : ""} onClick={() => onTabChange("home")}><Home /><span>记录</span></button>
          <button className={tab === "history" ? "active" : ""} onClick={() => onTabChange("history")}><Clock3 /><span>会议</span></button>
          <button className={tab === "community" ? "active" : ""} onClick={() => onTabChange("community")}><Compass /><span>社区</span></button>
          <button className={tab === "notifications" ? "active" : ""} onClick={() => onTabChange("notifications")} aria-label={notificationLabel}><span className="nav-icon-wrap"><Bell />{notificationCount > 0 && <i className="nav-unread-dot" aria-hidden="true" />}</span><span>通知</span></button>
          <button className={tab === "profile" ? "active" : ""} onClick={() => onTabChange("profile")}><UserRound /><span>我的</span></button>
        </nav>
        <small>会议整理</small>
      </aside>
      <div className="app-content">{children}</div>
      <nav className="bottom-nav" aria-label="主导航">
        <button className={tab === "home" ? "active" : ""} onClick={() => onTabChange("home")}><Home /><span>记录</span></button>
        <button className={tab === "history" ? "active" : ""} onClick={() => onTabChange("history")}><Clock3 /><span>会议</span></button>
        <button className={tab === "community" ? "active" : ""} onClick={() => onTabChange("community")}><Compass /><span>社区</span></button>
        <button className={tab === "notifications" ? "active" : ""} onClick={() => onTabChange("notifications")} aria-label={notificationLabel}><span className="nav-icon-wrap"><Bell />{notificationCount > 0 && <i className="nav-unread-dot" aria-hidden="true" />}</span><span>通知</span></button>
        <button className={tab === "profile" ? "active" : ""} onClick={() => onTabChange("profile")}><UserRound /><span>我的</span></button>
      </nav>
    </div>
  );
}
