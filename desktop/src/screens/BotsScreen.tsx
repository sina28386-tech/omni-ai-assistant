import { useEffect, useState } from "react";
import { Bot as BotIcon, Plus, Settings2, Trash2 } from "lucide-react";
import { useApp } from "../state/AppStore";
import { studioApi, type Bot, type BotInput, type Workspace } from "../lib/studioApi";
import { studioText } from "../lib/studioCopy";
import { Button, ConfirmDialog, Dialog, Field, Input, Spinner } from "../components/ui";
import { BotTaskPanel } from "../components/BotTaskPanel";

export function BotsScreen() {
  const { uiLanguage, providers, settings } = useApp();
  const c = studioText(uiLanguage);
  const [bots, setBots] = useState<Bot[]>([]);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Bot | null>(null);
  const [form, setForm] = useState<BotInput>({ name: "", instructions: "", providerId: "", workspaceId: null });
  const [formError, setFormError] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [busy, setBusy] = useState(false);
  const selected = bots.find((item) => item.id === selectedId) ?? bots[0];

  useEffect(() => {
    let disposed = false;
    void Promise.all([studioApi.listBots(), studioApi.listWorkspaces()]).then(([botRows, projectRows]) => {
      if (!disposed) { setBots(botRows); setWorkspaces(projectRows); }
    }).catch((failure) => { if (!disposed) setError(failure instanceof Error ? failure.message : c.error); })
      .finally(() => { if (!disposed) setLoading(false); });
    return () => { disposed = true; };
  }, [c.error]);

  function openForm(bot: Bot | null) {
    setEditing(bot); setFormError("");
    setForm(bot ? { name: bot.name, instructions: bot.instructions, providerId: bot.providerId, workspaceId: bot.workspaceId } : { name: "", instructions: "", providerId: settings?.activeProviderId || providers[0]?.id || "", workspaceId: null });
    setFormOpen(true);
  }

  async function save() {
    if (!form.name.trim() || !form.instructions.trim() || !form.providerId) return;
    setBusy(true); setFormError("");
    try {
      const record = editing ? await studioApi.updateBot(editing.id, form) : await studioApi.createBot(form);
      setBots((current) => [...current.filter((item) => item.id !== record.id), record]); setSelectedId(record.id); setFormOpen(false);
    } catch (failure) { setFormError(failure instanceof Error ? failure.message : c.error); }
    finally { setBusy(false); }
  }

  async function remove() {
    if (!selected) return;
    setBusy(true);
    try { await studioApi.deleteBot(selected.id); setBots((current) => current.filter((item) => item.id !== selected.id)); setSelectedId(null); setDeleting(false); }
    catch (failure) { setError(failure instanceof Error ? failure.message : c.error); setDeleting(false); }
    finally { setBusy(false); }
  }

  return <div className="studio-page">
    <div className="studio-page__head"><h1>{c.bots}</h1><p>{c.createHint}</p></div>
    {error ? <p className="studio-error" role="alert">{error}</p> : null}
    <div className="bots-layout">
      <aside className="coworkers" aria-label={c.bots}>
        <Button block onClick={() => openForm(null)}><Plus />{c.newBot}</Button>
        {loading ? <Spinner label={c.loading} /> : null}
        {!loading && !bots.length ? <p className="studio-empty">{c.noBots}</p> : null}
        <div className="coworkers__list">{bots.map((bot) => <button type="button" className="coworker" key={bot.id} aria-pressed={selected?.id === bot.id} onClick={() => setSelectedId(bot.id)}>
          <span className="coworker__mark"><BotIcon /></span><span><strong>{bot.name}</strong><small>{bot.instructions}</small></span>
        </button>)}</div>
        <p className="studio-note">{c.localNote}</p>
      </aside>
      <section className="coworker-main">
        {selected ? <><header className="coworker-main__head"><div><h2>{selected.name}</h2><p>{selected.instructions}</p><small>{workspaces.find((item) => item.id === selected.workspaceId)?.name ?? c.noProject}</small></div><div className="studio-actions"><Button size="sm" onClick={() => openForm(selected)}><Settings2 />{c.editBot}</Button><Button size="sm" variant="ghost" aria-label={c.delete} title={c.delete} onClick={() => setDeleting(true)}><Trash2 /></Button></div></header><BotTaskPanel key={selected.id} bot={selected} /></> : <p className="studio-empty">{c.chooseBot}</p>}
      </section>
    </div>
    <Dialog open={formOpen} onClose={() => { if (!busy) setFormOpen(false); }} title={editing ? c.editBot : c.newBot} footer={<><Button onClick={() => setFormOpen(false)} disabled={busy}>{c.cancel}</Button><Button variant="primary" disabled={busy || !form.name.trim() || !form.instructions.trim() || !form.providerId} onClick={() => void save()}>{c.save}</Button></>}>
      <form className="studio-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
        <Field label={c.name} htmlFor="bot-name"><Input id="bot-name" maxLength={80} value={form.name} onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))} autoFocus /></Field>
        <Field label={c.instructions} htmlFor="bot-instructions"><textarea id="bot-instructions" className="textarea" rows={5} maxLength={12000} value={form.instructions} onChange={(event) => setForm((current) => ({ ...current, instructions: event.target.value }))} /></Field>
        <Field label={c.model} htmlFor="bot-model"><select id="bot-model" className="select" value={form.providerId} onChange={(event) => setForm((current) => ({ ...current, providerId: event.target.value }))}><option value="">—</option>{providers.map((provider) => <option key={provider.id} value={provider.id} disabled={!provider.enabled}>{provider.name} · {provider.model}</option>)}</select></Field>
        <Field label={c.project} htmlFor="bot-workspace"><select id="bot-workspace" className="select" value={form.workspaceId ?? ""} onChange={(event) => setForm((current) => ({ ...current, workspaceId: event.target.value || null }))}><option value="">{c.noProject}</option>{workspaces.map((workspace) => <option key={workspace.id} value={workspace.id}>{workspace.name} · {workspace.rootPath}</option>)}</select></Field>
        {formError ? <p className="studio-error" role="alert">{formError}</p> : null}
      </form>
    </Dialog>
    <ConfirmDialog open={deleting} onClose={() => setDeleting(false)} onConfirm={() => void remove()} title={c.deleteTitle} body={c.deleteBody} confirmLabel={c.delete} cancelLabel={c.cancel} danger busy={busy} />
  </div>;
}
