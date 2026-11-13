import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { promises as fs } from "node:fs";
import path from "node:path";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { ensureDataDirs } from "./storage.js";
import { getSettings, updateSettings } from "./settings.js";
import {
  createProvider,
  deleteProvider,
  getProvider,
  getProviderKey,
  publicProviders,
  updateProvider,
} from "./providers.js";
import {
  appendMessage,
  createConversation,
  deleteConversation,
  deriveTitle,
  getConversation,
  listConversations,
  mutateConversation,
  pinConversation,
  renameConversation,
} from "./conversations.js";
import { clearMemory, createMemory, deleteMemory, listMemory, recallMemory, updateMemory } from "./memory.js";
import { recordUsage, usageSummary } from "./usage.js";
import { buildTurns, streamCompletion, testProvider } from "./openai.js";
import { applyPersonality } from "./personality.js";
import { discoverModels } from "./model-discovery.js";
import { explicitMemory } from "./memory-command.js";
import { HttpError } from "./errors.js";
import { startWindowsDictation } from "./windows-ui.js";
import { codexStatus } from "./codex-client.js";
import type { ChatMessage, Conversation } from "./types.js";
import { listWorkspaces, registerWorkspace, listWorkspaceDirectory, writeWorkspaceFile } from "./workspaces.js";
import { executeTool } from "./tool-engine.js";
import { initializeBots, listBots, createBot, getBot, updateBot, deleteBot, listBotRuns, listChatRuns, startBotRun, startChatRun, getBotRun, approveBotRun, cancelBotRun } from "./bots.js";

const PORT = Number(process.env.OMNI_PORT ?? 4317);
const HOST = "127.0.0.1";
const SESSION_TOKEN = randomBytes(32).toString("base64url");
const VERSION = "0.1.0";
const startedAt = Date.now();
const webRoot = path.resolve(process.cwd(), "dist", "web");
const devOrigins = new Set([
  "http://localhost:5173",
  "http://127.0.0.1:5173",
  `http://localhost:${PORT}`,
  `http://127.0.0.1:${PORT}`,
]);

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url ?? "/", `http://${req.headers.host ?? HOST}`);
    if (url.pathname.startsWith("/api/")) {
      applyApiSecurity(req, res);
      await handleApi(req, res, url);
      return;
    }
    await serveStatic(req, res, url);
  } catch (error) {
    sendError(res, error);
  }
});

server.requestTimeout = 0;
server.headersTimeout = 60_000;

await ensureDataDirs();
await initializeBots();
server.listen(PORT, HOST, () => {
  console.log(`[omni] desktop API on http://${HOST}:${PORT}`);
  console.log(`[omni] data directory: ${process.env.OMNI_DATA_DIR ?? "default user profile"}`);
});

