import { CheckCircle2, CircleAlert, X } from "lucide-react";

export interface ToastState {
  message: string;
  kind: "success" | "error";
}

export function Toast({ toast, onClose }: { toast?: ToastState; onClose: () => void }) {
  if (!toast) return null;
  return (
    <div className={`toast ${toast.kind}`} role="status">
      {toast.kind === "success" ? <CheckCircle2 /> : <CircleAlert />}
      <span>{toast.message}</span>
      <button className="icon-button" onClick={onClose} title="关闭"><X /></button>
    </div>
  );
}
