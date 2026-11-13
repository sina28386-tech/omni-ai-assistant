import { Brain, Cpu, Database, ShieldCheck, X } from "lucide-react";
import { useApp } from "../state/AppStore";
import { formatDate, formatNumber, totalTokens } from "../lib/format";
import { Button, Dot, IconButton, Tag } from "./ui";

export function Inspector() {
  const { active, activeProvider, memory, usage, streamMeta, streaming, actions, uiLanguage } = useApp();
  const lastAssistant = active
    ? [...active.messages].reverse().find((message) => message.role === "assistant" && message.usedMemory?.length)
    : undefined;
  const recalled = streamMeta?.usedMemory
    ? streamMeta.usedMemory
    : (lastAssistant?.usedMemory ?? [])
        .map((key) => {
          const fact = memory.find((item) => item.key === key);
          return fact
            ? { id: fact.id, key: fact.key, value: fact.value }
            : { id: key, key, value: "" };
        })
        .filter((item) => item.key);
  const threadTokens = active
    ? active.messages.reduce(
        (sum, message) => sum + totalTokens(message.promptTokens ?? 0, message.completionTokens ?? 0),
        0,
      )
    : 0;

  return (
    <aside className="inspector" aria-label={actions.t("inspector.title")}>
      <div className="inspector__head">
        <span className="inspector__title">{actions.t("inspector.title")}</span>
        <IconButton label={actions.t("inspector.close")} size="sm" onClick={() => actions.setInspectorOpen(false)}>
          <X />
        </IconButton>
      </div>

      <section className="inspector__section">
        <div className="inspector__label">{actions.t("inspector.provider")}</div>
        {activeProvider ? (
          <div className="inspector__provider">
            <span className="inspector__provider-mark">{activeProvider.name.slice(0, 1).toUpperCase()}</span>
            <div className="inspector__provider-main">
              <div className="inspector__provider-name">{activeProvider.name}</div>
              <div className="inspector__provider-model ltr">{activeProvider.model}</div>
            </div>
            <Dot tone={activeProvider.enabled ? "neutral" : "amber"} />
          </div>
        ) : (
          <div className="inspector__muted">{actions.t("inspector.noProvider")}</div>
        )}
        <Button
          variant="secondary"
          size="sm"
          block
          className="inspector__action"
          onClick={() => actions.setScreen("models")}
        >
          <Cpu />
          {actions.t("inspector.openModels")}
        </Button>
      </section>

      <section className="inspector__section">
        <div className="inspector__label">
          {actions.t("inspector.memory")}
          {recalled.length ? <Tag tone="ember">{recalled.length}</Tag> : null}
        </div>
        {recalled.length ? (
          <div className="recall-list">
            {recalled.slice(0, 6).map((fact) => (
              <div className="recall" key={fact.id}>
                <div className="recall__key">{fact.key}</div>
                {fact.value ? <div className="recall__value">{fact.value}</div> : null}
              </div>
            ))}
          </div>
        ) : (
          <div className="inspector__muted">
            {streaming ? actions.t("chat.sending") : actions.t("inspector.memoryEmpty")}
          </div>
        )}
        <div className="inspector__hint">{actions.t("inspector.memoryHint")}</div>
        <button
          type="button"
          className="inspector__link"
          onClick={() => actions.setScreen("memory")}
        >
          <Brain />
          {actions.t("workspace.openMemory")}
        </button>
      </section>

      <section className="inspector__section">
        <div className="inspector__label">{actions.t("inspector.usage")}</div>
        <div className="inspector__stats">
          <div className="inspector__stat">
            <span>{actions.t("workspace.statMessages")}</span>
            <strong>{formatNumber(active?.messages.length ?? 0, uiLanguage)}</strong>
            <small>{actions.t("inspector.thisThread")}</small>
          </div>
          <div className="inspector__stat">
            <span>{actions.t("workspace.statTokens")}</span>
            <strong>{formatNumber(threadTokens, uiLanguage)}</strong>
            <small>{actions.t("inspector.thisThread")}</small>
          </div>
          <div className="inspector__stat inspector__stat--wide">
            <span>
              {actions.t("models.requests")} · {actions.t("workspace.usage")}
            </span>
            <strong>{formatNumber(usage?.requests ?? 0, uiLanguage)}</strong>
          </div>
        </div>
      </section>

      {active ? (
        <section className="inspector__section">
          <div className="inspector__label">{actions.t("inspector.about")}</div>
          <div className="inspector__about">
            <div>
              <span>{actions.t("inspector.created")}</span>
              <strong>{formatDate(active.createdAt, uiLanguage, { dateStyle: "medium" })}</strong>
            </div>
            <div>
              <span>{actions.t("inspector.provider")}</span>
              <strong>{activeProvider?.name ?? actions.t("common.none")}</strong>
            </div>
          </div>
        </section>
      ) : null}

      <section className="inspector__section inspector__local">
        <div className="inspector__local-head">
          <ShieldCheck />
          <strong>{actions.t("inspector.local")}</strong>
        </div>
        <p>{actions.t("inspector.localText")}</p>
        <div className="inspector__footnote">
          <Database />
          <span>127.0.0.1</span>
        </div>
      </section>
    </aside>
  );
}