async function handleApi(req: IncomingMessage, res: ServerResponse, url: URL): Promise<void> {
  const method = req.method ?? "GET";
  const pathname = url.pathname;

  if (method === "GET" && pathname === "/api/health") {
    sendJson(res, 200, { ok: true, version: VERSION, uptimeMs: Date.now() - startedAt });
    return;
  }
  if (method === "GET" && pathname === "/api/session") {
    sendJson(res, 200, { token: SESSION_TOKEN, version: VERSION });
    return;
  }

  if (method !== "GET") requireSession(req);

  // Local project contents and coworker tasks require the session token even
  // for reads. Existing origin/Host validation is applied before this router.
  if (/^\/api\/(?:workspaces|bots|runs)(?:\/|$)/.test(pathname)) requireSession(req);

  const chatRunMatch = /^\/api\/conversations\/([^/]+)\/assistant-run$/.exec(pathname);
  if (chatRunMatch) {
    requireSession(req);
    if (method === "GET") return sendJson(res, 200, await listChatRuns(chatRunMatch[1]));
    if (method === "POST") {
      const body = await readJsonBody(req);
      return sendJson(res, 202, await startChatRun(chatRunMatch[1], typeof body?.providerId === "string" ? body.providerId : "", typeof body?.content === "string" ? body.content : ""));
    }
  }

  if (pathname === "/api/voice/windows-dictation" && method === "POST") {
    await startWindowsDictation(new AbortController().signal);
    return sendJson(res, 200, { ok: true });
  }
  if (pathname === "/api/integrations/codex" && method === "GET") {
    requireSession(req);
    return sendJson(res, 200, await codexStatus());
  }
  if (pathname === "/api/integrations/codex/connect" && method === "POST") {
    const body = await readJsonBody(req);
    const model = typeof body?.model === "string" ? body.model : "";
    const status = await codexStatus();
    if (!status.authenticated || !status.models.includes(model)) throw new HttpError(400, "codex_not_ready", "Sign in to Codex and choose a listed model.");
    const existing = (await publicProviders()).find(row => row.kind === "codex" && row.model === model);
    return sendJson(res, 200, existing ?? await createProvider({ kind: "codex", name: "Codex", baseUrl: "https://api.openai.com/v1", model }));
  }

  if (pathname === "/api/workspaces") {
    if (method === "GET") return sendJson(res, 200, await listWorkspaces());
    if (method === "POST") {
      const body = await readJsonBody(req);
      return sendJson(res, 201, await registerWorkspace(typeof body?.path === "string" ? body.path : ""));
    }
  }
  const workspaceMatch = /^\/api\/workspaces\/([^/]+)\/(tree|file)$/.exec(pathname);
  if (workspaceMatch) {
    const id = workspaceMatch[1];
    if (workspaceMatch[2] === "tree" && method === "GET") return sendJson(res, 200, await listWorkspaceDirectory(id, url.searchParams.get("directory") ?? ""));
    if (workspaceMatch[2] === "file" && method === "GET") return sendJson(res, 200, await executeTool("workspace_read", { path: url.searchParams.get("path") ?? "" }, { workspaceId: id }));
    if (workspaceMatch[2] === "file" && method === "PUT") {
      const body = await readJsonBody(req);
      return sendJson(res, 200, await writeWorkspaceFile(id,
        typeof body?.path === "string" ? body.path : "",
        typeof body?.content === "string" ? body.content : "",
        body?.expectedSha === null ? null : typeof body?.expectedSha === "string" ? body.expectedSha : "",
        body?.approved === true));
    }
  }
  if (pathname === "/api/bots") {
    if (method === "GET") return sendJson(res, 200, await listBots());
    if (method === "POST") return sendJson(res, 201, await createBot((await readJsonBody(req)) ?? {}));
  }
  const botMatch = /^\/api\/bots\/([^/]+)$/.exec(pathname);
  if (botMatch) {
    if (method === "GET") return sendJson(res, 200, await getBot(botMatch[1]));
    if (method === "PUT") return sendJson(res, 200, await updateBot(botMatch[1], (await readJsonBody(req)) ?? {}));
    if (method === "DELETE") { await deleteBot(botMatch[1]); return sendJson(res, 200, { ok: true }); }
  }
  const botRunsMatch = /^\/api\/bots\/([^/]+)\/runs$/.exec(pathname);
  if (botRunsMatch) {
    if (method === "GET") return sendJson(res, 200, await listBotRuns(botRunsMatch[1]));
    if (method === "POST") {
      const body = await readJsonBody(req);
      return sendJson(res, 202, await startBotRun(botRunsMatch[1], typeof body?.task === "string" ? body.task : ""));
    }
  }
  const runMatch = /^\/api\/runs\/([^/]+)(?:\/(approval|cancel))?$/.exec(pathname);
  if (runMatch) {
    if (!runMatch[2] && method === "GET") return sendJson(res, 200, await getBotRun(runMatch[1]));
    if (runMatch[2] === "cancel" && method === "POST") return sendJson(res, 200, await cancelBotRun(runMatch[1]));
    if (runMatch[2] === "approval" && method === "POST") {
      const body = await readJsonBody(req);
      if (typeof body?.approve !== "boolean") throw new HttpError(400, "approval_required", "Choose approve or deny.");
      return sendJson(res, 200, await approveBotRun(runMatch[1], typeof body?.changeId === "string" ? body.changeId : "", body.approve));
    }
  }

  if (pathname === "/api/settings") {
    if (method === "GET") return sendJson(res, 200, await getSettings());
    if (method === "PUT") {
      const body = await readJsonBody(req);
      return sendJson(res, 200, await updateSettings(body ?? {}));
    }
  }

  if (pathname === "/api/providers") {
    if (method === "GET") return sendJson(res, 200, await publicProviders());
    if (method === "POST") {
      const body = await readJsonBody(req);
      return sendJson(res, 201, await createProvider(body ?? {}));
    }
  }

  if (pathname === "/api/providers/discover" && method === "POST") {
    const body = await readJsonBody(req);
    if (typeof body?.baseUrl !== "string" || (body.apiKey !== undefined && typeof body.apiKey !== "string")) throw new HttpError(400, "provider_invalid", "Provide a URL and optional API key.");
    return sendJson(res, 200, await discoverModels(body.baseUrl, body.apiKey ?? ""));
  }

  const providerMatch = /^\/api\/providers\/([^/]+)$/.exec(pathname);
  if (providerMatch) {
    const id = providerMatch[1];
    const provider = await getProvider(id);
    if (!provider) throw new HttpError(404, "provider_not_found", "Provider not found.");
    if (method === "PUT") {
      const body = await readJsonBody(req);
      const updated = await updateProvider(id, body ?? {});
      return sendJson(res, 200, updated);
    }
    if (method === "DELETE") {
      await deleteProvider(id);
      return sendJson(res, 200, { ok: true });
    }
  }

  const testMatch = /^\/api\/providers\/([^/]+)\/test$/.exec(pathname);
  if (testMatch && method === "POST") {
    const provider = await getProvider(testMatch[1]);
    if (!provider) throw new HttpError(404, "provider_not_found", "Provider not found.");
    const key = await getProviderKey(provider.id);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 30_000);
    try {
      if (provider.kind === "codex") {
        const status = await codexStatus();
        if (!status.authenticated) throw new HttpError(400, "codex_not_signed_in", "Codex is not signed in.");
        return sendJson(res, 200, { ok: true, latencyMs: 0, reply: "Codex signed in" });
      }
      const result = await testProvider(provider, key, controller.signal);
      return sendJson(res, 200, { ok: true, ...result });
    } finally {
      clearTimeout(timer);
    }
  }

  const modelsMatch = /^\/api\/providers\/([^/]+)\/models$/.exec(pathname);
  if (modelsMatch && method === "GET") {
    const provider = await getProvider(modelsMatch[1]);
    if (!provider) throw new HttpError(404, "provider_not_found", "Provider not found.");
    const key = await getProviderKey(provider.id);
    return sendJson(res, 200, await discoverModels(provider.baseUrl, key));
  }

  if (pathname === "/api/conversations") {
    if (method === "GET") return sendJson(res, 200, await listConversations());
    if (method === "POST") {
      const body = await readJsonBody(req);
      return sendJson(res, 201, await createConversation(typeof body?.title === "string" ? body.title : ""));
    }
  }

  const conversationMatch = /^\/api\/conversations\/([^/]+)$/.exec(pathname);
  if (conversationMatch) {
    const id = conversationMatch[1];
    if (method === "GET") {
      const conversation = await getConversation(id);
      if (!conversation) throw new HttpError(404, "conversation_not_found", "Conversation not found.");
      return sendJson(res, 200, conversation);
    }
    if (method === "DELETE") {
      const removed = await deleteConversation(id);
      if (!removed) throw new HttpError(404, "conversation_not_found", "Conversation not found.");
      return sendJson(res, 200, { ok: true });
    }
    if (method === "PATCH") {
      const body = await readJsonBody(req);
      if (typeof body?.title === "string") await renameConversation(id, body.title);
      if (typeof body?.pinned === "boolean") await pinConversation(id, body.pinned);
      const conversation = await getConversation(id);
      if (!conversation) throw new HttpError(404, "conversation_not_found", "Conversation not found.");
      return sendJson(res, 200, conversation);
    }
  }

  if (pathname === "/api/memory") {
    if (method === "GET") return sendJson(res, 200, await listMemory());
    if (method === "POST") {
      const body = await readJsonBody(req);
      return sendJson(res, 201, await createMemory(body ?? {}));
    }
    if (method === "DELETE") {
      await clearMemory();
      return sendJson(res, 200, { ok: true });
    }
  }

  const memoryMatch = /^\/api\/memory\/([^/]+)$/.exec(pathname);
  if (memoryMatch) {
    if (method === "PUT") {
      const body = await readJsonBody(req);
      return sendJson(res, 200, await updateMemory(memoryMatch[1], body ?? {}));
    }
    if (method === "DELETE") {
      const removed = await deleteMemory(memoryMatch[1]);
      if (!removed) throw new HttpError(404, "memory_not_found", "Memory not found.");
      return sendJson(res, 200, { ok: true });
    }
  }

  if (pathname === "/api/usage" && method === "GET") {
    return sendJson(res, 200, await usageSummary());
  }

  if (pathname === "/api/chat" && method === "POST") {
    await handleChat(req, res);
    return;
  }

  throw new HttpError(404, "not_found", "Not found.");
}

