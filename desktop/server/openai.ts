import { chatEndpoint } from "./providers.js";
import { HttpError } from "./errors.js";
import type { ChatMessage, MemoryFact, ProviderRecord } from "./types.js";

export interface ChatTurn {
  role: "system" | "user" | "assistant";
  content: string;
}

export interface StreamCallbacks {
  onDelta(text: string): void;
  onUsage?(usage: { promptTokens: number; completionTokens: number }): void;
}

export function buildTurns(
  systemPrompt: string,
  history: ChatMessage[],
  userText: string,
  memory: MemoryFact[],
): ChatTurn[] {
  const memoryBlock = memory.length
    ? `Known facts the user chose to remember. Use them only when relevant:\n${memory
        .map((fact) => `- ${fact.key}: ${fact.value}`)
        .join("\n")}`
    : "";
  const system = [systemPrompt, memoryBlock].filter(Boolean).join("\n\n");
  const turns: ChatTurn[] = [{ role: "system", content: system }];
  for (const message of history.slice(-40)) {
    if (message.role === "system") continue;
    if (!message.content.trim()) continue;
    turns.push({ role: message.role, content: message.content.slice(0, 24000) });
  }
  turns.push({ role: "user", content: userText.slice(0, 24000) });
  return turns;
}

export async function streamCompletion(
  provider: ProviderRecord,
  apiKey: string,
  turns: ChatTurn[],
  signal: AbortSignal,
  callbacks: StreamCallbacks,
): Promise<void> {
  const response = await fetchWithTimeout(
    chatEndpoint(provider.baseUrl),
    {
      method: "POST",
      headers: providerHeaders(apiKey, true),
      body: JSON.stringify({
        model: provider.model,
        messages: turns,
        ...(new URL(provider.baseUrl).hostname === "api.anthropic.com" ? {} : { temperature: provider.temperature }),
        max_tokens: provider.maxTokens,
        stream: true,
      }),
      signal,
    },
    120_000,
  );

  if (!response.ok) {
    throw await providerError(response);
  }

  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("text/event-stream")) {
    // A few local gateways ignore stream=true and answer with JSON.
    const payload = (await response.json()) as OpenAiResponse;
    const text = payload.choices?.[0]?.message?.content ?? "";
    if (!text) throw new HttpError(502, "provider_empty", "The provider returned an empty answer.");
    callbacks.onDelta(text);
    const usage = normalizeUsage(payload.usage);
    if (usage) callbacks.onUsage?.(usage);
    return;
  }

  if (!response.body) throw new HttpError(502, "provider_no_body", "The provider returned no stream.");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const boundaryPattern = /\r?\n\r?\n/;
  let buffer = "";
  let finished = false;

  try {
    while (!finished) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let boundary = boundaryPattern.exec(buffer);
      while (boundary) {
        const rawEvent = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        const parsed = parseSseEvent(rawEvent);
        if (parsed.done) {
          finished = true;
          break;
        }
        if (parsed.text) callbacks.onDelta(parsed.text);
        if (parsed.usage) callbacks.onUsage?.(parsed.usage);
        boundary = boundaryPattern.exec(buffer);
      }
    }
    // Some gateways close the stream without a final blank line.
    if (!finished && buffer.trim()) {
      const parsed = parseSseEvent(buffer);
      if (parsed.text) callbacks.onDelta(parsed.text);
      if (parsed.usage) callbacks.onUsage?.(parsed.usage);
    }
  } finally {
    reader.releaseLock();
  }
}

export async function testProvider(
  provider: ProviderRecord,
  apiKey: string,
  signal: AbortSignal,
): Promise<{ latencyMs: number; reply: string }> {
  const started = Date.now();
  const response = await fetchWithTimeout(
    chatEndpoint(provider.baseUrl),
    {
      method: "POST",
      headers: providerHeaders(apiKey, false),
      body: JSON.stringify({
        model: provider.model,
        messages: [{ role: "user", content: "Reply with the single word: ready" }],
        ...(new URL(provider.baseUrl).hostname === "api.anthropic.com" ? {} : { temperature: 0 }),
        max_tokens: 16,
        stream: false,
      }),
      signal,
    },
    30_000,
  );
  if (!response.ok) throw await providerError(response);
  const payload = (await response.json()) as OpenAiResponse;
  const reply = payload.choices?.[0]?.message?.content?.trim() ?? "";
  return { latencyMs: Date.now() - started, reply: reply.slice(0, 160) };
}

