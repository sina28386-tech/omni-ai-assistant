import type {
  ChatStreamHandlers,
  Conversation,
  ConversationSummary,
  MemoryFact,
  ProviderProfile,
  Settings,
  UsageSummary,
} from "./types";

const BASE = "";
let sessionToken = "";

export class ApiError extends Error {
  constructor(
    message: string,
    public code = "request_failed",
    public status = 0,
  ) {
    super(message);
  }
}

async function ensureToken(): Promise<string> {
  if (sessionToken) return sessionToken;
  const response = await fetch(`${BASE}/api/session`, { credentials: "same-origin" });
  if (!response.ok) throw new ApiError("The local Omni service is not reachable.", "session_failed", response.status);
  const body = (await response.json()) as { token?: string };
  if (!body.token) throw new ApiError("The local Omni service returned no session.", "session_invalid");
  sessionToken = body.token;
  return sessionToken;
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await ensureToken();
  const headers = new Headers(init.headers);
  headers.set("x-omni-token", token);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  const response = await fetch(`${BASE}${path}`, { ...init, headers, credentials: "same-origin" });
  if (!response.ok) {
    let message = `Request failed (${response.status}).`;
    let code = "request_failed";
    try {
      const body = (await response.json()) as { error?: string; code?: string };
      if (body.error) message = body.error;
      if (body.code) code = body.code;
    } catch {
      // Keep the status message.
    }
    throw new ApiError(message, code, response.status);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export const api = {
  ensureToken,

  getSettings: () => request<Settings>("/api/settings"),
  updateSettings: (patch: Partial<Settings>) =>
    request<Settings>("/api/settings", { method: "PUT", body: JSON.stringify(patch) }),

  listProviders: () => request<ProviderProfile[]>("/api/providers"),
  createProvider: (input: Record<string, unknown>) =>
    request<ProviderProfile>("/api/providers", { method: "POST", body: JSON.stringify(input) }),
  updateProvider: (id: string, patch: Record<string, unknown>) =>
    request<ProviderProfile>(`/api/providers/${id}`, { method: "PUT", body: JSON.stringify(patch) }),
  deleteProvider: (id: string) => request<{ ok: true }>(`/api/providers/${id}`, { method: "DELETE" }),
  testProvider: (id: string) =>
    request<{ ok: true; latencyMs: number; reply: string }>(`/api/providers/${id}/test`, { method: "POST" }),
  listModels: (id: string) => request<{ models: string[] }>(`/api/providers/${id}/models`),
  discoverModels: (baseUrl: string, apiKey: string) => request<{ models: string[] }>("/api/providers/discover", { method: "POST", body: JSON.stringify({ baseUrl, apiKey }) }),

  listConversations: () => request<ConversationSummary[]>("/api/conversations"),
  getConversation: (id: string) => request<Conversation>(`/api/conversations/${id}`),
  createConversation: (title = "") =>
    request<Conversation>("/api/conversations", { method: "POST", body: JSON.stringify({ title }) }),
  updateConversation: (id: string, patch: { title?: string; pinned?: boolean }) =>
    request<Conversation>(`/api/conversations/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
  deleteConversation: (id: string) => request<{ ok: true }>(`/api/conversations/${id}`, { method: "DELETE" }),

  listMemory: () => request<MemoryFact[]>("/api/memory"),
  createMemory: (input: { key: string; value: string; source?: string }) =>
    request<MemoryFact>("/api/memory", { method: "POST", body: JSON.stringify(input) }),
  updateMemory: (id: string, patch: { key?: string; value?: string; source?: string }) =>
    request<MemoryFact>(`/api/memory/${id}`, { method: "PUT", body: JSON.stringify(patch) }),
  deleteMemory: (id: string) => request<{ ok: true }>(`/api/memory/${id}`, { method: "DELETE" }),
  clearMemory: () => request<{ ok: true }>("/api/memory", { method: "DELETE" }),

  usage: () => request<UsageSummary>("/api/usage"),

  async chatStream(
    payload: { conversationId: string; content: string; providerId?: string; retry?: boolean },
    handlers: ChatStreamHandlers,
    signal: AbortSignal,
  ): Promise<void> {
    const token = await ensureToken();
    const response = await fetch(`${BASE}/api/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-omni-token": token },
      body: JSON.stringify(payload),
      signal,
      credentials: "same-origin",
    });
    if (!response.ok) {
      let message = `The assistant could not start (${response.status}).`;
      let code = "chat_failed";
      try {
        const body = (await response.json()) as { error?: string; code?: string };
        if (body.error) message = body.error;
        if (body.code) code = body.code;
      } catch {
        // Keep the status message.
      }
      throw new ApiError(message, code, response.status);
    }
    if (!response.body) throw new ApiError("The assistant returned no stream.", "chat_no_stream");

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
        let boundary = buffer.indexOf("\n\n");
        while (boundary >= 0) {
          const raw = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          dispatchEvent(raw, handlers);
          boundary = buffer.indexOf("\n\n");
        }
      }
    } finally {
      reader.releaseLock();
    }
  },
};

function dispatchEvent(raw: string, handlers: ChatStreamHandlers): void {
  let event = "message";
  const dataLines: string[] = [];
  for (const line of raw.split("\n")) {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
  }
  if (!dataLines.length) return;
  let data: unknown;
  try {
    data = JSON.parse(dataLines.join("\n"));
  } catch {
    return;
  }
  if (event === "meta") handlers.onMeta(data as Parameters<ChatStreamHandlers["onMeta"]>[0]);
  else if (event === "delta") handlers.onDelta((data as { text?: string }).text ?? "");
  else if (event === "done") handlers.onDone(data as Parameters<ChatStreamHandlers["onDone"]>[0]);
  else if (event === "error") handlers.onError(data as Parameters<ChatStreamHandlers["onError"]>[0]);
}
