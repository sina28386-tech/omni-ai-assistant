import { useMemo } from "react";
import { ArrowRight, Brain, Cpu, Database, MessageSquareText, Plus, ShieldCheck, Sparkles } from "lucide-react";
import { useApp } from "../state/AppStore";
import { formatDate, formatNumber, totalTokens, truncate } from "../lib/format";
import { Button, Dot, EmptyState, Group, PageHead, Tag } from "../components/ui";

export function WorkspaceScreen() {
  const { conversations, memory, usage, activeProvider, actions, uiLanguage } = useApp();
  const recent = conversations.slice(0, 6);
  const totalMessages = conversations.reduce((sum, row) => sum + row.messageCount, 0);
  const tokens = totalTokens(usage?.promptTokens ?? 0, usage?.completionTokens ?? 0);
  const activity = useMemo(() => {
    const byDay = new Map((usage?.byDay ?? []).map((day) => [day.day, day.requests]));
    return Array.from({ length: 14 }, (_, index) => {
      const timestamp = Date.now() - (13 - index) * 86_400_000;
      const day = new Date(timestamp).toISOString().slice(0, 10);
      return { day, timestamp, requests: byDay.get(day) ?? 0 };
    });
  }, [usage]);
  const maxDay = Math.max(1, ...activity.map((day) => day.requests));

  return (
    <div className="page">
      <PageHead
        eyebrow={actions.t("workspace.eyebrow")}
        title={actions.t("workspace.title")}
        subtitle={actions.t("workspace.subtitle")}
        actions={
          <>
            <Button variant="primary" onClick={actions.newChat}>
              <Plus />
              {actions.t("workspace.newChat")}
            </Button>
            <Button variant="secondary" onClick={() => actions.setScreen("assistant")}>
              <MessageSquareText />
              {actions.t("workspace.openAssistant")}
            </Button>
          </>
        }
      />

      <div className="workspace-hero">
        <div className="workspace-hero__main">
          <div className="workspace-hero__eyebrow">
            <span className="status-dot status-dot--sage" />
            {actions.t("workspace.localReady")}
          </div>
          <div className="workspace-hero__title">
            {activeProvider
              ? actions.t("workspace.modelsReady", { name: activeProvider.name, model: activeProvider.model })
              : actions.t("workspace.modelsMissing")}
          </div>
          <p className="workspace-hero__sub">{actions.t("workspace.modelsHint")}</p>
          <div className="workspace-hero__actions">
            <Button variant="secondary" size="sm" onClick={() => actions.setScreen("models")}>
              <Cpu />
              {actions.t("workspace.openModels")}
            </Button>
            <Button variant="ghost" size="sm" onClick={() => actions.setScreen("memory")}>
              <Brain />
              {actions.t("workspace.openMemory")}
            </Button>
          </div>
        </div>
        <div className="workspace-stats">
          <div className="stat">
            <span className="stat__label">{actions.t("workspace.statConversations")}</span>
            <span className="stat__value">{formatNumber(conversations.length, uiLanguage)}</span>
          </div>
          <div className="stat">
            <span className="stat__label">{actions.t("workspace.statMessages")}</span>
            <span className="stat__value">{formatNumber(totalMessages, uiLanguage)}</span>
          </div>
          <div className="stat">
            <span className="stat__label">{actions.t("workspace.statTokens")}</span>
            <span className="stat__value">{formatNumber(tokens, uiLanguage)}</span>
          </div>
        </div>
      </div>

      <div className="workspace-grid">
        <section>
          <div className="section__head">
            <h2 className="section__title">{actions.t("workspace.recent")}</h2>
          </div>
          {recent.length ? (
            <div className="recent-list">
              {recent.map((row) => (
                <button
                  key={row.id}
                  type="button"
                  className="recent-row"
                  onClick={() => void actions.openConversation(row.id)}
                >
                  <div className="recent-row__main">
                    <span className="recent-row__title">{row.title || actions.t("common.untitled")}</span>
                    <span className="recent-row__meta">{truncate(row.preview || actions.t("common.untitled"), 100)}</span>
                  </div>
                  <span className="recent-row__time">
                    {formatDate(row.updatedAt, uiLanguage, { month: "short", day: "numeric" })}
                  </span>
                </button>
              ))}
            </div>
          ) : (
            <EmptyState
              icon={<MessageSquareText />}
              title={actions.t("workspace.recentEmpty")}
              text={actions.t("workspace.subtitle")}
              actions={
                <Button variant="primary" onClick={actions.newChat}>
                  <Plus />
                  {actions.t("workspace.newChat")}
                </Button>
              }
            />
          )}

          <div className="usage-panel">
            <div className="usage-panel__head">
              <span className="section__title">{actions.t("workspace.activity")}</span>
              <span className="section__hint">{actions.t("models.requests")}: {formatNumber(usage?.requests ?? 0, uiLanguage)}</span>
            </div>
            {usage && usage.requests > 0 ? (
              <div className="usage-bars" aria-label={actions.t("workspace.activity")}>
                {activity.map((day) => (
                  <div
                    className={`usage-bar${day.requests ? "" : " usage-bar--empty"}`}
                    key={day.day}
                    title={`${formatDate(day.timestamp, uiLanguage, { month: "short", day: "numeric" })} · ${formatNumber(day.requests, uiLanguage)}`}
                  >
                    <span style={{ height: `${Math.max(6, (day.requests / maxDay) * 100)}%` }} />
                  </div>
                ))}
              </div>
            ) : (
              <div className="usage-empty">{actions.t("workspace.activityEmpty")}</div>
            )}
          </div>
        </section>

        <aside className="side-list">
          <Group>
            <div className="group__row">
              <div className="group__icon group__icon--ember">
                <Brain />
              </div>
              <div className="group__main">
                <div className="group__title">
                  {actions.t("workspace.memory")}
                  <Tag tone={memory.length ? "ember" : "neutral"}>{formatNumber(memory.length, uiLanguage)}</Tag>
                </div>
                <div className="group__subtitle">
                  {memory.length ? memory.slice(0, 2).map((fact) => fact.key).join(" · ") : actions.t("workspace.memoryEmpty")}
                </div>
                <button type="button" className="inline-link" onClick={() => actions.setScreen("memory")}>
                  {actions.t("workspace.openMemory")}
                  <ArrowRight className="flip" />
                </button>
              </div>
            </div>
            <div className="group__row">
              <div className="group__icon">
                <Cpu />
              </div>
              <div className="group__main">
                <div className="group__title">{actions.t("workspace.models")}</div>
                <div className="group__subtitle">
                  {activeProvider ? (
                    <span className="ltr">
                      {activeProvider.name} · {activeProvider.model}
                    </span>
                  ) : (
                    actions.t("workspace.modelsMissing")
                  )}
                </div>
                <button type="button" className="inline-link" onClick={() => actions.setScreen("models")}>
                  {actions.t("workspace.openModels")}
                  <ArrowRight className="flip" />
                </button>
              </div>
              <span className="group__trailing">
                <Dot tone={activeProvider?.enabled ? "sage" : "ember"} />
              </span>
            </div>
            <div className="group__row">
              <div className="group__icon">
                <ShieldCheck />
              </div>
              <div className="group__main">
                <div className="group__title">{actions.t("workspace.local")}</div>
                <div className="group__subtitle">{actions.t("workspace.localReady")}</div>
              </div>
              <span className="group__trailing">
                <Tag tone="sage">{actions.t("common.ready")}</Tag>
              </span>
            </div>
          </Group>

          <div className="side-card">
            <div className="side-card__head">
              <Sparkles />
              <span>{actions.t("agents.eyebrow")}</span>
              <Tag tone="amber">{actions.t("common.unavailable")}</Tag>
            </div>
            <p>{actions.t("agents.subtitle")}</p>
            <Button variant="ghost" size="sm" onClick={() => actions.setScreen("agents")}>
              {actions.t("agents.contract")}
              <ArrowRight className="flip" />
            </Button>
          </div>

          <div className="side-note">
            <Database />
            <span>{actions.t("settings.dataHint")}</span>
          </div>
        </aside>
      </div>
    </div>
  );
}
