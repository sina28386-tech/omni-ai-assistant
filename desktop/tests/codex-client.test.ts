import { expect, it } from "vitest";
import path from "node:path";
import { codexTurn } from "../server/codex-client.js";
import { UiStalledError } from "../server/ui-progress.js";

it("runs the app-server handshake, streams text, and relays a user decision", async () => {
  const approvals: string[] = [];
  const progress: string[] = [];
  const result = await codexTurn("fixture", "hello", new AbortController().signal, async (method, params) => {
    approvals.push(`${method}:${params.command}`);
    return { decision: "accept" };
  }, text => progress.push(text), { executable: process.execPath, args: [path.resolve("tests/fixtures/fake-codex-app-server.cjs")] });
  expect(result).toBe("First approved");
  expect(approvals).toEqual(["item/commandExecution/requestApproval:fixture command"]);
  expect(progress).toEqual(["First ", "First approved"]);
});

it("connects a dynamic Omni tool to the Codex app-server request", async () => {
  const result = await codexTurn("fixture", "hello", new AbortController().signal, async (method, params) => {
    expect(method).toBe("item/tool/call");
    expect(params.arguments).toEqual({ action: "list_windows" });
    return { success: true, contentItems: [{ type: "inputText", text: "fixture result" }] };
  }, () => undefined, { executable: process.execPath, args: [path.resolve("tests/fixtures/fake-codex-app-server.cjs")] }, [{ type: "function", name: "computer_ui", description: "Fixture", inputSchema: { type: "object" } }]);
  expect(result).toBe("First tool returned");
});

it("stops the Codex turn when computer control cannot make progress", async () => {
  await expect(codexTurn("fixture", "hello", new AbortController().signal, async () => {
    throw new UiStalledError();
  }, () => undefined, { executable: process.execPath, args: [path.resolve("tests/fixtures/fake-codex-app-server.cjs")] }, [{ type: "function", name: "computer_ui", description: "Fixture", inputSchema: { type: "object" } }])).rejects.toThrow(UiStalledError);
});