async function handleChat(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const body = await readJsonBody(req);
  const conversationId = typeof body?.conversationId === "string" ? body.conversationId : "";
  let conversation = conversationId ? await getConversation(conversationId) : null;
  if (!conversation) throw new HttpError(404, "conversation_not_found", "Conversation not found.");

  const retry = body?.retry === true;
  const content = typeof body?.content === "string" ? body.content.trim().slice(0, 24000) : "";
  if (!retry && !content) throw new HttpError(400, "message_required", "Write a message first.");

  const providerId = typeof body?.providerId === "string" ? body.providerId : conversation.providerId;
  const provider = providerId ? await getProvider(providerId) : (await publicProviders()).find((row) => row.enabled);
  if (!provider) {
    throw new HttpError(400, "provider_required", "Add an AI provider before sending a message.");
  }
  if (!provider.enabled) {
    throw new HttpError(400, "provider_disabled", "This provider is disabled. Enable it or choose another one.");
  }

  const userId = randomUUID();
  const assistantId = randomUUID();
  let userText = content;
  let usedMemory: Awaited<ReturnType<typeof recallMemory>> = [];

  if (retry) {
    const lastUserIndex = findLastUserIndex(conversation);
    if (lastUserIndex < 0) throw new HttpError(400, "retry_unavailable", "There is no message to retry.");
    userText = conversation.messages[lastUserIndex].content;
    conversation = await mutateConversation(conversation.id, (current) => {
      const index = findLastUserIndex(current);
      current.messages = index >= 0 ? current.messages.slice(0, index + 1) : [];
      current.providerId = provider.id;
      current.model = provider.model;
      current.updatedAt = Date.now();
      return current;
    });
  } else {
    const userMessage: ChatMessage = {
      id: userId,
      role: "user",
      content: userText,
      createdAt: Date.now(),
    };
    conversation = await appendMessage(conversation.id, userMessage);
    conversation = await mutateConversation(conversation.id, (current) => {
      current.providerId = provider.id;
      current.model = provider.model;
      if (!current.title) current.title = deriveTitle(userText);
      return current;
    });
  }

  const requestedMemory = !retry ? explicitMemory(userText) : null;
  const savedMemory = requestedMemory ? await createMemory({
    key: `chat-${createHash("sha256").update(requestedMemory).digest("hex").slice(0, 16)}`,
    value: requestedMemory,
    source: "explicit chat request",
  }) : null;
  usedMemory = await recallMemory(userText);
  if (savedMemory) usedMemory = [savedMemory, ...usedMemory.filter((row) => row.id !== savedMemory.id)].slice(0, 6);
  const history = conversation.messages.slice(0, -1);
  const settings = await getSettings();
  const turns = buildTurns(applyPersonality(settings.systemPrompt, settings.personality), history, userText, usedMemory);
  const apiKey = await getProviderKey(provider.id);

  res.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache, no-transform",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
  });
  res.write(
    sse("meta", {
      conversationId: conversation.id,
      conversationTitle: conversation.title,
      assistantMessageId: assistantId,
      userMessageId: userId,
      providerId: provider.id,
      model: provider.model,
      usedMemory: usedMemory.map((fact) => ({ id: fact.id, key: fact.key, value: fact.value })).slice(0, 6),
      savedMemoryId: savedMemory?.id,
    }),
  );

  const controller = new AbortController();
  let finished = false;
  let text = "";
  let promptTokens = 0;
  let completionTokens = 0;
  const heartbeat = setInterval(() => {
    if (!finished) res.write(": keep-alive\n\n");
  }, 15_000);

  res.on("close", () => {
    if (!finished) controller.abort();
  });

  try {
    await streamCompletion(provider, apiKey, turns, controller.signal, {
      onDelta(delta) {
        text += delta;
        res.write(sse("delta", { text: delta }));
      },
      onUsage(usage) {
        promptTokens = usage.promptTokens;
        completionTokens = usage.completionTokens;
      },
    });

    const message: ChatMessage = {
      id: assistantId,
      role: "assistant",
      content: text || "…",
      createdAt: Date.now(),
      providerId: provider.id,
      model: provider.model,
      status: "complete",
      promptTokens,
      completionTokens,
      usedMemory: usedMemory.map((fact) => fact.key),
    };
    const saved = await appendMessage(conversation.id, message);
    await recordUsage({
      conversationId: conversation.id,
      providerId: provider.id,
      model: provider.model,
      promptTokens,
      completionTokens,
    });
    if (!controller.signal.aborted) {
      res.write(sse("done", { message, conversationTitle: saved.title, usage: { promptTokens, completionTokens } }));
    }
  } catch (error) {
    if (controller.signal.aborted) {
      const message: ChatMessage = {
        id: assistantId,
        role: "assistant",
        content: text,
        createdAt: Date.now(),
        providerId: provider.id,
        model: provider.model,
        status: "stopped",
        promptTokens,
        completionTokens,
        usedMemory: usedMemory.map((fact) => fact.key),
      };
      if (text) await appendMessage(conversation.id, message).catch(() => undefined);
      if (!res.writableEnded) {
        res.write(sse("done", { message, stopped: true, usage: { promptTokens, completionTokens } }));
      }
    } else {
      const normalized = normalizeError(error);
      if (text) {
        const message: ChatMessage = {
          id: assistantId,
          role: "assistant",
          content: text,
          createdAt: Date.now(),
          providerId: provider.id,
          model: provider.model,
          status: "error",
          usedMemory: usedMemory.map((fact) => fact.key),
        };
        await appendMessage(conversation.id, message).catch(() => undefined);
      }
      if (!res.writableEnded) {
        res.write(sse("error", { error: normalized.message, code: normalized.code, partial: Boolean(text) }));
      }
    }
  } finally {
    finished = true;
    clearInterval(heartbeat);
    if (!res.writableEnded) res.end();
  }
}

