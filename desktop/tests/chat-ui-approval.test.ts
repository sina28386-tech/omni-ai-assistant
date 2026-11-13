import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";

const actionMock = vi.hoisted(() => vi.fn(async () => ({ ok: true })));
vi.mock("../server/windows-ui.js", async importOriginal => {
  const original = await importOriginal<typeof import("../server/windows-ui.js")>();
  return { ...original, runUiAction: actionMock };
});
const base = path.join(os.tmpdir(), `omni-chat-ui-${randomUUID()}`);
process.env.OMNI_DATA_DIR = base;
const bots = await import("../server/bots.js");
const { createProvider } = await import("../server/providers.js");
const { createConversation } = await import("../server/conversations.js");
const { updateSettings } = await import("../server/settings.js");
let providerId = "";
beforeAll(async () => { providerId = (await createProvider({ name: "Fixture", baseUrl: "http://127.0.0.1:9999/v1", model: "fixture" })).id; });
afterAll(async () => { vi.restoreAllMocks(); await fs.rm(base, { recursive: true, force: true }); });
async function waitFor(id: string, state: string) {
  for (let i = 0; i < 500; i++) {
    const run = await bots.getBotRun(id);
    if (run.state === state) return run;
    if (run.state === "failed") throw new Error(run.error);
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error(`Timed out waiting for ${state}`);
}
const baseControl = { windowHandle: 10, processId: 20, windowTitle: "Test app", runtimeId: "42,1", name: "Text field" };
function response(call: unknown) { return new Response(JSON.stringify({ choices: [{ message: call }] })); }
function uiCall(id: string, arguments_: unknown) { return { id, type: "function", function: { name: "computer_ui", arguments: JSON.stringify(arguments_) } }; }

describe.runIf(process.platform === "win32")("chat computer UI approval", () => {
  it("requires approval for typing when automatic mode is off", async () => {
    actionMock.mockClear(); await updateSettings({ autoLowRisk: false });
    let calls = 0;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => response(++calls === 1 ? { tool_calls: [uiCall("type", { action: "type_text", ...baseControl, text: "hello" })] } : { content: "Done" }));
    try {
      const conversation = await createConversation();
      const run = await bots.startChatRun(conversation.id, providerId, "Type hello");
      const waiting = await waitFor(run.id, "waiting_approval");
      expect(waiting.pendingUiAction?.request.text).toBe("hello");
      expect(actionMock).not.toHaveBeenCalled();
      await bots.approveBotRun(run.id, waiting.pendingUiAction!.id, false);
      await waitFor(run.id, "complete");
      expect(actionMock).not.toHaveBeenCalled();
    } finally { fetchMock.mockRestore(); }
  });
  it("automatically types only after opt-in, but always asks before pressing a button", async () => {
    actionMock.mockClear(); await updateSettings({ autoLowRisk: true });
    let calls = 0;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => response(++calls === 1 ? { tool_calls: [uiCall("type", { action: "type_text", ...baseControl, text: "hello" })] } : calls === 2 ? { tool_calls: [uiCall("send", { action: "invoke", ...baseControl, name: "Send" })] } : { content: "Sent" }));
    try {
      const conversation = await createConversation();
      const run = await bots.startChatRun(conversation.id, providerId, "Send hello");
      const waiting = await waitFor(run.id, "waiting_approval");
      expect(actionMock).toHaveBeenCalledTimes(1);
      expect(waiting.pendingUiAction?.request.name).toBe("Send");
      await bots.approveBotRun(run.id, waiting.pendingUiAction!.id, true);
      await waitFor(run.id, "complete");
      expect(actionMock).toHaveBeenCalledTimes(2);
    } finally { fetchMock.mockRestore(); }
  });
  it.each([
    { action: "type_text", ...baseControl, text: "replacement", overwrite: true },
    { action: "select", ...baseControl, name: "Destination" },
  ])("requires approval for $action even in automatic mode", async request => {
    actionMock.mockClear(); await updateSettings({ autoLowRisk: true });
    let calls = 0;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => response(++calls === 1 ? { tool_calls: [uiCall("sensitive", request)] } : { content: "Done" }));
    try {
      const conversation = await createConversation();
      const run = await bots.startChatRun(conversation.id, providerId, "Perform the action");
      const waiting = await waitFor(run.id, "waiting_approval");
      expect(actionMock).not.toHaveBeenCalled();
      await bots.approveBotRun(run.id, waiting.pendingUiAction!.id, false);
      await waitFor(run.id, "complete");
      expect(actionMock).not.toHaveBeenCalled();
    } finally { fetchMock.mockRestore(); }
  });
});
