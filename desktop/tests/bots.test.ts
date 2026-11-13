import { beforeAll, afterAll, describe, expect, it, vi } from "vitest";
import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";

const base = path.join(os.tmpdir(), `omni-bots-${randomUUID()}`);
process.env.OMNI_DATA_DIR = path.join(base, "data");
const bots = await import("../server/bots.js");
const { createProvider } = await import("../server/providers.js");
const { registerWorkspace } = await import("../server/workspaces.js");
const { createConversation, appendMessage, getConversation } = await import("../server/conversations.js");
let providerId = "";
let workspaceId = "";
beforeAll(async () => {
  await fs.mkdir(path.join(base, "project"), { recursive: true });
  await fs.writeFile(path.join(base, "project", "main.ts"), "old\n");
  providerId = (await createProvider({ name: "Fixture", baseUrl: "http://127.0.0.1:9999/v1", model: "fixture", apiKey: "" })).id;
  workspaceId = (await registerWorkspace(path.join(base, "project"))).id;
});
afterAll(async () => { vi.restoreAllMocks(); await fs.rm(base, { recursive: true, force: true }); });

async function until(id: string, state: string) {
  for (let i = 0; i < 1000; i++) {
    const run = await bots.getBotRun(id);
    if (run.state === state) return run;
    if (run.state === "failed" && state !== "failed") throw new Error(run.error);
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error(`Run did not reach ${state}`);
}
function fixtureWrite() {
  let turn = 0;
  return vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ choices: [{ message: ++turn === 1
    ? { content: "", tool_calls: [{ id: "edit-one", type: "function", function: { name: "workspace_write", arguments: JSON.stringify({ path: "main.ts", content: "new\n" }) } }] }
    : { content: "Finished" } }] }), { headers: { "Content-Type": "application/json" } }));
}

