import { Cpu, Menu, PanelLeft, PanelRight, Plus, Search } from "lucide-react";
import { useApp } from "../state/AppStore";
import type { MessageKey } from "../lib/i18n";
import { Dot, IconButton } from "./ui";

const screenLabel: Record<string, MessageKey> = {
  assistant: "nav.assistant",
  workspace: "nav.workspace",
  memory: "nav.memory",
  models: "nav.models",
  settings: "nav.settings",
  agents: "nav.agents",
  code: "nav.code",
  web: "nav.web",
  devices: "nav.devices",
};

export function Topbar({ onToggleSidebar }: { onToggleSidebar: () => void }) {
  const { active, screen, activeProvider, inspectorOpen, settings, actions, uiLanguage } = useApp();
  const showingChat = screen === "assistant";
  const title = showingChat ? active?.title || actions.t("nav.assistant") : actions.t(screenLabel[screen] ?? "nav.assistant");
  const eyebrow = showingChat
    ? active
      ? actions.t("nav.assistant")
      : actions.t("common.local")
    : actions.t("common.appName");
  const PanelIcon = uiLanguage === "fa" ? PanelLeft : PanelRight;

  return (
    <header className="topbar">
      <IconButton
        label={actions.t("topbar.toggleSidebar")}
        className="topbar__menu"
        onClick={onToggleSidebar}
      >
        <Menu />
      </IconButton>
      <div className="topbar__title">
        <span className="topbar__eyebrow">{eyebrow}</span>
        <span className="topbar__name">{title}</span>
      </div>
      <div className="topbar__spacer" />
      <div className="topbar__actions">
        <select
          className="select topbar__personality"
          aria-label={actions.t("personality.label")}
          title={actions.t("personality.label")}
          value={settings?.personality ?? "default"}
          onChange={(event) => void actions.updateSettings({ personality: event.target.value as NonNullable<typeof settings>["personality"] }).catch((error: unknown) => actions.showToast(error instanceof Error ? error.message : actions.t("error.generic"), "error"))}
        >
          <option value="default">{actions.t("personality.default")}</option>
          <option value="concise">{actions.t("personality.concise")}</option>
          <option value="teacher">{actions.t("personality.teacher")}</option>
          <option value="engineer">{actions.t("personality.engineer")}</option>
        </select>
        <button
          type="button"
          className="model-chip"
          onClick={() => actions.setScreen("models")}
          title={actions.t("inspector.openModels")}
        >
          <Dot tone={activeProvider?.enabled ? "neutral" : "amber"} />
          <Cpu />
          <span className="model-chip__label">
            {activeProvider ? activeProvider.model : actions.t("models.add")}
          </span>
        </button>
        <IconButton label={actions.t("topbar.command")} onClick={() => actions.setPaletteOpen(true)}>
          <Search />
        </IconButton>
        {showingChat ? (
          <IconButton
            label={actions.t("topbar.toggleInspector")}
            className="topbar__inspector"
            onClick={() => actions.setInspectorOpen(!inspectorOpen)}
            aria-pressed={inspectorOpen}
          >
            <PanelIcon />
          </IconButton>
        ) : null}
        <IconButton label={actions.t("topbar.newChat")} onClick={actions.newChat}>
          <Plus />
        </IconButton>
      </div>
    </header>
  );
}
