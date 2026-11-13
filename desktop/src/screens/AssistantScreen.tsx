import { useEffect, useRef, type KeyboardEvent } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  ArrowUp,
  CalendarDays,
  Code2,
  Copy,
  PenLine,
  Mic,
  RefreshCw,
  Scale,
  Square,
  Sparkles,
} from "lucide-react";
import { useApp } from "../state/AppStore";
import { formatNumber, formatTime, greetingKey, totalTokens } from "../lib/format";
import { isPromptGuard, isTokenLimitError } from "../lib/modelWarnings";
import { request } from "../lib/api";
import type { MessageKey } from "../lib/i18n";
import type { ChatMessage } from "../lib/types";
import { Banner, Pulse } from "../components/chat-bits";
import { LogoMark } from "../components/ui";

export function AssistantScreen() {
  const {
    active,
    drafts,
    activeProvider,
    settings,
    streaming,
    streamText,
    streamError,
    streamMeta,
    chatRun,
    actions,
    uiLanguage,
  } = useApp();
  const draftKey = active?.id ?? "new";
  const draft = drafts[draftKey] ?? "";
  const setDraft = (text: string) => actions.setDraft(draftKey, text);
  const scrollRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const stickRef = useRef(true);
  const messages = active?.messages ?? [];
  const lastAssistantId = [...messages].reverse().find((message) => message.role === "assistant")?.id;
  const isEmpty = messages.length === 0;
  const classifierSelected = isPromptGuard(activeProvider?.model);

  useEffect(() => {
    const node = scrollRef.current;
    if (!node) return;
    if (stickRef.current) node.scrollTop = node.scrollHeight;
  }, [messages.length, streamText, streamError, chatRun?.updatedAt]);

  useEffect(() => {
    stickRef.current = true;
    const timer = setTimeout(() => textareaRef.current?.focus(), 50);
    return () => clearTimeout(timer);
  }, [active?.id]);

  useEffect(() => {
    const node = textareaRef.current;
    if (!node) return;
    node.style.height = "auto";
    node.style.height = `${Math.min(node.scrollHeight, 190)}px`;
  }, [draft]);

  function submit() {
    const text = draft.trim();
    if (!text || streaming || !activeProvider?.enabled || classifierSelected) return;
    setDraft("");
    stickRef.current = true;
    void actions.send(text);
  }

  function onComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.nativeEvent.isComposing) return;
    const sendOnEnter = settings?.sendOnEnter !== false;
    const wantsSend = sendOnEnter ? event.key === "Enter" && !event.shiftKey : event.key === "Enter" && (event.metaKey || event.ctrlKey);
    if (wantsSend) {
      event.preventDefault();
      submit();
    }
  }

  function onScroll() {
    const node = scrollRef.current;
    if (!node) return;
    stickRef.current = node.scrollHeight - node.scrollTop - node.clientHeight < 120;
  }

  const suggestions = [
    { title: "chat.suggestion1Title", hint: "chat.suggestion1Hint", icon: <CalendarDays />, prompt: actions.t("chat.suggestion1Title") },
    { title: "chat.suggestion2Title", hint: "chat.suggestion2Hint", icon: <Code2 />, prompt: actions.t("chat.suggestion2Title") },
    { title: "chat.suggestion3Title", hint: "chat.suggestion3Hint", icon: <PenLine />, prompt: actions.t("chat.suggestion3Title") },
    { title: "chat.suggestion4Title", hint: "chat.suggestion4Hint", icon: <Scale />, prompt: actions.t("chat.suggestion4Title") },
  ] as const;

  const providerReady = Boolean(activeProvider?.enabled && !classifierSelected);

  const composer = (
<div className="composer-wrap">
        <label className="chat-auto-control"><input type="checkbox" checked={settings?.autoLowRisk === true} onChange={(event) => void actions.updateSettings({ autoLowRisk: event.target.checked })} disabled={streaming} />{uiLanguage === "fa" ? "کار خودکار: بازکردن برنامه و تایپ؛ ارسال و کارهای حساس با تأیید" : "Automatic: open apps and type; sending and sensitive actions require approval"}</label>
        {classifierSelected ? <Banner tone="ember" title={actions.t("chat.classifierTitle")} text={actions.t("chat.classifierText")} actionLabel={actions.t("chat.chooseModel")} onAction={() => actions.setScreen("models")} /> : null}
        <form
          className="composer"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <textarea
            ref={textareaRef}
            className="composer__input"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={onComposerKeyDown}
            placeholder={actions.t("chat.placeholder")}
            aria-label={actions.t("chat.placeholder")}
            rows={1}
          />
          <div className="composer__bar">
            <div className="composer__context">
              {activeProvider ? (
                <button type="button" className="composer__model" onClick={() => actions.setScreen("models")} title={actions.t("inspector.openModels")}>
                  <span className="ltr">{activeProvider.model}</span>
                  <span className="composer__model-state">{actions.t(activeProvider.enabled ? "chat.providerSelected" : "common.off")}</span>
                </button>
              ) : (
                <span className="tag tag--ember">{actions.t("models.add")}</span>
              )}
              {streamMeta?.usedMemory?.length ? (
                <span className="tag tag--ember">
                  {streamMeta.savedMemoryId ? (uiLanguage === "fa" ? "به حافظه سپرده شد" : "Saved to memory") : actions.t("chat.memoryUsed")} · {streamMeta.usedMemory.length}
                </span>
              ) : null}
            </div>
            <div className="composer__spacer" />
            <button type="button" className="icon-btn" aria-label={uiLanguage === "fa" ? "تایپ صوتی ویندوز" : "Windows voice typing"} title={uiLanguage === "fa" ? "تایپ صوتی ویندوز" : "Windows voice typing"} onClick={() => { textareaRef.current?.focus(); void request("/api/voice/windows-dictation", { method: "POST" }).catch(error => actions.showToast(error instanceof Error ? error.message : "Voice typing is unavailable.", "error")); }}><Mic /></button>
            {streaming ? (
              <button
                type="button"
                className="composer__send composer__send--stop"
                onClick={actions.stop}
                aria-label={actions.t("chat.stop")}
                title={actions.t("chat.stop")}
              >
                <Square />
              </button>
            ) : (
              <button
                type="submit"
                className="composer__send"
                disabled={!draft.trim() || !providerReady}
                aria-label={actions.t("chat.send")}
                title={actions.t("chat.send")}
              >
                <ArrowUp />
              </button>
            )}
          </div>
        </form>
        <div className="composer__hint">
          <span>
            {settings?.sendOnEnter !== false ? actions.t("chat.hintSend") : actions.t("chat.hintModSend")}
          </span>
          <span className="composer__hint-end">
            <Sparkles />
            {actions.t("chat.storageNote")}
          </span>
        </div>
      </div>
  );

  return (
    <div className={`chat${isEmpty ? " chat--empty" : ""}`}>
      <div className="chat__scroll" ref={scrollRef} onScroll={onScroll}>
        <div className={`chat__inner${isEmpty ? " chat__inner--empty" : ""}`}>
          {isEmpty ? (
            <div className="chat-hero">
              <div className="chat-hero__mark"><LogoMark size={42} /></div>
              <div className="chat-hero__eyebrow">
                <span className="status-dot" />
                {actions.t(greetingKey())}
                <span className="chat-hero__provider">
                  {activeProvider ? `${activeProvider.name} · ${activeProvider.model}` : actions.t("workspace.modelsMissing")}
                </span>
              </div>
              <h1 className="chat-hero__title">
                {actions.t("chat.heroA")} <em>{actions.t("chat.heroB")}</em>
              </h1>
              <p className="chat-hero__sub">{actions.t("chat.heroSub")}</p>

              {!providerReady && !classifierSelected ? (
                <div className="chat-hero__banner">
                  <Banner
                    tone="ember"
                    title={actions.t("chat.noProviderTitle")}
                    text={actions.t("chat.noProviderText")}
                    actionLabel={actions.t("chat.addProvider")}
                    onAction={() => actions.setScreen("models")}
                  />
                </div>
              ) : null}

              {composer}

              <div className="suggestions" aria-label={actions.t("palette.actions")}>
                {suggestions.map((suggestion) => (
                  <button
                    key={suggestion.title}
                    type="button"
                    className="suggestion"
                    onClick={() => {
                      setDraft(suggestion.prompt);
                      textareaRef.current?.focus();
                    }}
                  >
                    {suggestion.icon}
                    <span className="suggestion__body">
                      <span className="suggestion__title">{actions.t(suggestion.title)}</span>
                      <span className="suggestion__hint">{actions.t(suggestion.hint)}</span>
                    </span>
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className="messages" aria-live="polite" aria-busy={streaming}>
              {messages.map((message) => (
                <Message
                  key={message.id}
                  message={message}
                  streaming={streaming && message.id === "local-assistant"}
                  isLastAssistant={message.id === lastAssistantId}
                  onCopy={() => void actions.copyText(message.content)}
                  onRetry={() => classifierSelected ? actions.setScreen("models") : void actions.retry()}
                  t={actions.t}
                  language={uiLanguage}
                  showMemory={Boolean(message.usedMemory?.length)}
                />
              ))}
              {chatRun && (chatRun.steps.length > 0 || chatRun.pendingCodexAction) ? <section className="chat-computer-work" aria-label={uiLanguage === "fa" ? "کار روی رایانه" : "Computer work"}>
                <strong>{uiLanguage === "fa" ? "کار روی رایانه" : "Computer work"}</strong>
                {chatRun.steps.map(step => <div key={step.id} className="chat-computer-step">
                  <span dir="auto">{step.summary} · {step.state}</span>
                  {step.result ? <details><summary>{uiLanguage === "fa" ? "خروجی و کد پایان" : "Output and exit code"}</summary><p>{uiLanguage === "fa" ? "کد پایان" : "Exit code"}: {step.result.exitCode ?? "—"}</p><pre dir="auto">{step.result.stdout || step.result.stderr || (uiLanguage === "fa" ? "بدون خروجی" : "No output")}</pre>{step.result.stdout && step.result.stderr ? <pre dir="auto">{step.result.stderr}</pre> : null}</details> : null}
                </div>)}
                {chatRun.pendingCommand ? <div className="pending-change">
                  <h2>{uiLanguage === "fa" ? "دستور را بررسی و تأیید کنید" : "Review and approve this command"}</h2>
                  <p>{uiLanguage === "fa" ? "دستور با دسترسی حساب ویندوز شما اجرا می‌شود و محدود به پوشهٔ پروژه نیست. خروجی در سابقهٔ محلی ذخیره و به مدل انتخاب‌شده ارسال می‌شود." : "The command runs with your Windows account permissions, outside a project sandbox. Output is saved locally and sent to the selected model."}</p>
                  <code dir="ltr">{chatRun.pendingCommand.shell} · {chatRun.pendingCommand.cwd}</code>
                  <pre className="command-review" dir="ltr">{chatRun.pendingCommand.command}</pre>
                  <div className="studio-actions"><button type="button" className="computer-approve" onClick={() => void actions.approveComputerCommand(true)}>{uiLanguage === "fa" ? "تأیید و اجرا" : "Approve and run"}</button><button type="button" onClick={() => void actions.approveComputerCommand(false)}>{uiLanguage === "fa" ? "رد دستور" : "Reject command"}</button></div>
                </div> : null}
                {chatRun.pendingUiAction ? <div className="pending-change">
                  <h2>{uiLanguage === "fa" ? "این کار را تأیید می‌کنید؟" : "Approve this action?"}</h2>
                  <p>{chatRun.pendingUiAction.request.action === "type_text" ? (uiLanguage === "fa" ? "متن زیر در کادر انتخاب‌شده نوشته می‌شود. اگر متن دیگری در کادر باشد، با این متن جایگزین خواهد شد." : "This text will be placed in the selected field, replacing any existing text.") : chatRun.pendingUiAction.request.action === "open_app" ? (uiLanguage === "fa" ? "این برنامه باز می‌شود." : "This application will be opened.") : (uiLanguage === "fa" ? "پیش از تأیید، مقصد و متن را در پنجرهٔ برنامه بررسی کنید؛ فعال‌کردن کنترل به‌تنهایی تحویل پیام را تضمین نمی‌کند." : "Check the destination and text in the target window before approval; activating a control alone does not guarantee delivery.")}</p>
                  <p dir="auto">{chatRun.pendingUiAction.request.action} · {chatRun.pendingUiAction.request.windowTitle ?? chatRun.pendingUiAction.request.appId} · {chatRun.pendingUiAction.request.name}</p>
                  {chatRun.pendingUiAction.request.text !== undefined ? <pre className="command-review" dir="auto">{chatRun.pendingUiAction.request.text}</pre> : null}
                  <div className="studio-actions"><button type="button" className="computer-approve" onClick={() => void actions.approveComputerCommand(true)}>{uiLanguage === "fa" ? "تأیید و اجرا" : "Approve and run"}</button><button type="button" onClick={() => void actions.approveComputerCommand(false)}>{uiLanguage === "fa" ? "رد" : "Reject"}</button></div>
                </div> : null}
                {chatRun.pendingCodexAction ? <div className="pending-change">
                  <h2>{uiLanguage === "fa" ? "درخواست تأیید Codex" : "Codex approval request"}</h2>
                  <p>{uiLanguage === "fa" ? "این درخواست را پیش از اجرا بررسی کنید." : "Review this request before it runs."}</p>
                  <pre className="command-review" dir="auto">{chatRun.pendingCodexAction.description}</pre>
                  <div className="studio-actions"><button type="button" className="computer-approve" onClick={() => void actions.approveComputerCommand(true)}>{uiLanguage === "fa" ? "تأیید" : "Approve"}</button><button type="button" onClick={() => void actions.approveComputerCommand(false)}>{uiLanguage === "fa" ? "رد" : "Reject"}</button></div>
                </div> : null}
              </section> : null}
              {streaming && !messages.some((message) => message.id === "local-assistant") ? (
                <div className="msg">
                  <div className="msg__label">
                    <Sparkles />
                    {actions.t("chat.omni")}
                    <Pulse label={actions.t("chat.sending")} />
                  </div>
                </div>
              ) : null}
            </div>
          )}

          {streamError ? (
            <div className="chat__error">
              <Banner
                tone="rose"
                title={actions.t("chat.errorTitle")}
                text={classifierSelected ? actions.t("chat.classifierText") : isTokenLimitError(streamError) ? actions.t("chat.tokenLimitText") : streamError}
                actionLabel={classifierSelected || isTokenLimitError(streamError) ? actions.t("chat.chooseModel") : actions.t("common.retry")}
                onAction={() => classifierSelected || isTokenLimitError(streamError) ? actions.setScreen("models") : void actions.retry()}
              />
            </div>
          ) : null}
        </div>
      </div>

      {!isEmpty ? composer : null}
    </div>
  );
}

function Message({
  message,
  streaming,
  isLastAssistant,
  onCopy,
  onRetry,
  t,
  language,
  showMemory,
}: {
  message: ChatMessage;
  streaming: boolean;
  isLastAssistant: boolean;
  onCopy: () => void;
  onRetry: () => void;
  t: (key: MessageKey, vars?: Record<string, string | number>) => string;
  language: "fa" | "en";
  showMemory: boolean;
}) {
  const isUser = message.role === "user";
  const metadata = (
    <div className="msg__actions">
      <button type="button" className="icon-btn icon-btn--sm" onClick={onCopy} aria-label={t("common.copy")} title={t("common.copy")}>
        <Copy />
      </button>
      {!isUser && isLastAssistant && !streaming ? (
        <button type="button" className="icon-btn icon-btn--sm" onClick={onRetry} aria-label={t("chat.retry")} title={t("chat.retry")}>
          <RefreshCw />
        </button>
      ) : null}
    </div>
  );

  return (
    <article className={`msg${isUser ? " msg--user" : ""}`}>
      {!isUser ? (
        <div className="msg__label">
          <Sparkles />
          OMNI
          {message.status === "stopped" ? <span className="msg__stopped">· {t("chat.stopped")}</span> : null}
        </div>
      ) : null}
      {isUser ? (
        <div className="msg__bubble" dir="auto">
          {message.content}
        </div>
      ) : (
        <div className="msg__body" dir="auto">
          {message.content ? (
            <div className="markdown">
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                components={{
                  a: ({ children, ...props }) => (
                    <a {...props} target="_blank" rel="noreferrer noopener">
                      {children}
                    </a>
                  ),
                }}
              >
                {message.content}
              </ReactMarkdown>
            </div>
          ) : (
            <Pulse label="…" />
          )}
          {streaming ? <span className="typing-caret" aria-hidden="true" /> : null}
        </div>
      )}
      <div className={`msg__meta${isUser ? " msg__meta--user" : ""}`}>
        {metadata}
        <span>{formatTime(message.createdAt, language)}</span>
        {!isUser && message.model ? <span className="ltr">· {message.model}</span> : null}
        {!isUser && message.completionTokens ? (
          <span>
            ·{" "}
            {t("chat.tokens", {
              value: formatNumber(
                totalTokens(message.promptTokens ?? 0, completionTokensNumber(message.completionTokens)),
                language,
              ),
            })}
          </span>
        ) : null}
        {showMemory ? <span className="tag tag--ember">{t("inspector.memory")}</span> : null}
      </div>
    </article>
  );
}

function completionTokensNumber(value: number | undefined): number {
  return typeof value === "number" ? value : 0;
}