function findLastUserIndex(conversation: Conversation): number {
  for (let index = conversation.messages.length - 1; index >= 0; index -= 1) {
    if (conversation.messages[index].role === "user") return index;
  }
  return -1;
}

function applyApiSecurity(req: IncomingMessage, res: ServerResponse): void {
  const origin = req.headers.origin;
  if (origin && !devOrigins.has(origin)) {
    throw new HttpError(403, "origin_rejected", "This origin is not allowed to talk to the local Omni service.");
  }
  if (req.headers["sec-fetch-site"] === "cross-site") {
    throw new HttpError(403, "cross_site_rejected", "Cross-site requests are rejected.");
  }
  const host = (req.headers.host ?? "").split(":")[0];
  if (host && !["127.0.0.1", "localhost", "::1", "[::1]"].includes(host)) {
    throw new HttpError(403, "host_rejected", "The local Omni service only accepts loopback requests.");
  }
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("Cache-Control", "no-store");
}

function requireSession(req: IncomingMessage): void {
  const token = req.headers["x-omni-token"];
  if (typeof token !== "string" || token.length !== SESSION_TOKEN.length) {
    throw new HttpError(403, "session_required", "Missing local session token.");
  }
  // Constant-time comparison avoids leaking the token through timing.
  const a = Buffer.from(token);
  const b = Buffer.from(SESSION_TOKEN);
  if (a.length !== b.length || !timingSafeEqual(a, b)) {
    throw new HttpError(403, "session_invalid", "The local session token is invalid. Reload Omni.");
  }
}

