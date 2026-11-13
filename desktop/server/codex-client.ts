import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import readline from "node:readline";
import { HttpError } from "./errors.js";
import { UiStalledError } from "./ui-progress.js";

type Message = { id?: number | string; method?: string; params?: Record<string, unknown>; result?: unknown; error?: { message?: string } };
type OnServerRequest = (method: string, params: Record<string, unknown>) => Promise<Record<string, unknown>>;

export async function findCodexExecutable(): Promise<string> {
  const roots = [process.env.CODEX_HOME ? path.dirname(process.env.CODEX_HOME) : "", process.env.APPDATA ? path.join(process.env.APPDATA, "npm") : ""].filter(Boolean);
  const names = process.platform === "win32" ? ["x86_64-pc-windows-msvc/bin/codex.exe"] : ["x86_64-unknown-linux-musl/bin/codex"];
  const candidates = roots.flatMap(root => names.map(name => path.join(root, "node_modules", "@openai", process.platform === "win32" ? "codex-win32-x64" : "codex-linux-x64", "vendor", name)));
  if (process.platform === "win32") {
    const extensions = path.join(os.homedir(), ".vscode", "extensions");
    const dirs = await fs.readdir(extensions).catch(() => [] as string[]);
    for (const dir of dirs.filter(name => name.startsWith("openai.chatgpt-")).sort().reverse()) candidates.push(path.join(extensions, dir, "bin", "windows-x86_64", "codex.exe"));
  }
  for (const folder of (process.env.PATH ?? "").split(path.delimiter)) if (folder) candidates.push(path.join(folder, process.platform === "win32" ? "codex.exe" : "codex"));
  for (const candidate of candidates) if (await fs.stat(candidate).then(s => s.isFile()).catch(() => false)) return candidate;
  throw new HttpError(400, "codex_missing", "Codex was not found. Install Codex and sign in first.");
}