describe("persistent coworkers and real local task lifecycle", () => {
  it.runIf(process.platform === "win32")("uses existing chat context and requires approval for a computer command", async () => {
    const conversation = await createConversation("Existing conversation");
    await appendMessage(conversation.id, { id: randomUUID(), role: "user", content: "Remember this conversation context", createdAt: Date.now() });
    let calls = 0; let firstTurns: Array<{ role: string; content: string }> = [];
    const mock = vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, init) => {
      const payload = JSON.parse(String(init?.body));
      if (++calls === 1) {
        firstTurns = payload.messages;
        return new Response(JSON.stringify({ choices: [{ message: { tool_calls: [{ id: "chat-cmd", type: "function", function: { name: "system_command", arguments: JSON.stringify({ shell: "powershell", command: "[Console]::Write('chat-result')" }) } }] } }] }));
      }
      return new Response(JSON.stringify({ choices: [{ message: { content: "Done in this conversation" } }] }));
    });
    try {
      const started = await bots.startChatRun(conversation.id, providerId, "Run a safe check");
      const waiting = await until(started.id, "waiting_approval");
      expect(firstTurns.some(row => row.content === "Remember this conversation context")).toBe(true);
      expect(calls).toBe(1);
      await bots.approveBotRun(started.id, waiting.pendingCommand!.id, true);
      const done = await until(started.id, "complete");
      expect(done.steps[0].result?.stdout).toBe("chat-result");
      const updated = await getConversation(conversation.id);
      expect(updated?.messages.map(row => row.content)).toEqual(["Remember this conversation context", "Run a safe check", "Done in this conversation"]);
      expect(updated?.messages.at(-1)?.runId).toBe(started.id);
    } finally { mock.mockRestore(); }
  }, 20_000);
  it("persists bot role and conversation independently", async () => {
    const a = await bots.createBot({ name: "Engineer", instructions: "Explain before editing", providerId, workspaceId });
    const b = await bots.createBot({ name: "Researcher", instructions: "Cite sources", providerId });
    expect(a.conversationId).not.toBe(b.conversationId);
    expect((await bots.listBots()).find(bot => bot.id === a.id)?.instructions).toBe("Explain before editing");
    await bots.updateBot(a.id, { name: "Code reviewer" });
    expect((await bots.getBot(a.id)).conversationId).toBe(a.conversationId);
  });
  it("does not edit until an exact pending change is approved", async () => {
    const mock = fixtureWrite();
    try {
      const bot = await bots.createBot({ name: "Writer", instructions: "Edit selected project", providerId, workspaceId });
      const started = await bots.startBotRun(bot.id, "Change main.ts");
      const waiting = await until(started.id, "waiting_approval");
      expect(await fs.readFile(path.join(base, "project", "main.ts"), "utf8")).toBe("old\n");
      await expect(bots.approveBotRun(started.id, "wrong-change", true)).rejects.toMatchObject({ status: 409 });
      await bots.approveBotRun(started.id, waiting.pendingChange!.id, true);
      const done = await until(started.id, "complete");
      expect(done.output).toBe("Finished");
      expect(await fs.readFile(path.join(base, "project", "main.ts"), "utf8")).toBe("new\n");
      expect(done.steps.some(step => step.tool === "workspace_write" && step.state === "complete")).toBe(true);
    } finally { mock.mockRestore(); }
  });
  it("canceling an approval cannot later write a file", async () => {
    await fs.writeFile(path.join(base, "project", "main.ts"), "keep\n");
    const mock = fixtureWrite();
    try {
      const bot = await bots.createBot({ name: "Cancelable", instructions: "Edit selected project", providerId, workspaceId });
      const started = await bots.startBotRun(bot.id, "Change main.ts");
      const waiting = await until(started.id, "waiting_approval");
      await bots.cancelBotRun(started.id);
      await until(started.id, "canceled");
      await expect(bots.approveBotRun(started.id, waiting.pendingChange!.id, true)).rejects.toMatchObject({ status: 409 });
      expect(await fs.readFile(path.join(base, "project", "main.ts"), "utf8")).toBe("keep\n");
    } finally { mock.mockRestore(); }
  });
  it.runIf(process.platform === "win32")("executes a proposed system command only after exact approval and returns its real output", async () => {
    let count = 0;
    let toolResult = "";
    const mock = vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, init) => {
      const payload = JSON.parse(String(init?.body));
      if (++count === 1) return new Response(JSON.stringify({ choices: [{ message: { tool_calls: [{ id: "cmd-one", type: "function", function: { name: "system_command", arguments: JSON.stringify({ shell: "powershell", command: "[Console]::Write('confirmed-output')" }) } }] } }] }));
      toolResult = payload.messages.find((row: { role: string }) => row.role === "tool")?.content ?? "";
      return new Response(JSON.stringify({ choices: [{ message: { content: "Command finished" } }] }));
    });
    try {
      const bot = await bots.createBot({ name: "Computer test", instructions: "Run a harmless command", providerId, allowCommands: true });
      const started = await bots.startBotRun(bot.id, "Print a test value");
      const waiting = await until(started.id, "waiting_approval");
      expect(count).toBe(1);
      expect(waiting.pendingCommand?.command).toBe("[Console]::Write('confirmed-output')");
      await expect(bots.approveBotRun(started.id, "wrong", true)).rejects.toMatchObject({ status: 409 });
      await bots.approveBotRun(started.id, waiting.pendingCommand!.id, true);
      const completed = await until(started.id, "complete");
      expect(JSON.parse(toolResult)).toMatchObject({ ok: true, exitCode: 0, stdout: "confirmed-output" });
      expect(completed.steps.find(step => step.tool === "system_command")?.result).toMatchObject({ ok: true, stdout: "confirmed-output" });
    } finally { mock.mockRestore(); }
  }, 20_000);
  it.runIf(process.platform === "win32")("does not execute a rejected command", async () => {
    const marker = path.join(base, "must-not-exist.txt");
    const command = `Set-Content -LiteralPath '${marker.replace(/'/g, "''")}' -Value 'wrong'`;
    let count = 0;
    let toolResult = "";
    const mock = vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, init) => {
      const payload = JSON.parse(String(init?.body));
      if (++count === 1) return new Response(JSON.stringify({ choices: [{ message: { tool_calls: [{ id: "cmd-denied", type: "function", function: { name: "system_command", arguments: JSON.stringify({ shell: "powershell", command }) } }] } }] }));
      toolResult = payload.messages.find((row: { role: string }) => row.role === "tool")?.content ?? "";
      return new Response(JSON.stringify({ choices: [{ message: { content: "Command rejected" } }] }));
    });
    try {
      const bot = await bots.createBot({ name: "Deny test", instructions: "Request approval", providerId, allowCommands: true });
      const started = await bots.startBotRun(bot.id, "Request a test command");
      const waiting = await until(started.id, "waiting_approval");
      await bots.approveBotRun(started.id, waiting.pendingCommand!.id, false);
      await until(started.id, "complete");
      await expect(fs.stat(marker)).rejects.toMatchObject({ code: "ENOENT" });
      expect(JSON.parse(toolResult)).toMatchObject({ ok: false });
    } finally { mock.mockRestore(); }
  });
});
