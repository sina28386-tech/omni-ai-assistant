import { useEffect, useState } from "react";
import { ArrowUp, Square } from "lucide-react";
import { useApp } from "../state/AppStore";
import { studioApi, type Bot, type BotRun } from "../lib/studioApi";
import { runLabel, studioText } from "../lib/studioCopy";
import { Button, Spinner } from "./ui";

export function ChangeReview({ path, before, after }: { path: string; before: string; after: string }) {
  const { uiLanguage } = useApp();
  const c = studioText(uiLanguage);
  return <div className="change-review">
    <code className="change-review__path" dir="ltr">{path}</code>
    <div className="change-review__columns">
      <div><h3>{c.before}</h3><pre dir="ltr">{before || c.newFile}</pre></div>
      <div><h3>{c.after}</h3><pre dir="ltr">{after}</pre></div>
    </div>
  </div>;
}

export function BotTaskPanel({ bot, onFileChanged }: { bot: Bot; onFileChanged?: () => void }) {
  const { uiLanguage, providers } = useApp();
  const c = studioText(uiLanguage);
  const [runs, setRuns] = useState<BotRun[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const selected = runs.find((run) => run.id === selectedId) ?? runs[0];
  const active = runs.some((run) => ["queued", "running", "waiting_approval"].includes(run.state));
  const provider = providers.find((item) => item.id === bot.providerId);
  const draft = drafts[bot.id] ?? "";

  useEffect(() => {
    let disposed = false;
    let inFlight = false;
    setLoading(true);
    setRuns([]);
    setSelectedId(null);
    setError("");
    const refresh = async () => {
      if (inFlight) return;
      inFlight = true;
      try {
        const rows = await studioApi.listRuns(bot.id);
        if (!disposed) { setRuns(rows.sort((a, b) => b.createdAt - a.createdAt)); setError(""); }
      } catch (failure) { if (!disposed) setError(failure instanceof Error ? failure.message : c.error); }
      finally { inFlight = false; if (!disposed) setLoading(false); }
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 1500);
    return () => { disposed = true; window.clearInterval(timer); };
  }, [bot.id, c.error]);

  function replaceRun(record: BotRun) {
    setRuns((current) => [record, ...current.filter((item) => item.id !== record.id)].sort((a, b) => b.createdAt - a.createdAt));
  }

  async function start() {
    if (!draft.trim() || active || busy || !provider?.enabled) return;
    setBusy(true); setError("");
    try {
      const record = await studioApi.startRun(bot.id, draft.trim());
      replaceRun(record); setSelectedId(record.id); setDrafts((current) => ({ ...current, [bot.id]: "" }));
    } catch (failure) { setError(failure instanceof Error ? failure.message : c.error); }
    finally { setBusy(false); }
  }

  async function act(operation: () => Promise<BotRun>, fileChanged = false) {
    setBusy(true); setError("");
    try { replaceRun(await operation()); if (fileChanged) onFileChanged?.(); }
    catch (failure) { setError(failure instanceof Error ? failure.message : c.error); }
    finally { setBusy(false); }
  }

  return <div className="bot-tasks">
    <div className="bot-tasks__scroll">
      {loading ? <Spinner label={c.loading} /> : null}
      {!loading && !runs.length ? <p className="studio-empty">{c.noRuns}</p> : null}
      {runs.length ? <div className="run-history" aria-label={c.history}>
        {runs.map((run) => <button type="button" key={run.id} aria-pressed={selected?.id === run.id} onClick={() => setSelectedId(run.id)}>
          <span>{run.task}</span><small data-state={run.state}>{runLabel(uiLanguage, run.state)}</small>
        </button>)}
      </div> : null}
      {selected ? <article className="run-detail" aria-label={c.taskDetails} aria-busy={["queued", "running"].includes(selected.state)}>
        <div className="run-detail__heading"><h2>{c.output}</h2><span className="run-state" data-state={selected.state}>{runLabel(uiLanguage, selected.state)}</span>
          {["queued", "running", "waiting_approval"].includes(selected.state) ? <Button size="sm" disabled={busy} onClick={() => void act(() => studioApi.cancelRun(selected.id))}><Square />{c.stop}</Button> : null}
        </div>
        {selected.steps.length ? <ol className="run-steps">{selected.steps.map((step) => <li key={step.id}><span aria-hidden="true">·</span><span dir="auto">{step.summary}{step.result ? <details className="command-result"><summary>{uiLanguage === "fa" ? "خروجی و کد پایان" : "Output and exit code"}</summary><p>{uiLanguage === "fa" ? "کد پایان" : "Exit code"}: {step.result.exitCode ?? "—"}{step.result.timedOut ? (uiLanguage === "fa" ? " · مهلت تمام شد" : " · timed out") : ""}{step.result.truncated ? (uiLanguage === "fa" ? " · خروجی کوتاه شده" : " · output truncated") : ""}</p><pre dir="auto">{step.result.stdout || step.result.stderr || (uiLanguage === "fa" ? "بدون خروجی" : "No output")}</pre>{step.result.stdout && step.result.stderr ? <pre dir="auto">{step.result.stderr}</pre> : null}</details> : null}</span><small>{runLabel(uiLanguage, step.state)}</small></li>)}</ol> : null}
        {selected.output ? <pre className="run-output" dir="auto">{selected.output}</pre> : null}
        {selected.error ? <p className="studio-error" role="alert">{selected.error}</p> : null}
        {selected.state === "waiting_approval" && selected.pendingChange ? <section className="pending-change">
          <h2>{c.review}</h2>
          <ChangeReview path={selected.pendingChange.path} before={selected.pendingChange.before} after={selected.pendingChange.after} />
          <div className="studio-actions"><Button variant="primary" disabled={busy} onClick={() => void act(() => studioApi.approveChange(selected.id, selected.pendingChange!.id, true), true)}>{c.approve}</Button><Button disabled={busy} onClick={() => void act(() => studioApi.approveChange(selected.id, selected.pendingChange!.id, false))}>{c.deny}</Button></div>
        </section> : null}
        {selected.state === "waiting_approval" && selected.pendingCommand ? <section className="pending-change" aria-label={uiLanguage === "fa" ? "تأیید دستور ویندوز" : "Approve Windows command"}>
          <h2>{uiLanguage === "fa" ? "این دستور را بررسی کنید" : "Review this command"}</h2>
          <p className="studio-note">{uiLanguage === "fa" ? "این دستور با دسترسی حساب ویندوز شما اجرا می‌شود و محدود به پوشهٔ پروژه نیست. خروجی آن در سابقهٔ محلی ذخیره و برای مدل انتخاب‌شده ارسال می‌شود." : "This command runs with your Windows account's permissions and is not confined to the project. Its output is stored in local task history and sent to the selected model."}</p>
          <code dir="ltr">{selected.pendingCommand.shell} · {selected.pendingCommand.cwd}</code>
          <pre className="command-review" dir="ltr">{selected.pendingCommand.command}</pre>
          <div className="studio-actions"><Button variant="primary" disabled={busy} onClick={() => void act(() => studioApi.approveChange(selected.id, selected.pendingCommand!.id, true))}>{uiLanguage === "fa" ? "تأیید و اجرای دستور" : "Approve and run"}</Button><Button disabled={busy} onClick={() => void act(() => studioApi.approveChange(selected.id, selected.pendingCommand!.id, false))}>{uiLanguage === "fa" ? "رد دستور" : "Reject command"}</Button></div>
        </section> : null}
        {selected.state === "waiting_approval" && selected.pendingCodexAction ? <section className="pending-change" aria-label={uiLanguage === "fa" ? "تأیید Codex" : "Codex approval"}>
          <h2>{uiLanguage === "fa" ? "درخواست تأیید Codex" : "Codex approval request"}</h2>
          <pre className="command-review" dir="auto">{selected.pendingCodexAction.description}</pre>
          <div className="studio-actions"><Button variant="primary" disabled={busy} onClick={() => void act(() => studioApi.approveChange(selected.id, selected.pendingCodexAction!.id, true))}>{c.approve}</Button><Button disabled={busy} onClick={() => void act(() => studioApi.approveChange(selected.id, selected.pendingCodexAction!.id, false))}>{c.deny}</Button></div>
        </section> : null}
      </article> : null}
      {error ? <p className="studio-error" role="alert">{error}</p> : null}
    </div>
    <form className="bot-task-composer" onSubmit={(event) => { event.preventDefault(); void start(); }}>
      <textarea value={draft} onChange={(event) => setDrafts((current) => ({ ...current, [bot.id]: event.target.value }))} placeholder={c.task} aria-label={c.task} rows={3} />
      <div><span>{provider ? `${provider.name} · ${provider.model}` : c.unavailableModel}</span><button className="composer__send" type="submit" aria-label={c.run} title={c.run} disabled={busy || active || !draft.trim() || !provider?.enabled}><ArrowUp /></button></div>
    </form>
    <p className="studio-note">{c.localNote}</p>
  </div>;
}
