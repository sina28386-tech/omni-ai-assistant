import { beforeAll, afterAll, describe, expect, it, vi } from "vitest";
import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";

const base = path.join(os.tmpdir(), `omni-engine-${randomUUID()}`);
process.env.OMNI_DATA_DIR = path.join(base, "data");
const engine = await import("../server/tool-engine.js");
const bots = await import("../server/bots.js");
const { createProvider } = await import("../server/providers.js");
const { registerWorkspace } = await import("../server/workspaces.js");
let workspaceId = "";
beforeAll(async () => {
  await fs.mkdir(path.join(base, "project"), { recursive: true });
  await fs.writeFile(path.join(base, "project", "readme.txt"), "Shared contract\n");
  await fs.writeFile(path.join(base, "project", ".env"), "secret");
  workspaceId = (await registerWorkspace(path.join(base, "project"))).id;
});
afterAll(async () => { vi.restoreAllMocks(); await fs.rm(base, { recursive: true, force: true }); });

async function finished(id: string) {
  for (let i = 0; i < 300; i++) {
    const run = await bots.getBotRun(id);
    if (run.state === "complete") return run;
    if (run.state === "failed") throw new Error(run.error);
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error("Run did not finish");
}

describe("workspace read tool contract", () => {
  it("validates untrusted arguments and requires an explicitly selected workspace", async () => {
    await expect(engine.executeTool("workspace_read", { path: "readme.txt" }, { workspaceId: null })).rejects.toMatchObject({ code: "workspace_required" });
    await expect(engine.executeTool("workspace_read", { path: 3 }, { workspaceId })).rejects.toMatchObject({ code: "tool_arguments_invalid" });
    await expect(engine.executeTool("workspace_read", { path: "readme.txt", extra: true }, { workspaceId })).rejects.toMatchObject({ code: "tool_arguments_invalid" });
    await expect(engine.executeTool("workspace_read", { path: "../outside" }, { workspaceId })).rejects.toMatchObject({ code: "workspace_path_invalid" });
    await expect(engine.executeTool("workspace_read", { path: ".env" }, { workspaceId })).rejects.toMatchObject({ code: "workspace_path_invalid" });
    await expect(engine.executeTool("unknown", {}, { workspaceId })).rejects.toMatchObject({ code: "tool_unavailable" });
  });

  it("does not execute an already canceled request", async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(engine.executeTool("workspace_read", { path: "readme.txt" }, { workspaceId, signal: controller.signal })).rejects.toMatchObject({ name: "AbortError" });
  });

  it("uses the same operation for manual access and two independent simulated providers", async () => {
    const manual = await engine.executeTool("workspace_read", { path: "readme.txt" }, { workspaceId });
    const observed: unknown[] = [];
    const turnByModel = new Map<string, number>();
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, init) => {
      const request = JSON.parse(String(init?.body));
      const count = (turnByModel.get(request.model) ?? 0) + 1;
      turnByModel.set(request.model, count);
      if (count === 1) {
        expect(request.tools.find((t: { function: { name: string } }) => t.function.name === "workspace_read").function.parameters).toEqual(engine.workspaceReadSpec.parameters);
        return new Response(JSON.stringify({ choices: [{ message: { tool_calls: [{ id: "read", type: "function", function: { name: "workspace_read", arguments: JSON.stringify({ path: "readme.txt" }) } }] } }] }));
      }
      observed.push(JSON.parse(request.messages.find((m: { role: string }) => m.role === "tool").content));
      return new Response(JSON.stringify({ choices: [{ message: { content: "Read complete" } }] }));
    });
    try {
      for (const model of ["fixture-a", "fixture-b"]) {
        const provider = await createProvider({ name: model, baseUrl: "http://127.0.0.1:9999/v1", model, apiKey: "" });
        const bot = await bots.createBot({ name: model, instructions: "Read the file", providerId: provider.id, workspaceId });
        await finished((await bots.startBotRun(bot.id, "Read readme.txt")).id);
      }
      expect(observed).toEqual([{ ...manual, truncated: false }, { ...manual, truncated: false }]);
    } finally { fetchMock.mockRestore(); }
  });
});
