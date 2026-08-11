import { Clock3, Home, UserRound } from "lucide-react";
import type { ReactNode } from "react";
import { BrandMark } from "./BrandMark";

export type AppTab = "home" | "history" | "profile";

interface AppShellProps {
  tab: AppTab;
  onTabChange: (tab: AppTab) => void;
  children: ReactNode;
}

export function AppShell({ tab, onTabChange, children }: AppShellProps) {
  return (
    <div className="app-shell">
      <aside className="desktop-rail">
        <div className="rail-brand"><BrandMark size={42} /><span>智悟本</span></div>
        <nav>
          <button className={tab === "home" ? "active" : ""} onClick={() => onTabChange("home")}><Home /><span>工作台</span></button>
          <button className={tab === "history" ? "active" : ""} onClick={() => onTabChange("history")}><Clock3 /><span>会议</span></button>
          <button className={tab === "profile" ? "active" : ""} onClick={() => onTabChange("profile")}><UserRound /><span>我的</span></button>
        </nav>
        <small>智能体 · 小Woo</small>
      </aside>
      <div className="app-content">{children}</div>
      <nav className="bottom-nav" aria-label="主导航">
        <button className={tab === "home" ? "active" : ""} onClick={() => onTabChange("home")}><Home /><span>工作台</span></button>
        <button className={tab === "history" ? "active" : ""} onClick={() => onTabChange("history")}><Clock3 /><span>会议</span></button>
        <button className={tab === "profile" ? "active" : ""} onClick={() => onTabChange("profile")}><UserRound /><span>我的</span></button>
      </nav>
    </div>
  );
}