class CodexClient {
  private child: ChildProcessWithoutNullStreams;
  private nextId = 0;
  private pending = new Map<number | string, { resolve: (value: unknown) => void; reject: (reason: Error) => void }>();
  private requestQueue: Promise<void> = Promise.resolve();
  onEvent: (event: Message) => void = () => undefined;
  onRequest: OnServerRequest = async () => ({ decision: "decline" });
  onExit: () => void = () => undefined;
  constructor(executable: string, signal?: AbortSignal, args = ["app-server", "--listen", "stdio://"]) {
    this.child = spawn(executable, args, { stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
    this.child.stderr.resume();
    readline.createInterface({ input: this.child.stdout }).on("line", line => {
      let msg: Message; try { msg = JSON.parse(line) as Message; } catch { return; }
      if (msg.method && msg.id !== undefined) {
        const method = msg.method; const id = msg.id;
        this.requestQueue = this.requestQueue.catch(() => undefined).then(async () => {
          try { this.write({ id, result: await this.onRequest(method, msg.params ?? {}) }); }
          catch { this.write({ id, result: method === "item/tool/call" ? { success: false, contentItems: [{ type: "inputText", text: "Tool call failed or was canceled." }] } : { decision: "decline" } }); }
        });
      } else if (msg.id !== undefined) {
        const pending = this.pending.get(msg.id); if (!pending) return;
        this.pending.delete(msg.id);
        if (msg.error) pending.reject(new Error(msg.error.message?.slice(0, 500) || "Codex request failed.")); else pending.resolve(msg.result);
      } else this.onEvent(msg);
    });
    const fail = () => { for (const promise of this.pending.values()) promise.reject(new Error("Codex stopped.")); this.pending.clear(); this.onExit(); };
    this.child.on("error", fail); this.child.on("exit", fail);
    signal?.addEventListener("abort", () => this.close(), { once: true });
  }
  private write(message: unknown) { if (!this.child.stdin.destroyed) this.child.stdin.write(`${JSON.stringify(message)}\n`); }
  request(method: string, params: object): Promise<unknown> {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => { this.pending.set(id, { resolve, reject }); this.write({ id, method, params }); });
  }
  async initialize(experimental = false) {
    await this.request("initialize", { clientInfo: { name: "omni_assistant", title: "Omni Assistant", version: "0.1.0" }, ...(experimental ? { capabilities: { experimentalApi: true } } : {}) });
    this.write({ method: "initialized", params: {} });
  }
  close() { if (!this.child.killed) this.child.kill(); }
}

export async function codexStatus(): Promise<{ authenticated: boolean; authType: string | null; models: string[] }> {
  const client = new CodexClient(await findCodexExecutable());
  const timer = setTimeout(() => client.close(), 20_000);
  try {
    await client.initialize();
    const account = await client.request("account/read", { refreshToken: false }) as { account?: { type?: string } | null };
    const catalog = await client.request("model/list", { limit: 100 }) as { data?: Array<{ model?: string; id?: string }> };
    return { authenticated: Boolean(account.account), authType: account.account?.type ?? null, models: [...new Set((catalog.data ?? []).map(row => row.model || row.id || "").filter(Boolean))] };
  } finally { clearTimeout(timer); client.close(); }
}

export async function codexTurn(model: string, text: string, signal: AbortSignal, onApproval: OnServerRequest, onProgress: (text: string) => void, fixture?: { executable: string; args: string[] }, dynamicTools?: unknown[]): Promise<string> {
  signal.throwIfAborted();
  const client = new CodexClient(fixture?.executable ?? await findCodexExecutable(), signal, fixture?.args);
  let resolveTurn!: (value: string) => void; let rejectTurn!: (error: Error) => void;
  let answer = ""; let settled = false;
  const proposedChanges = new Map<string, unknown>();
  const finished = new Promise<string>((resolve, reject) => { resolveTurn = resolve; rejectTurn = reject; });
  void finished.catch(() => undefined);
  client.onExit = () => { if (!settled) rejectTurn(new Error("Codex stopped before completing the task.")); };
  client.onRequest = async (method, params) => {
    try { return await onApproval(method, method === "item/fileChange/requestApproval" && typeof params.itemId === "string" ? { ...params, changes: proposedChanges.get(params.itemId) } : params); }
    catch (error) {
      if (error instanceof UiStalledError && !settled) { settled = true; rejectTurn(error); client.close(); }
      throw error;
    }
  };
  client.onEvent = event => {
    const params = event.params ?? {};
    if (event.method === "item/started") {
      const item = params.item as { type?: string; id?: string; changes?: unknown } | undefined;
      if (item?.type === "fileChange" && item.id && item.changes) proposedChanges.set(item.id, item.changes);
    }
    if (event.method === "item/agentMessage/delta" && typeof params.delta === "string") { answer += params.delta; onProgress(answer.slice(-120_000)); }
    if (event.method === "turn/completed") {
      const turn = params.turn as { status?: string; error?: { message?: string } } | undefined;
      settled = true;
      if (turn?.status === "completed" && answer.trim()) resolveTurn(answer);
      else rejectTurn(new Error(turn?.error?.message || `Codex turn ${turn?.status || "failed"}.`));
    }
  };
  const abort = () => { if (!settled) rejectTurn(new Error("Codex task canceled.")); };
  signal.addEventListener("abort", abort, { once: true });
  const timer = setTimeout(() => { if (!settled) { client.close(); rejectTurn(new Error("Codex timed out.")); } }, 180_000);
  try {
    await client.initialize(Boolean(dynamicTools?.length));
    const started = await client.request("thread/start", { model, cwd: os.homedir(), approvalPolicy: "on-request", sandbox: "read-only", ephemeral: true, ...(dynamicTools?.length ? { dynamicTools } : {}) }) as { thread?: { id?: string } };
    const threadId = started.thread?.id; if (!threadId) throw new Error("Codex returned no thread.");
    await client.request("turn/start", { threadId, input: [{ type: "text", text }] });
    return await finished;
  } finally { clearTimeout(timer); signal.removeEventListener("abort", abort); client.close(); }
}
