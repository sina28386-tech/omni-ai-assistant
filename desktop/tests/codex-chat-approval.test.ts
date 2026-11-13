import { afterAll, expect, it, vi } from "vitest";
import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";

const actionMock = vi.hoisted(() => vi.fn(async () => ({ ok: true })));
vi.mock("../server/windows-ui.js", async importOriginal => {
  const original = await importOriginal<typeof import("../server/windows-ui.js")>();
  return { ...original, runUiAction: actionMock };
});
vi.mock("../server/codex-client.js", () => ({
  codexTurn: async (_model: unknown, _text: unknown, _signal: unknown, call: (method: string, args: unknown) => Promise<{ success?: boolean }>, _progress: unknown, _fixture: unknown, tools: unknown[]) => {
    expect(tools).toEqual(expect.arrayContaining([expect.objectContaining({ name: "computer_ui" })]));
    const target = { windowHandle: 10, processId: 20, windowTitle: "Test app", runtimeId: "42,1" };
    expect((await call("item/tool/call", { tool: "computer_ui", arguments: { action: "type_text", ...target, name: "Input", text: "hello" } })).success).toBe(true);
    expect((await call("item/tool/call", { tool: "computer_ui", arguments: { action: "invoke", ...target, name: "Send" } })).success).toBe(true);
    return "Fixture finished";
  },
}));
const base = path.join(os.tmpdir(), `omni-codex-chat-${randomUUID()}`);
process.env.OMNI_DATA_DIR = base;
const bots = await import("../server/bots.js");
const { createProvider } = await import("../server/providers.js");
const { createConversation } = await import("../server/conversations.js");
const { updateSettings } = await import("../server/settings.js");
afterAll(async () => { await fs.rm(base, { recursive: true, force: true }); });

it.runIf(process.platform === "win32")("lets Codex use automatic typing but waits for explicit button approval", async () => {
  await updateSettings({ autoLowRisk: true });
  const provider = await createProvider({ kind: "codex", name: "fixture", model: "fixture" });
  const conversation = await createConversation();
  const run = await bots.startChatRun(conversation.id, provider.id, "Type and press the button");
  let waiting;
  for (let i = 0; i < 500; i++) {
    const current = await bots.getBotRun(run.id);
    if (current.state === "failed") throw new Error(current.error);
    if (current.state === "waiting_approval") { waiting = current; break; }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  expect(waiting?.pendingUiAction?.request.name).toBe("Send");
  expect(actionMock).toHaveBeenCalledTimes(1);
  await bots.approveBotRun(run.id, waiting!.pendingUiAction!.id, true);
  for (let i = 0; i < 500; i++) {
    const current = await bots.getBotRun(run.id);
    if (current.state === "failed") throw new Error(current.error);
    if (current.state === "complete") { expect(actionMock).toHaveBeenCalledTimes(2); return; }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error("Codex run did not finish");
});
