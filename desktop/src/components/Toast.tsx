import { AlertTriangle, CheckCircle2, Info } from "lucide-react";
import { useApp } from "../state/AppStore";

export function Toast() {
  const { toast, actions } = useApp();
  const Icon = toast
    ? toast.kind === "error"
      ? AlertTriangle
      : toast.message === actions.t("common.copied")
        ? CheckCircle2
        : Info
    : null;
  return (
    <div className="toast-wrap" aria-live="polite" aria-atomic="true">
      {toast && Icon ? (
        <div key={toast.nonce} className={`toast toast--${toast.kind}`} role="status">
          <Icon aria-hidden="true" />
          <span>{toast.message}</span>
        </div>
      ) : null}
    </div>
  );
}
