import { useEffect, useState } from "react";
import {
  Check,
  Cpu,
  Eye,
  EyeOff,
  KeyRound,
  Pencil,
  Plus,
  RefreshCw,
  Server,
  ShieldCheck,
  Trash2,
  Zap,
} from "lucide-react";
import { useApp } from "../state/AppStore";
import { api, request } from "../lib/api";
import { isPromptGuard } from "../lib/modelWarnings";
import { formatNumber, providerInitial, totalTokens } from "../lib/format";
import type { ProviderProfile } from "../lib/types";
import {
  Button,
  ConfirmDialog,
  Dialog,
  Dot,
  EmptyState,
  Field,
  IconButton,
  Input,
  PageHead,
  Switch,
  Tag,
} from "../components/ui";

interface FormState {
  name: string;
  baseUrl: string;
  model: string;
  apiKey: string;
  temperature: string;
  maxTokens: string;
  enabled: boolean;
}

const emptyForm: FormState = {
  name: "",
  baseUrl: "https://api.openai.com/v1",
  model: "",
  apiKey: "",
  temperature: "0.7",
  maxTokens: "2048",
  enabled: true,
};

// These endpoints expose an OpenAI-compatible API; availability still depends on the account.
const providerPresets = [
  { name: "OpenAI", baseUrl: "https://api.openai.com/v1" },
  { name: "Claude API", baseUrl: "https://api.anthropic.com/v1" },
  { name: "Google Gemini", baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai" },
  { name: "Groq", baseUrl: "https://api.groq.com/openai/v1" },
  { name: "DeepSeek", baseUrl: "https://api.deepseek.com/v1" },
  { name: "Mistral", baseUrl: "https://api.mistral.ai/v1" },
  { name: "OpenRouter", baseUrl: "https://openrouter.ai/api/v1" },
] as const;

export function ModelsScreen() {
  const { providers, settings, usage, actions, uiLanguage } = useApp();
  const [editing, setEditing] = useState<ProviderProfile | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [showKey, setShowKey] = useState(false);
  const [models, setModels] = useState<string[]>([]);
  const [fetching, setFetching] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ProviderProfile | null>(null);
  const [testState, setTestState] = useState<{ id: string; ok: boolean; text: string } | null>(null);
  const [codexModels, setCodexModels] = useState<string[]>([]);
  const [codexModel, setCodexModel] = useState("");
  const [codexStatusText, setCodexStatusText] = useState("");
  const [codexBusy, setCodexBusy] = useState(false);

  async function checkCodex() {
    setCodexBusy(true); setCodexStatusText("");
    try {
      const status = await request<{ authenticated: boolean; models: string[] }>("/api/integrations/codex");
      setCodexModels(status.models); setCodexModel(status.models[0] ?? "");
      setCodexStatusText(status.authenticated ? (uiLanguage === "fa" ? "حساب Codex وارد شده است؛ مدل را انتخاب کنید." : "Codex is signed in; choose a model.") : (uiLanguage === "fa" ? "ابتدا در Codex وارد حساب شوید." : "Sign in to Codex first."));
    } catch (failure) { setCodexStatusText(failure instanceof Error ? failure.message : actions.t("error.generic")); }
    finally { setCodexBusy(false); }
  }

  async function connectCodex() {
    if (!codexModel) return;
    setCodexBusy(true);
    try {
      const provider = await request<ProviderProfile>("/api/integrations/codex/connect", { method: "POST", body: JSON.stringify({ model: codexModel }) });
      await actions.reload();
      await actions.selectProvider(provider.id);
      setCodexStatusText(uiLanguage === "fa" ? "Codex انتخاب شد؛ می‌توانید در گفت‌وگو از آن استفاده کنید." : "Codex selected; you can use it in chat.");
    } catch (failure) { setCodexStatusText(failure instanceof Error ? failure.message : actions.t("error.generic")); }
    finally { setCodexBusy(false); }
  }

  useEffect(() => {
    if (!formOpen) return;
    setError("");
    setModels([]);
    setShowKey(false);
    if (editing) {
      setForm({
        name: editing.name,
        baseUrl: editing.baseUrl,
        model: editing.model,
        apiKey: "",
        temperature: String(editing.temperature),
        maxTokens: String(editing.maxTokens),
        enabled: editing.enabled,
      });
    } else {
      setForm(emptyForm);
    }
  }, [editing, formOpen]);

  function openNew() {
    setEditing(null);
    setTestState(null);
    setFormOpen(true);
  }

  function openEdit(provider: ProviderProfile) {
    setEditing(provider);
    setTestState(null);
    setFormOpen(true);
  }

  async function save() {
    if (!form.name.trim()) return setError(actions.t("models.errorName"));
    if (!form.baseUrl.trim()) return setError(actions.t("models.errorUrl"));
    if (!form.model.trim()) return setError(actions.t("models.errorModel"));
    setBusy(true);
    setError("");
    try {
      const payload: Record<string, unknown> = {
        name: form.name.trim(),
        baseUrl: form.baseUrl.trim(),
        model: form.model.trim(),
        temperature: Number(form.temperature) || 0.7,
        maxTokens: Number(form.maxTokens) || 2048,
        enabled: form.enabled,
      };
      if (form.apiKey.trim()) payload.apiKey = form.apiKey.trim();
      await actions.saveProvider(payload, editing?.id);
      setFormOpen(false);
      setEditing(null);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : actions.t("error.generic"));
    } finally {
      setBusy(false);
    }
  }

  async function runTest(provider: ProviderProfile) {
    setTestState({ id: provider.id, ok: true, text: actions.t("models.testing") });
    try {
      const result = await actions.testProvider(provider.id);
      setTestState({
        id: provider.id,
        ok: true,
        text: actions.t("models.testOk", { ms: result.latencyMs }),
      });
    } catch (failure) {
      setTestState({
        id: provider.id,
        ok: false,
        text: failure instanceof Error ? failure.message : actions.t("models.testFailed"),
      });
    }
  }

  async function fetchModels() {
    setFetching(true);
    setError("");
    try {
      const rows = editing && !form.apiKey.trim() && form.baseUrl.trim() === editing.baseUrl
        ? await actions.fetchModels(editing.id)
        : (await api.discoverModels(form.baseUrl.trim(), form.apiKey)).models;
      setModels(rows);
      if (rows.length === 1) setForm((current) => ({ ...current, model: rows[0] }));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : actions.t("error.generic"));
    } finally {
      setFetching(false);
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setBusy(true);
    try {
      await actions.deleteProvider(deleteTarget.id);
      setDeleteTarget(null);
    } catch (failure) {
      actions.showToast(failure instanceof Error ? failure.message : actions.t("error.generic"), "error");
    } finally {
      setBusy(false);
    }
  }

  const tokens = totalTokens(usage?.promptTokens ?? 0, usage?.completionTokens ?? 0);

  return (
    <div className="page page--narrow">
      <PageHead
        eyebrow={actions.t("models.eyebrow")}
        title={actions.t("models.title")}
        subtitle={actions.t("models.subtitle")}
        actions={
          <Button variant="primary" onClick={openNew}>
            <Plus />
            {actions.t("models.add")}
          </Button>
        }
      />

      <section className="guide-card" aria-label="Codex">
        <div className="guide-card__head"><Cpu /><span>{uiLanguage === "fa" ? "اتصال Codex نصب‌شده" : "Connect installed Codex"}</span></div>
        <p>{uiLanguage === "fa" ? "از ورود موجود Codex استفاده کنید؛ رمز یا کلید در اومنی کپی نمی‌شود." : "Use your existing Codex sign-in without copying its credentials into Omni."}</p>
        <div className="studio-actions"><Button disabled={codexBusy} onClick={() => void checkCodex()}>{uiLanguage === "fa" ? "بررسی Codex" : "Check Codex"}</Button>
          {codexModels.length ? <><select className="select" aria-label={uiLanguage === "fa" ? "مدل Codex" : "Codex model"} value={codexModel} onChange={event => setCodexModel(event.target.value)}>{codexModels.map(model => <option key={model} value={model}>{model}</option>)}</select><Button disabled={codexBusy} onClick={() => void connectCodex()}>{uiLanguage === "fa" ? "اتصال و انتخاب" : "Connect and select"}</Button></> : null}</div>
        {codexStatusText ? <p role="status">{codexStatusText}</p> : null}
      </section>

      <div className="usage-strip">
        <span>
          {actions.t("models.requests")}: <strong>{formatNumber(usage?.requests ?? 0, uiLanguage)}</strong>
        </span>
        <span>
          {actions.t("models.promptTokens")}: <strong>{formatNumber(usage?.promptTokens ?? 0, uiLanguage)}</strong>
        </span>
        <span>
          {actions.t("models.completionTokens")}: <strong>{formatNumber(usage?.completionTokens ?? 0, uiLanguage)}</strong>
        </span>
        <span>
          {actions.t("workspace.statTokens")}: <strong>{formatNumber(tokens, uiLanguage)}</strong>
        </span>
      </div>

      {providers.length === 0 ? (
        <EmptyState
          icon={<Server />}
          title={actions.t("models.emptyTitle")}
          text={actions.t("models.emptyText")}
        />
      ) : (
        <div className="provider-list">
          {providers.map((provider) => {
            const active = settings?.activeProviderId === provider.id;
            const test = testState?.id === provider.id ? testState : null;
            return (
              <article key={provider.id} className={`provider-card${active ? " provider-card--active" : ""}`}>
                <div className="provider-card__mark">{providerInitial(provider.name)}</div>
                <div className="provider-card__main">
                  <div className="provider-card__name">
                    {provider.name}
                    {active ? <Tag tone="ember">{actions.t("models.active")}</Tag> : null}
                    {!provider.enabled ? <Tag>{actions.t("models.disabled")}</Tag> : null}
                    {provider.kind !== "codex" && !provider.hasKey && !/localhost|127\.0\.0\.1/.test(provider.baseUrl) ? (
                      <Tag tone="amber">
                        <KeyRound /> {actions.t("models.needsKey")}
                      </Tag>
                    ) : null}
                  </div>
                  <div className="provider-card__url">{provider.kind === "codex" ? (uiLanguage === "fa" ? "حساب محلی Codex" : "Local Codex sign-in") : provider.baseUrl}</div>
                  <div className="provider-card__meta">
                    <span className="ltr">
                      <Cpu /> {provider.model}
                    </span>
                    <span>
                      <Dot tone={provider.enabled ? "sage" : "amber"} />
                      {provider.enabled ? actions.t("models.ready") : actions.t("models.disabled")}
                    </span>
                    <span>
                      {actions.t("models.tempShort")} {provider.temperature}
                    </span>
                    <span>
                      {formatNumber(provider.maxTokens, uiLanguage)} {actions.t("models.tokensShort")}
                    </span>
                  </div>
                  {test ? (
                    <div className={`provider-test${test.ok ? " provider-test--ok" : " provider-test--error"}`}>
                      {test.ok ? <Check /> : <Zap />}
                      <span>{test.text}</span>
                    </div>
                  ) : null}
                </div>
                <div className="provider-card__actions">
                  {!active ? (
                    <Button variant="ghost" size="sm" onClick={() => void actions.selectProvider(provider.id)}>
                      {actions.t("models.use")}
                    </Button>
                  ) : null}
                  <IconButton
                    label={actions.t("models.test")}
                    size="sm"
                    onClick={() => void runTest(provider)}
                    disabled={test?.text === actions.t("models.testing")}
                  >
                    <Zap />
                  </IconButton>
                  {provider.kind !== "codex" ? <IconButton label={actions.t("models.edit")} size="sm" onClick={() => openEdit(provider)}>
                    <Pencil />
                  </IconButton> : null}
                  <IconButton label={actions.t("models.delete")} size="sm" danger onClick={() => setDeleteTarget(provider)}>
                    <Trash2 />
                  </IconButton>
                </div>
              </article>
            );
          })}
        </div>
      )}

      <div className="models-guide">
        <section className="guide-card">
          <div className="guide-card__head">
            <Server />
            <span>{actions.t("models.guideBaseTitle")}</span>
          </div>
          <p>{actions.t("models.guideBaseText")}</p>
          <div className="guide-card__endpoints">
            <span>
              <b>Ollama</b>
              <code dir="ltr">http://127.0.0.1:11434/v1</code>
            </span>
            <span>
              <b>LM Studio</b>
              <code dir="ltr">http://127.0.0.1:1234/v1</code>
            </span>
          </div>
        </section>
        <section className="guide-card">
          <div className="guide-card__head">
            <ShieldCheck />
            <span>{actions.t("models.guideKeyTitle")}</span>
          </div>
          <p>{actions.t("models.guideKeyText")}</p>
        </section>
      </div>

      <p className="models-footnote">{actions.t("models.costNote")}</p>

      <Dialog
        open={formOpen}
        onClose={() => setFormOpen(false)}
        wide
        title={editing ? actions.t("models.edit") : actions.t("models.add")}
        subtitle={actions.t("models.subtitle")}
        footer={
          <>
            <Button variant="ghost" onClick={() => setFormOpen(false)} disabled={busy}>
              {actions.t("common.cancel")}
            </Button>
            <Button variant="primary" onClick={() => void save()} disabled={busy}>
              {busy ? <RefreshCw className="spin" /> : null}
              {actions.t("models.save")}
            </Button>
          </>
        }
      >
        <div className="form">
          {error ? <div className="form__error">{error}</div> : null}
          {!editing ? <Field label={uiLanguage === "fa" ? "سرویس‌دهنده" : "Provider"} hint={uiLanguage === "fa" ? "سرویس را انتخاب کنید؛ کلید و فهرست مدل‌ها مربوط به همان حساب است." : "Select a service, then use its own API key and model list."} htmlFor="provider-preset">
            <select id="provider-preset" className="select" defaultValue="" onChange={(event) => {
              const preset = providerPresets.find((item) => item.name === event.target.value);
              if (preset) { setForm((current) => ({ ...current, name: preset.name, baseUrl: preset.baseUrl, model: "", apiKey: "" })); setModels([]); setError(""); }
            }}>
              <option value="">{uiLanguage === "fa" ? "انتخاب یا ورود دستی" : "Select or configure manually"}</option>
              {providerPresets.map((preset) => <option key={preset.name} value={preset.name}>{preset.name}</option>)}
            </select>
          </Field> : null}
          <Field label={actions.t("models.name")} hint={actions.t("models.nameHint")} htmlFor="provider-name">
            <Input
              id="provider-name"
              value={form.name}
              onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
              placeholder="Work gateway"
              autoFocus
            />
          </Field>
          <Field label={actions.t("models.baseUrl")} hint={actions.t("models.baseUrlHint")} htmlFor="provider-url">
            <Input
              id="provider-url"
              value={form.baseUrl}
              onChange={(event) => setForm((current) => ({ ...current, baseUrl: event.target.value }))}
              placeholder="https://api.openai.com/v1"
              className="ltr"
              inputMode="url"
            />
          </Field>
          <Field label={actions.t("models.apiKey")} hint={actions.t("models.apiKeyHint")} htmlFor="provider-key">
            <div className="secret-field">
              <Input
                id="provider-key"
                type={showKey ? "text" : "password"}
                value={form.apiKey}
                onChange={(event) => setForm((current) => ({ ...current, apiKey: event.target.value }))}
                placeholder={actions.t("models.apiKeyPlaceholder")}
                className="ltr"
                autoComplete="off"
                spellCheck={false}
              />
              <IconButton
                label={showKey ? "Hide key" : "Show key"}
                size="sm"
                onClick={() => setShowKey((value) => !value)}
              >
                {showKey ? <EyeOff /> : <Eye />}
              </IconButton>
            </div>
          </Field>
          <Field label={actions.t("models.model")} hint={actions.t("models.modelHint")} htmlFor="provider-model">
            <div className="model-fetch">
              <Input
                id="provider-model"
                value={form.model}
                onChange={(event) => setForm((current) => ({ ...current, model: event.target.value }))}
                placeholder="gpt-4.1-mini"
                className="ltr"
                list="provider-models"
              />
              <datalist id="provider-models">
                {models.map((model) => (
                  <option key={model} value={model} />
                ))}
              </datalist>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => void fetchModels()}
                disabled={!form.baseUrl.trim() || fetching}
              >
                {fetching ? <RefreshCw className="spin" /> : <RefreshCw />}
                {actions.t("models.fetchModels")}
              </Button>
            </div>
            {models.length ? <select className="select" aria-label={uiLanguage === "fa" ? "انتخاب مدل دریافت‌شده" : "Choose a discovered model"} value={models.includes(form.model) ? form.model : ""} onChange={(event) => setForm((current) => ({ ...current, model: event.target.value }))}>
              <option value="">{uiLanguage === "fa" ? `انتخاب مدل (${models.length})` : `Choose a model (${models.length})`}</option>
              {models.map((model) => <option key={model} value={model}>{model}</option>)}
            </select> : null}
            {isPromptGuard(form.model) ? <p className="studio-error" role="status">{actions.t("chat.classifierText")}</p> : null}
          </Field>
          <div className="form__row">
            <Field label={actions.t("models.temperature")} htmlFor="provider-temp">
              <Input
                id="provider-temp"
                type="number"
                min={0}
                max={2}
                step={0.1}
                value={form.temperature}
                onChange={(event) => setForm((current) => ({ ...current, temperature: event.target.value }))}
              />
            </Field>
            <Field label={actions.t("models.maxTokens")} htmlFor="provider-max">
              <Input
                id="provider-max"
                type="number"
                min={64}
                max={32000}
                step={64}
                value={form.maxTokens}
                onChange={(event) => setForm((current) => ({ ...current, maxTokens: event.target.value }))}
              />
            </Field>
          </div>
          <div className="form__switch">
            <div>
              <div className="group__title">{actions.t("models.enabled")}</div>
              <div className="group__subtitle">{actions.t("models.enabled") === "Enabled" ? "Use this provider when it is selected." : "وقتی انتخاب شده باشد، از این سرویس‌دهنده استفاده شود."}</div>
            </div>
            <Switch
              checked={form.enabled}
              onChange={(checked) => setForm((current) => ({ ...current, enabled: checked }))}
              label={actions.t("models.enabled")}
            />
          </div>
          <div className="form__note">
            <KeyRound />
            <span>{actions.t("models.localKeyNote")}</span>
          </div>
          {editing ? (
            <div className="form__test">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => void runTest(editing)}
                disabled={testState?.text === actions.t("models.testing")}
              >
                <Zap />
                {actions.t("models.test")}
              </Button>
              {testState && testState.id === editing.id ? (
                <span className={testState.ok ? "text-sage" : "text-rose"}>{testState.text}</span>
              ) : null}
            </div>
          ) : null}
        </div>
      </Dialog>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => void confirmDelete()}
        title={actions.t("models.deleteTitle")}
        body={actions.t("models.deleteBody")}
        confirmLabel={actions.t("common.delete")}
        cancelLabel={actions.t("common.cancel")}
        danger
        busy={busy}
      />
    </div>
  );
}
