import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { createServer, type Server } from "node:http";
import { randomUUID } from "node:crypto";
import os from "node:os";
import path from "node:path";
import { promises as fs } from "node:fs";

const dir = path.join(os.tmpdir(), `omni-openai-test-${randomUUID()}`);
process.env.OMNI_DATA_DIR = dir;

const { buildTurns, parseSseEvent, streamCompletion, testProvider } = await import("../server/openai.js");
const { HttpError } = await import("../server/errors.js");
import type { ProviderRecord } from "../server/types.js";

const provider: ProviderRecord = {
  id: randomUUID(),
  name: "Mock",
  baseUrl: "http://127.0.0.1:9/v1",
  model: "mock-model",
  enabled: true,
  temperature: 0.2,
  maxTokens: 128,
  createdAt: Date.now(),
  updatedAt: Date.now(),
};

const servers: Server[] = [];

async function mockServer(handler: Parameters<typeof createServer>[1]): Promise<string> {
  const server = createServer(handler);
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("No port");
  return `http://127.0.0.1:${address.port}/v1`;
}

beforeAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

afterEach(async () => {
  await Promise.all(
    servers.splice(0).map(
      (server) =>
        new Promise<void>((resolve) => {
          server.close(() => resolve());
        }),
    ),
  );
});

afterAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

describe("prompt assembly", () => {
  it("adds a memory block and skips empty or system history", () => {
    const turns = buildTurns(
      "System rules.",
      [
        { id: "1", role: "system", content: "ignored", createdAt: 1 },
        { id: "2", role: "user", content: "Earlier question", createdAt: 2 },
        { id: "3", role: "assistant", content: "", createdAt: 3 },
        { id: "4", role: "assistant", content: "Earlier answer", createdAt: 4 },
      ],
      "New question",
      [{ id: "m1", key: "City", value: "Tehran", source: "manual", createdAt: 1, updatedAt: 1 }],
    );
    expect(turns[0].role).toBe("system");
    expect(turns[0].content).toContain("System rules.");
    expect(turns[0].content).toContain("City: Tehran");
    expect(turns.map((turn) => turn.content)).toEqual([
      turns[0].content,
      "Earlier question",
      "Earlier answer",
      "New question",
    ]);
  });

  it("keeps the last forty history messages", () => {
    const history = Array.from({ length: 45 }, (_, index) => ({
      id: String(index),
      role: "user" as const,
      content: `message ${index}`,
      createdAt: index,
    }));
    const turns = buildTurns("sys", history, "final", []);
    expect(turns).toHaveLength(42);
    expect(turns[1].content).toBe("message 5");
    expect(turns.at(-1)?.content).toBe("final");
  });
});

describe("SSE parsing", () => {
  it("parses data lines, usage, and the done marker", () => {
    expect(parseSseEvent('data: {"choices":[{"delta":{"content":"Hi"}}]}')).toEqual({ text: "Hi", done: false });
    expect(parseSseEvent('data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}')).toEqual({
      text: undefined,
      usage: { promptTokens: 4, completionTokens: 2 },
      done: false,
    });
    expect(parseSseEvent("data: [DONE]")).toEqual({ done: true });
    expect(parseSseEvent("data: not-json")).toEqual({});
  });
});

describe("streaming transport", () => {
  it("cancels a body that stalls after response headers", async () => {
    let finish = () => {};
    const baseUrl = await mockServer((_request, response) => {
      response.writeHead(200, { "Content-Type": "text/event-stream" });
      response.write('data: {"choices":[{"delta":{"content":"started"}}]}\n\n');
      finish = () => response.end("data: [DONE]\n\n");
    });
    let received!: () => void;
    const firstChunk = new Promise<void>(resolve => { received = resolve; });
    const controller = new AbortController();
    const completion = streamCompletion({ ...provider, baseUrl }, "", [], controller.signal, { onDelta: () => received() });
    const completed = completion.then(() => true, () => true);
    await firstChunk;
    controller.abort();
    let canceled = false;
    try {
      canceled = await Promise.race([completed, new Promise<false>(resolve => setTimeout(() => resolve(false), 250))]);
    } finally {
      finish();
      await completed;
    }
    expect(canceled, "Cancel must still reach the provider response body after headers arrive").toBe(true);
  });

  it("accepts CRLF-framed SSE events and records usage", async () => {
    const baseUrl = await mockServer((_request, response) => {
      response.writeHead(200, { "Content-Type": "text/event-stream; charset=utf-8" });
      response.write('data: {"choices":[{"delta":{"content":"Hello "}}]}\r\n\r\n');
      response.write('data: {"choices":[{"delta":{"content":"world"}}]}\r\n\r\n');
      response.write('data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}\r\n\r\n');
      response.end("data: [DONE]\r\n\r\n");
    });
    const chunks: string[] = [];
    let usage: { promptTokens: number; completionTokens: number } | undefined;
    await streamCompletion({ ...provider, baseUrl }, "key", [], new AbortController().signal, {
      onDelta: (text) => chunks.push(text),
      onUsage: (next) => {
        usage = next;
      },
    });
    expect(chunks.join("")).toBe("Hello world");
    expect(usage).toEqual({ promptTokens: 7, completionTokens: 2 });
  });

  it("falls back to a JSON response for gateways that ignore streaming", async () => {
    const baseUrl = await mockServer((_request, response) => {
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(
        JSON.stringify({
          choices: [{ message: { content: "Plain answer" } }],
          usage: { prompt_tokens: 3, completion_tokens: 2 },
        }),
      );
    });
    const chunks: string[] = [];
    await streamCompletion({ ...provider, baseUrl }, "key", [], new AbortController().signal, {
      onDelta: (text) => chunks.push(text),
    });
    expect(chunks.join("")).toBe("Plain answer");
  });

  it("maps provider authentication failures and scrubs key-shaped text", async () => {
    const baseUrl = await mockServer((_request, response) => {
      response.writeHead(401, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: { message: "bad key sk-1234567890abcdef" } }));
    });
    try {
      await streamCompletion({ ...provider, baseUrl }, "key", [], new AbortController().signal, {
        onDelta: () => undefined,
      });
      throw new Error("expected failure");
    } catch (error) {
      expect(error).toBeInstanceOf(HttpError);
      const failure = error as InstanceType<typeof HttpError>;
      expect(failure.status).toBe(401);
      expect(failure.message).not.toContain("sk-1234567890abcdef");
      expect(failure.message).toContain("sk-…");
    }
  });

  it("tests a provider with a non-streaming completion", async () => {
    const baseUrl = await mockServer((_request, response) => {
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ choices: [{ message: { content: "ready" } }] }));
    });
    const result = await testProvider({ ...provider, baseUrl }, "key", new AbortController().signal);
    expect(result.reply).toBe("ready");
    expect(result.latencyMs).toBeGreaterThanOrEqual(0);
  });
});
