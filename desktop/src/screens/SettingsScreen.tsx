import { useEffect, useState } from "react";
import { Database, Globe, KeyRound, Monitor, Moon, ShieldCheck, SlidersHorizontal, Sun } from "lucide-react";
import { useApp } from "../state/AppStore";
import { Button, Group, PageHead, Switch, Textarea, Tag } from "../components/ui";

export function SettingsScreen() {
  const { settings, actions } = useApp();
  const [prompt, setPrompt] = useState(settings?.systemPrompt ?? "");
  const [savingPrompt, setSavingPrompt] = useState(false);

  useEffect(() => {
    setPrompt(settings?.systemPrompt ?? "");
  }, [settings?.systemPrompt]);

  if (!settings) return null;
  const promptDirty = prompt.trim() !== settings.systemPrompt.trim();

  async function savePrompt() {
    if (!prompt.trim()) return;
    setSavingPrompt(true);
    try {
      await actions.updateSettings({ systemPrompt: prompt.trim() });
      actions.showToast(actions.t("settings.promptSaved"));
    } finally {
      setSavingPrompt(false);
    }
  }

  return (
    <div className="page page--narrow">
      <PageHead
        eyebrow={actions.t("settings.eyebrow")}
        title={actions.t("settings.title")}
        subtitle={actions.t("settings.subtitle")}
      />

      <div className="settings-block">
        <h2 className="settings-block__title">{actions.t("settings.appearance")}</h2>
        <Group>
          <div className="settings-row">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.language")}</div>
              <div className="settings-row__sub">{actions.t("settings.languageHint")}</div>
            </div>
            <div className="settings-row__control">
              <div className="segmented" role="group" aria-label={actions.t("settings.language")}>
                <button
                  type="button"
                  className="segmented__option"
                  aria-pressed={settings.language === "fa"}
                  onClick={() => void actions.updateSettings({ language: "fa" })}
                >
                  فارسی
                </button>
                <button
                  type="button"
                  className="segmented__option"
                  aria-pressed={settings.language === "en"}
                  onClick={() => void actions.updateSettings({ language: "en" })}
                >
                  English
                </button>
              </div>
            </div>
          </div>

          <div className="settings-row settings-row--stack">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.theme")}</div>
            </div>
            <div className="settings-row__control">
              <div className="theme-preview">
                <button
                  type="button"
                  className="theme-card theme-card--system"
                  aria-pressed={settings.theme === "system"}
                  onClick={() => void actions.updateSettings({ theme: "system" })}
                >
                  <span className="theme-card__swatch" aria-hidden="true">
                    <i />
                    <i />
                  </span>
                  <span className="theme-card__label">
                    <Monitor /> {actions.t("settings.themeSystem")}
                  </span>
                </button>
                <button
                  type="button"
                  className="theme-card theme-card--light"
                  aria-pressed={settings.theme === "light"}
                  onClick={() => void actions.updateSettings({ theme: "light" })}
                >
                  <span className="theme-card__swatch" aria-hidden="true">
                    <i />
                    <i />
                    <i />
                  </span>
                  <span className="theme-card__label">
                    <Sun /> {actions.t("settings.themeLight")}
                  </span>
                </button>
                <button
                  type="button"
                  className="theme-card theme-card--dark"
                  aria-pressed={settings.theme === "dark"}
                  onClick={() => void actions.updateSettings({ theme: "dark" })}
                >
                  <span className="theme-card__swatch" aria-hidden="true">
                    <i />
                    <i />
                    <i />
                  </span>
                  <span className="theme-card__label">
                    <Moon /> {actions.t("settings.themeDark")}
                  </span>
                </button>
              </div>
            </div>
          </div>

          <div className="settings-row">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.density")}</div>
            </div>
            <div className="settings-row__control">
              <div className="segmented" role="group" aria-label={actions.t("settings.density")}>
                <button
                  type="button"
                  className="segmented__option"
                  aria-pressed={settings.density === "comfortable"}
                  onClick={() => void actions.updateSettings({ density: "comfortable" })}
                >
                  {actions.t("settings.densityComfortable")}
                </button>
                <button
                  type="button"
                  className="segmented__option"
                  aria-pressed={settings.density === "compact"}
                  onClick={() => void actions.updateSettings({ density: "compact" })}
                >
                  {actions.t("settings.densityCompact")}
                </button>
              </div>
            </div>
          </div>
        </Group>
      </div>

      <div className="settings-block">
        <h2 className="settings-block__title">{actions.t("settings.behavior")}</h2>
        <Group>
          <div className="settings-row">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.sendOnEnter")}</div>
              <div className="settings-row__sub">{actions.t("settings.sendOnEnterHint")}</div>
            </div>
            <div className="settings-row__control">
              <Switch
                checked={settings.sendOnEnter}
                onChange={(checked) => void actions.updateSettings({ sendOnEnter: checked })}
                label={actions.t("settings.sendOnEnter")}
              />
            </div>
          </div>
          <div className="settings-row">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.inspector")}</div>
              <div className="settings-row__sub">{actions.t("settings.inspectorHint")}</div>
            </div>
            <div className="settings-row__control">
              <Switch
                checked={settings.showInspector}
                onChange={(checked) => void actions.setInspectorOpen(checked)}
                label={actions.t("settings.inspector")}
              />
            </div>
          </div>
          <div className="settings-row settings-row--stack">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.systemPrompt")}</div>
              <div className="settings-row__sub">{actions.t("settings.systemPromptHint")}</div>
            </div>
            <div className="settings-row__control settings-row__control--stack">
              <Textarea
                value={prompt}
                onChange={(event) => setPrompt(event.target.value)}
                rows={5}
                aria-label={actions.t("settings.systemPrompt")}
              />
              <div className="settings-row__save">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={!promptDirty || savingPrompt || !prompt.trim()}
                  onClick={() => void savePrompt()}
                >
                  {savingPrompt ? <SlidersHorizontal className="spin" /> : <SlidersHorizontal />}
                  {actions.t("settings.systemPromptSave")}
                </Button>
              </div>
            </div>
          </div>
        </Group>
      </div>

      <div className="settings-block">
        <h2 className="settings-block__title">{actions.t("settings.experience")}</h2>
        <Group>
          <div className="settings-row">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.simple")} / {actions.t("settings.studio")}</div>
              <div className="settings-row__sub">
                {settings.mode === "simple" ? actions.t("settings.simpleHint") : actions.t("settings.studioHint")}
              </div>
            </div>
            <div className="settings-row__control">
              <div className="segmented" role="group" aria-label={actions.t("settings.experience")}>
                <button
                  type="button"
                  className="segmented__option"
                  aria-pressed={settings.mode === "simple"}
                  onClick={() => void actions.updateSettings({ mode: "simple" })}
                >
                  {actions.t("settings.simple")}
                </button>
                <button
                  type="button"
                  className="segmented__option"
                  aria-pressed={settings.mode === "studio"}
                  onClick={() => void actions.updateSettings({ mode: "studio" })}
                >
                  {actions.t("settings.studio")}
                </button>
              </div>
            </div>
          </div>
          <div className="settings-row">
            <div className="settings-row__main">
              <div className="settings-row__sub">{actions.t("settings.studioNote")}</div>
            </div>
          </div>
        </Group>
      </div>

      <div className="settings-block">
        <h2 className="settings-block__title">{actions.t("settings.data")}</h2>
        <Group>
          <div className="settings-row">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.localService")}</div>
              <div className="settings-row__sub">{actions.t("settings.localServiceHint")}</div>
            </div>
            <div className="settings-row__control">
              <Tag tone="sage">
                <span className="status-dot status-dot--sage" /> 127.0.0.1
              </Tag>
            </div>
          </div>
          <div className="settings-row">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.keyStorage")}</div>
              <div className="settings-row__sub">{actions.t("settings.keyStorageHint")}</div>
            </div>
            <div className="settings-row__control">
              <Tag tone="amber">
                <KeyRound /> {actions.t("settings.keyStorageValue")}
              </Tag>
            </div>
          </div>
          <div className="settings-row">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.data")}</div>
              <div className="settings-row__sub">{actions.t("settings.dataHint")}</div>
            </div>
            <div className="settings-row__control">
              <Database />
            </div>
          </div>
          <div className="settings-row">
            <div className="settings-row__main">
              <div className="settings-row__title">{actions.t("settings.about")}</div>
              <div className="settings-row__sub">
                {actions.t("settings.version", { version: "0.1.0" })}
              </div>
            </div>
            <div className="settings-row__control">
              <Tag>
                <Globe /> {settings.language === "fa" ? "RTL" : "LTR"}
              </Tag>
              <Tag tone="ember">
                <ShieldCheck /> {actions.t("common.local")}
              </Tag>
            </div>
          </div>
        </Group>
      </div>

      <div className="settings-about">
        <div>
          <strong>Omni Desktop</strong>
          <span>{actions.t("settings.designValue")}</span>
        </div>
        <div>
          <span>{actions.t("settings.design")}</span>
          <strong>{actions.t("settings.designValue")}</strong>
        </div>
      </div>
    </div>
  );
}