export function providerHeaders(apiKey: string, stream: boolean): Record<string, string> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "User-Agent": "OmniDesktop/0.1",
  };
  if (stream) headers.Accept = "text/event-stream";
  if (apiKey.trim()) headers.Authorization = `Bearer ${apiKey.trim()}`;
  return headers;
}

export async function fetchWithTimeout(url: string, init: RequestInit, timeoutMs: number): Promise<Response> {
  const outer = init.signal as AbortSignal | undefined;
  // Keep cancellation linked for the whole response body, not just fetch's
  // headers promise. A stalled SSE/JSON body must not survive Stop.
  const deadline = AbortSignal.timeout(timeoutMs);
  const signal = outer ? AbortSignal.any([outer, deadline]) : deadline;
  try {
    return await fetch(url, { ...init, signal });
  } catch (error) {
    if (outer?.aborted) throw error;
    if (deadline.aborted) {
      throw new HttpError(504, "provider_timeout", "The provider took too long to answer.");
    }
    throw new HttpError(502, "provider_unreachable", "Could not reach the provider. Check the URL and your connection.");
  }
}

export async function providerError(response: Response): Promise<HttpError> {
  let detail = "";
  try {
    const body = (await response.json()) as { error?: { message?: string } | string; message?: string };
    detail =
      typeof body.error === "string"
        ? body.error
        : body.error?.message ?? body.message ?? JSON.stringify(body).slice(0, 300);
  } catch {
    try {
      detail = (await response.text()).slice(0, 300);
    } catch {
      detail = "";
    }
  }
  const clean = detail.replace(/sk-[A-Za-z0-9_-]{8,}/g, "sk-…").slice(0, 300);
  if (response.status === 401 || response.status === 403) {
    return new HttpError(401, "provider_auth", `The provider rejected the API key. ${clean}`.trim());
  }
  if (response.status === 404) {
    return new HttpError(404, "provider_endpoint", `The provider endpoint was not found. ${clean}`.trim());
  }
  if (response.status === 429) {
    return new HttpError(429, "provider_rate_limit", "The provider is rate limiting requests. Try again shortly.");
  }
  return new HttpError(502, "provider_error", `The provider returned ${response.status}. ${clean}`.trim());
}

export function parseSseEvent(raw: string): {
  text?: string;
  done?: boolean;
  usage?: { promptTokens: number; completionTokens: number };
} {
  const data = raw
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trim())
    .join("");
  if (!data) return {};
  if (data === "[DONE]") return { done: true };
  try {
    const chunk = JSON.parse(data) as {
      choices?: Array<{ delta?: { content?: string }; finish_reason?: string | null }>;
      usage?: { prompt_tokens?: number; completion_tokens?: number };
    };
    const text = chunk.choices?.[0]?.delta?.content ?? "";
    const usage = normalizeUsage(chunk.usage);
    return { text: text || undefined, usage, done: false };
  } catch {
    return {};
  }
}

interface OpenAiResponse {
  choices?: Array<{ message?: { content?: string }; finish_reason?: string | null }>;
  usage?: { prompt_tokens?: number; completion_tokens?: number };
}

function normalizeUsage(usage?: {
  prompt_tokens?: number;
  completion_tokens?: number;
}): { promptTokens: number; completionTokens: number } | undefined {
  if (!usage) return undefined;
  const promptTokens = Number.isFinite(usage.prompt_tokens) ? Number(usage.prompt_tokens) : 0;
  const completionTokens = Number.isFinite(usage.completion_tokens) ? Number(usage.completion_tokens) : 0;
  if (!promptTokens && !completionTokens) return undefined;
  return { promptTokens, completionTokens };
}
