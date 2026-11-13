import { request } from "./api";

export interface Workspace {
  id: string;
  name: string;
  rootPath: string;
  createdAt: number;
}

export interface WorkspaceEntry {
  name: string;
  path: string;
  kind: "directory" | "file";
}

export interface WorkspaceFile {
  path: string;
  content: string;
  sha256: string;
}

export interface Bot {
  id: string;
  name: string;
  instructions: string;
  providerId: string;
  workspaceId: string | null;
  allowCommands?: boolean;
  conversationId: string;
  createdAt: number;
  updatedAt: number;
}

export type RunState = "queued" | "running" | "waiting_approval" | "complete" | "failed" | "canceled";
export interface BotRun {
  id: string;
  botId: string;
  task: string;
  state: RunState;
  steps: Array<{ id: string; tool: string; state: "running" | "complete" | "failed" | "denied"; summary: string; result?: { ok: boolean; exitCode: number | null; stdout: string; stderr: string; truncated: boolean; timedOut: boolean } }>;
  output: string;
  error?: string;
  pendingChange?: { id: string; path: string; before: string; after: string; expectedSha: string | null };
  pendingCommand?: { id: string; shell: "powershell" | "cmd"; command: string; cwd: string };
  pendingUiAction?: { id: string; request: { action: "open_app" | "type_text" | "invoke" | "select"; appId?: string; windowTitle?: string; name?: string; text?: string; overwrite?: boolean } };
  pendingCodexAction?: { id: string; description: string };
  createdAt: number;
  updatedAt: number;
}

export type BotInput = Pick<Bot, "name" | "instructions" | "providerId" | "workspaceId"> & { allowCommands?: boolean };

export const studioApi = {
  listBots: () => request<Bot[]>("/api/bots"),
  createBot: (input: BotInput) => request<Bot>("/api/bots", { method: "POST", body: JSON.stringify(input) }),
  updateBot: (id: string, input: BotInput) => request<Bot>(`/api/bots/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteBot: (id: string) => request<{ ok: true }>(`/api/bots/${encodeURIComponent(id)}`, { method: "DELETE" }),
  listRuns: (botId: string) => request<BotRun[]>(`/api/bots/${encodeURIComponent(botId)}/runs`),
  startRun: (botId: string, task: string) => request<BotRun>(`/api/bots/${encodeURIComponent(botId)}/runs`, { method: "POST", body: JSON.stringify({ task }) }),
  getRun: (id: string) => request<BotRun>(`/api/runs/${encodeURIComponent(id)}`),
  cancelRun: (id: string) => request<BotRun>(`/api/runs/${encodeURIComponent(id)}/cancel`, { method: "POST" }),
  approveChange: (id: string, changeId: string, approve: boolean) => request<BotRun>(`/api/runs/${encodeURIComponent(id)}/approval`, { method: "POST", body: JSON.stringify({ changeId, approve }) }),
  listWorkspaces: () => request<Workspace[]>("/api/workspaces"),
  registerWorkspace: (path: string) =>
    request<Workspace>("/api/workspaces", { method: "POST", body: JSON.stringify({ path }) }),
  listDirectory: (id: string, directory = "") =>
    request<{ entries: WorkspaceEntry[]; truncated: boolean }>(`/api/workspaces/${encodeURIComponent(id)}/tree?directory=${encodeURIComponent(directory)}`),
  readFile: (id: string, path: string) =>
    request<WorkspaceFile>(`/api/workspaces/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`),
  saveFile: (id: string, file: { path: string; content: string; expectedSha: string | null; approved: true }) =>
    request<WorkspaceFile>(`/api/workspaces/${encodeURIComponent(id)}/file`, { method: "PUT", body: JSON.stringify(file) }),
};
