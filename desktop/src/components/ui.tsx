import {
  useEffect,
  useRef,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
  type InputHTMLAttributes,
} from "react";
import { X } from "lucide-react";

export function LogoMark({ size = 28 }: { size?: number }) {
  return (
    <img src="/omni-logo.png" width={size} height={size} alt="" aria-hidden="true" style={{ borderRadius: "24%", objectFit: "cover" }} />
  );
}

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
type ButtonSize = "sm" | "md" | "lg";

export function Button({
  variant = "secondary",
  size = "md",
  block,
  className = "",
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
  block?: boolean;
}) {
  return (
    <button
      type="button"
      className={`btn btn--${variant} btn--${size}${block ? " btn--block" : ""} ${className}`.trim()}
      {...props}
    >
      {children}
    </button>
  );
}

export function IconButton({
  label,
  size = "md",
  danger,
  className = "",
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  label: string;
  size?: "sm" | "md";
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      className={`icon-btn${size === "sm" ? " icon-btn--sm" : ""}${danger ? " icon-btn--danger" : ""} ${className}`.trim()}
      aria-label={label}
      title={label}
      {...props}
    >
      {children}
    </button>
  );
}

export function Field({
  label,
  hint,
  error,
  htmlFor,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  htmlFor?: string;
  children: ReactNode;
}) {
  return (
    <div className="field">
      <label className="field__label" htmlFor={htmlFor}>
        {label}
      </label>
      {children}
      {error ? <div className="field__error">{error}</div> : hint ? <div className="field__hint">{hint}</div> : null}
    </div>
  );
}

export function Input({ className = "", ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={`input ${className}`.trim()} {...props} />;
}

export function Textarea({ className = "", ...props }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={`textarea ${className}`.trim()} {...props} />;
}

export function Select({ className = "", children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={`select ${className}`.trim()} {...props}>
      {children}
    </select>
  );
}

export function Switch({
  checked,
  onChange,
  label,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
}) {
  return (
    <label className="switch">
      <input
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        aria-label={label}
      />
      <span className="switch__track" aria-hidden="true" />
    </label>
  );
}

export function Tag({
  tone = "neutral",
  children,
  className = "",
}: {
  tone?: "neutral" | "ember" | "sage" | "amber" | "rose" | "steel";
  children: ReactNode;
  className?: string;
}) {
  return <span className={`tag${tone === "neutral" ? "" : ` tag--${tone}`} ${className}`.trim()}>{children}</span>;
}

export function Dot({ tone = "neutral" }: { tone?: "neutral" | "sage" | "ember" | "amber" | "rose" | "steel" }) {
  return <span className={`status-dot${tone === "neutral" ? "" : ` status-dot--${tone}`}`} aria-hidden="true" />;
}

export function Spinner({ large, label }: { large?: boolean; label?: string }) {
  return <span className={`spinner${large ? " spinner--lg" : ""}`} role="status" aria-label={label ?? "Loading"} />;
}

export function EmptyState({
  icon,
  title,
  text,
  actions,
}: {
  icon: ReactNode;
  title: string;
  text: string;
  actions?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty__mark">{icon}</div>
      <div className="empty__title">{title}</div>
      <p className="empty__text">{text}</p>
      {actions ? <div className="empty__actions">{actions}</div> : null}
    </div>
  );
}

export function Dialog({
  open,
  onClose,
  title,
  subtitle,
  children,
  footer,
  wide,
  bare,
  className = "",
}: {
  open: boolean;
  onClose: () => void;
  title?: string;
  subtitle?: string;
  children?: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  bare?: boolean;
  className?: string;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    const onCancel = (event: Event) => {
      event.preventDefault();
      onClose();
    };
    dialog.addEventListener("cancel", onCancel);
    return () => dialog.removeEventListener("cancel", onCancel);
  }, [onClose]);

  return (
    <dialog
      ref={ref}
      className={`dialog${wide ? " dialog--wide" : ""}${bare ? " dialog--bare" : ""} ${className}`.trim()}
      aria-label={title}
      onClose={onClose}
    >
      {bare ? (
        children
      ) : (
        <>
          <div className="dialog__head">
            <div>
              {title ? <div className="dialog__title">{title}</div> : null}
              {subtitle ? <div className="dialog__subtitle">{subtitle}</div> : null}
            </div>
            <IconButton label="Close" onClick={onClose}>
              <X />
            </IconButton>
          </div>
          <div className="dialog__body">{children}</div>
          {footer ? <div className="dialog__foot">{footer}</div> : null}
        </>
      )}
    </dialog>
  );
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  body,
  confirmLabel,
  cancelLabel,
  danger,
  busy,
}: {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  body: string;
  confirmLabel: string;
  cancelLabel: string;
  danger?: boolean;
  busy?: boolean;
}) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      subtitle={body}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button variant={danger ? "danger" : "primary"} onClick={onConfirm} disabled={busy}>
            {busy ? <Spinner /> : null}
            {confirmLabel}
          </Button>
        </>
      }
    />
  );
}

export function Group({ children, className = "", ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={`group ${className}`.trim()} {...props}>
      {children}
    </div>
  );
}

export function PageHead({
  eyebrow,
  title,
  subtitle,
  actions,
}: {
  eyebrow: string;
  title: string;
  subtitle?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="page__head">
      <div>
        <div className="page__eyebrow">{eyebrow}</div>
        <h1 className="page__title">{title}</h1>
        {subtitle ? <p className="page__subtitle">{subtitle}</p> : null}
      </div>
      {actions ? <div className="page__actions">{actions}</div> : null}
    </div>
  );
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd>{children}</kbd>;
}
