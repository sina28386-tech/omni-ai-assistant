import { useEffect, useState, type KeyboardEvent } from "react";
import { ArrowUp, Bot as BotIcon, FileText, Folder, FolderPlus, RefreshCw, Save } from "lucide-react";
import { useApp } from "../state/AppStore";
import { studioApi, type Bot, type Workspace, type WorkspaceEntry, type WorkspaceFile } from "../lib/studioApi";
import { studioText } from "../lib/studioCopy";
import { Button, ConfirmDialog, Dialog, Field, Input, Spinner } from "../components/ui";
import { BotTaskPanel, ChangeReview } from "../components/BotTaskPanel";

export function CodeScreen() {
  const { uiLanguage, actions } = useApp();
  const c = studioText(uiLanguage);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [workspaceId, setWorkspaceId] = useState("");
  const [bots, setBots] = useState<Bot[]>([]);
  const [botId, setBotId] = useState("");
  const [directory, setDirectory] = useState("");
  const [entries, setEntries] = useState<WorkspaceEntry[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [file, setFile] = useState<WorkspaceFile | null>(null);
  const [content, setContent] = useState("");
  const [review, setReview] = useState<{ file: WorkspaceFile; after: string } | null>(null);
  const [pendingNavigation, setPendingNavigation] = useState<(() => void) | null>(null);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [folderPath, setFolderPath] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [showBot, setShowBot] = useState(true);
  const projectBots = bots.filter((bot) => bot.workspaceId === workspaceId);
  const bot = projectBots.find((item) => item.id === botId) ?? projectBots[0];
  const dirty = Boolean(file && content !== file.content);

  useEffect(() => {
    let disposed = false;
    void Promise.all([studioApi.listWorkspaces(), studioApi.listBots()]).then(([projects, coworkers]) => {
      if (!disposed) { setWorkspaces(projects); setWorkspaceId(projects[0]?.id ?? ""); setBots(coworkers); }
    }).catch((failure) => { if (!disposed) setError(failure instanceof Error ? failure.message : c.error); }).finally(() => { if (!disposed) setLoading(false); });
    return () => { disposed = true; };
  }, []);

  useEffect(() => {
    if (!workspaceId) return;
    let disposed = false;
    setBusy(true); setEntries([]); setFile(null); setContent(""); setError("");
    void studioApi.listDirectory(workspaceId).then((listing) => { if (!disposed) { setEntries(listing.entries); setTruncated(listing.truncated); setDirectory(""); } })
      .catch((failure) => { if (!disposed) setError(failure instanceof Error ? failure.message : c.error); }).finally(() => { if (!disposed) setBusy(false); });
    return () => { disposed = true; };
  }, [workspaceId]);

  useEffect(() => {
    actions.setNavigationGuard(dirty ? (proceed) => setPendingNavigation(() => proceed) : null);
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    if (dirty) window.addEventListener("beforeunload", beforeUnload);
    return () => { actions.setNavigationGuard(null); window.removeEventListener("beforeunload", beforeUnload); };
  }, [dirty, actions]);

  function navigate(operation: () => void) {
    if (dirty) setPendingNavigation(() => operation);
    else operation();
  }

  async function openDirectory(path: string) {
    setBusy(true); setError("");
    try { const listing = await studioApi.listDirectory(workspaceId, path); setEntries(listing.entries); setTruncated(listing.truncated); setDirectory(path); }
    catch (failure) { setError(failure instanceof Error ? failure.message : c.error); }
    finally { setBusy(false); }
  }

  async function openFile(path: string) {
    setBusy(true); setError("");
    try { const result = await studioApi.readFile(workspaceId, path); setFile(result); setContent(result.content); }
    catch (failure) { setError(failure instanceof Error ? failure.message : c.error); }
    finally { setBusy(false); }
  }

  async function register() {
    if (!folderPath.trim()) return;
    setBusy(true); setError("");
    try {
      const project = await studioApi.registerWorkspace(folderPath.trim());
      setWorkspaces((current) => [...current.filter((item) => item.id !== project.id), project]); setRegisterOpen(false); setFolderPath("");
      navigate(() => setWorkspaceId(project.id));
    } catch (failure) { setError(failure instanceof Error ? failure.message : c.error); }
    finally { setBusy(false); }
  }

  async function save() {
    if (!review) return;
    setBusy(true); setError("");
    try {
      const result = await studioApi.saveFile(workspaceId, { path: review.file.path, content: review.after, expectedSha: review.file.sha256, approved: true });
      setFile(result); setContent(result.content); setReview(null); actions.showToast(c.saved);
    } catch (failure) { setError(failure instanceof Error ? failure.message : c.error); setReview(null); }
    finally { setBusy(false); }
  }

  function onEditorKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
      event.preventDefault();
      if (dirty && !busy && file) setReview({ file, after: content });
    }
  }

  return <div className="studio-page code-page">
    <div className="code-page__head"><div className="studio-page__head"><h1>{c.code}</h1><p>{c.projectNote}</p></div><div className="studio-actions">
      <select className="select" aria-label={c.project} value={workspaceId} disabled={busy} onChange={(event) => { const id = event.target.value; navigate(() => setWorkspaceId(id)); }}><option value="">—</option>{workspaces.map((workspace) => <option key={workspace.id} value={workspace.id}>{workspace.name}</option>)}</select>
      <Button onClick={() => setRegisterOpen(true)}><FolderPlus />{c.registerProject}</Button>
      {workspaceId ? <Button aria-expanded={showBot} aria-controls="project-bot-panel" onClick={() => setShowBot((value) => !value)}><BotIcon />{showBot ? c.hideBot : c.showBot}</Button> : null}
    </div></div>
    {error && !registerOpen ? <p role="alert" className="studio-error">{error}</p> : null}
    {loading ? <Spinner label={c.loading} /> : null}
    {!loading && !workspaceId ? <div className="studio-empty">{c.noProjects}</div> : null}
    {workspaceId ? <><div className={`coding-layout${showBot ? "" : " coding-layout--bot-hidden"}`}>
      <aside className="file-browser" aria-label={c.files}>
        <div className="file-browser__heading"><h2>{c.files}</h2><Button size="sm" variant="ghost" aria-label={c.refresh} title={c.refresh} disabled={busy} onClick={() => void openDirectory(directory)}><RefreshCw /></Button></div>
        <code className="file-browser__path" dir="ltr">{directory || workspaces.find((workspace) => workspace.id === workspaceId)?.name}</code>
        {directory ? <Button size="sm" variant="ghost" disabled={busy} onClick={() => void openDirectory(directory.split("/").slice(0, -1).join("/"))}><ArrowUp />{c.parent}</Button> : null}
        {entries.map((entry) => <button className="file-entry" type="button" key={entry.path} aria-current={file?.path === entry.path ? "true" : undefined} disabled={busy} onClick={() => entry.kind === "directory" ? void openDirectory(entry.path) : navigate(() => void openFile(entry.path))}>{entry.kind === "directory" ? <Folder /> : <FileText />}<span dir="ltr">{entry.name}</span></button>)}
        {!entries.length && !busy ? <p className="studio-empty">{c.emptyFolder}</p> : null}
        {truncated ? <p className="studio-note">{c.truncated}</p> : null}
      </aside>
      <section className="file-editor">
        <header><code dir="ltr">{file?.path ?? c.editor}</code><div className="studio-actions">{dirty ? <span className="studio-note">{c.unsaved}</span> : null}<Button size="sm" disabled={!file || busy} onClick={() => file && navigate(() => void openFile(file.path))}><RefreshCw />{c.reloadFile}</Button><Button size="sm" variant="primary" disabled={!dirty || busy || !file} onClick={() => file && setReview({ file, after: content })}><Save />{c.saveFile}</Button></div></header>
        {file ? <textarea className="code-editor" aria-label={`${c.editor}: ${file.path}`} dir="ltr" value={content} onChange={(event) => setContent(event.target.value)} onKeyDown={onEditorKeyDown} spellCheck={false} readOnly={busy} /> : <p className="studio-empty">{c.selectFile}</p>}
      </section>
    <section id="project-bot-panel" className="coding-bot" hidden={!showBot}><div className="coding-bot__head"><div><h2>{c.codingBot}</h2><p>{c.codingHint}</p></div><select className="select" aria-label={c.codingBot} value={bot?.id ?? ""} onChange={(event) => setBotId(event.target.value)}><option value="">—</option>{projectBots.map((coworker) => <option key={coworker.id} value={coworker.id}>{coworker.name}</option>)}</select></div>
      {bot ? <BotTaskPanel key={bot.id} bot={bot} onFileChanged={() => { if (file && !dirty) void openFile(file.path); void openDirectory(directory); }} /> : <div className="studio-empty"><p>{c.noProjectBots}</p><Button size="sm" onClick={() => navigate(() => actions.setScreen("agents"))}>{c.newBot}</Button></div>}
    </section></div></> : null}
    <Dialog open={registerOpen} onClose={() => { if (!busy) setRegisterOpen(false); }} title={c.registerProject} subtitle={c.pathHint} footer={<><Button disabled={busy} onClick={() => setRegisterOpen(false)}>{c.cancel}</Button><Button variant="primary" disabled={busy || !folderPath.trim()} onClick={() => void register()}>{c.add}</Button></>}>
      <Field label={c.folderPath} htmlFor="project-path"><Input id="project-path" className="ltr" dir="ltr" value={folderPath} onChange={(event) => setFolderPath(event.target.value)} placeholder="E:\Projects\my-project" onKeyDown={(event) => { if (event.key === "Enter") void register(); }} autoFocus /></Field>
      {error && registerOpen ? <p className="studio-error" role="alert">{error}</p> : null}
    </Dialog>
    <Dialog open={Boolean(review)} onClose={() => { if (!busy) setReview(null); }} title={c.reviewFile} wide footer={<><Button disabled={busy} onClick={() => setReview(null)}>{c.cancel}</Button><Button variant="primary" disabled={busy} onClick={() => void save()}>{c.approve}</Button></>}>{review ? <ChangeReview path={review.file.path} before={review.file.content} after={review.after} /> : null}</Dialog>
    <ConfirmDialog open={Boolean(pendingNavigation)} onClose={() => setPendingNavigation(null)} onConfirm={() => { const operation = pendingNavigation; setPendingNavigation(null); operation?.(); }} title={c.discardTitle} body={c.discardBody} confirmLabel={c.discard} cancelLabel={c.cancel} danger />
  </div>;
}