function timingSafeEqual(a: Buffer, b: Buffer): boolean {
  let diff = 0;
  for (let index = 0; index < a.length; index += 1) diff |= a[index] ^ b[index];
  return diff === 0;
}

async function readJsonBody(req: IncomingMessage): Promise<Record<string, unknown> | null> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > 2 * 1024 * 1024) throw new HttpError(413, "body_too_large", "The request body is too large.");
    chunks.push(buffer);
  }
  if (!chunks.length) return null;
  try {
    const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("not an object");
    }
    return parsed as Record<string, unknown>;
  } catch {
    throw new HttpError(400, "body_invalid", "The request body must be valid JSON.");
  }
}

function sendJson(res: ServerResponse, status: number, value: unknown): void {
  if (res.writableEnded) return;
  const body = JSON.stringify(value);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

function sendError(res: ServerResponse, error: unknown): void {
  const normalized = normalizeError(error);
  if (!res.headersSent) {
    sendJson(res, normalized.status, { error: normalized.message, code: normalized.code });
  } else if (!res.writableEnded) {
    res.end();
  }
}

function normalizeError(error: unknown): { status: number; code: string; message: string } {
  if (error instanceof HttpError) {
    return { status: error.status, code: error.code, message: error.message };
  }
  const message = error instanceof Error ? error.message : "Unexpected error.";
  return { status: 500, code: "internal_error", message: message.slice(0, 300) };
}

function sse(event: string, data: unknown): string {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

async function serveStatic(req: IncomingMessage, res: ServerResponse, url: URL): Promise<void> {
  if (req.method !== "GET" && req.method !== "HEAD") {
    throw new HttpError(405, "method_not_allowed", "Method not allowed.");
  }
  let pathname = decodeURIComponent(url.pathname);
  if (pathname === "/") pathname = "/index.html";
  const safePath = path.normalize(path.join(webRoot, pathname));
  if (!safePath.startsWith(webRoot + path.sep) && safePath !== path.join(webRoot, "index.html")) {
    throw new HttpError(403, "path_rejected", "Invalid path.");
  }
  let file = safePath;
  try {
    const stat = await fs.stat(file);
    if (stat.isDirectory()) file = path.join(file, "index.html");
  } catch {
    // SPA fallback for app routes.
    file = path.join(webRoot, "index.html");
  }
  let body: Buffer;
  try {
    body = await fs.readFile(file);
  } catch {
    throw new HttpError(404, "web_build_missing", "The web build is missing. Run `npm run build` first.");
  }
  const ext = path.extname(file).toLowerCase();
  const type =
    {
      ".html": "text/html; charset=utf-8",
      ".js": "text/javascript; charset=utf-8",
      ".css": "text/css; charset=utf-8",
      ".json": "application/json; charset=utf-8",
      ".svg": "image/svg+xml",
      ".png": "image/png",
      ".woff2": "font/woff2",
      ".woff": "font/woff",
      ".ttf": "font/ttf",
      ".ico": "image/x-icon",
      ".md": "text/plain; charset=utf-8",
      ".txt": "text/plain; charset=utf-8",
    }[ext] ?? "application/octet-stream";
  const immutable = file.includes(`${path.sep}assets${path.sep}`);
  res.writeHead(200, {
    "Content-Type": type,
    "Content-Length": body.length,
    "Cache-Control": immutable ? "public, max-age=31536000, immutable" : "no-cache",
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy":
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
  });
  if (req.method === "HEAD") res.end();
  else res.end(body);
}
