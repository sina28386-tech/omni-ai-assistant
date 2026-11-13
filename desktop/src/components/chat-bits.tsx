import type { ReactNode } from "react";
import { AlertTriangle, ArrowRight, Info } from "lucide-react";

export function Banner({
  tone = "neutral",
  title,
  text,
  actionLabel,
  onAction,
  children,
}: {
  tone?: "neutral" | "ember" | "rose";
  title: string;
  text?: string;
  actionLabel?: string;
  onAction?: () => void;
  children?: ReactNode;
}) {
  const Icon = tone === "rose" ? AlertTriangle : tone === "ember" ? Info : Info;
  return (
    <div
      className={`banner${tone === "neutral" ? "" : ` banner--${tone}`}`}
      role={tone === "rose" ? "alert" : undefined}
    >
      <Icon aria-hidden="true" />
      <div className="banner__body">
        <div className="banner__title">{title}</div>
        {text ? <div className="banner__text">{text}</div> : null}
        {children}
        {actionLabel && onAction ? (
          <div className="banner__actions">
            <button type="button" className="btn btn--secondary btn--sm" onClick={onAction}>
              {actionLabel}
              <ArrowRight className="flip" />
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
}

export function Pulse({ label }: { label: string }) {
  return (
    <span className="pulse" role="status" aria-label={label}>
      <span className="pulse__bars" aria-hidden="true">
        <i />
        <i />
        <i />
        <i />
      </span>
      <span className="pulse__label">{label}</span>
    </span>
  );
}
