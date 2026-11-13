import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { promises as fs } from "node:fs";
import path from "node:path";
import os from "node:os";
import { randomUUID } from "node:crypto";

const base = path.join(os.tmpdir(), `omni-workspace-${randomUUID()}`);
process.env.OMNI_DATA_DIR = path.join(base, "data");
const workspace = await import("../server/workspaces.js");
let id = "";
beforeAll(async () => {
  await fs.mkdir(path.join(base, "project", "src"), { recursive: true });
  await fs.writeFile(path.join(base, "project", "src", "main.ts"), "export const answer = 1;\n");
  await fs.writeFile(path.join(base, "project", ".env"), "PRIVATE=value");
  id = (await workspace.registerWorkspace(path.join(base, "project"))).id;
});
afterAll(async () => { await fs.rm(base, { recursive: true, force: true }); });

describe("explicit local coding workspace", () => {
  it("browses and reads selected code while hiding secrets", async () => {
    const tree = await workspace.listWorkspaceDirectory(id, "");
    expect(tree.entries.some(entry => entry.name === "src")).toBe(true);
    expect(tree.entries.some(entry => entry.name === ".env")).toBe(false);
    const file = await workspace.readWorkspaceFile(id, "src/main.ts");
    expect(file.content).toContain("answer = 1");
    expect(file.sha256).toMatch(/^[a-f0-9]{64}$/);
  });
  it("requires approval and rejects stale overwrites", async () => {
    const original = await workspace.readWorkspaceFile(id, "src/main.ts");
    await expect(workspace.writeWorkspaceFile(id, "src/main.ts", "changed", original.sha256, false)).rejects.toMatchObject({ status: 400 });
    const changed = await workspace.writeWorkspaceFile(id, "src/main.ts", "export const answer = 2;\n", original.sha256, true);
    expect(changed.sha256).not.toBe(original.sha256);
    await expect(workspace.writeWorkspaceFile(id, "src/main.ts", "lost update", original.sha256, true)).rejects.toMatchObject({ status: 409 });
    expect((await workspace.readWorkspaceFile(id, "src/main.ts")).content).toContain("answer = 2");
  });
  it.each(["../outside.txt", "src/../../outside.txt", ".env", "src/file.txt:stream", "C:\\Windows\\win.ini", "/etc/passwd"])("rejects unsafe path %s", async unsafe => {
    await expect(workspace.readWorkspaceFile(id, unsafe)).rejects.toMatchObject({ status: 400 });
  });
  it("rejects junction escapes even when the path text is inside the project", async () => {
    await fs.mkdir(path.join(base, "outside"));
    await fs.writeFile(path.join(base, "outside", "private.txt"), "outside");
    await fs.symlink(path.join(base, "outside"), path.join(base, "project", "linked"), "junction");
    await expect(workspace.readWorkspaceFile(id, "linked/private.txt")).rejects.toMatchObject({ status: 400 });
  });
  it("creates a new file only with an absent-file digest", async () => {
    const result = await workspace.writeWorkspaceFile(id, "src/new.ts", "export {};\n", null, true);
    expect(result.content).toBe("export {};\n");
    await expect(workspace.writeWorkspaceFile(id, "src/new.ts", "overwrite", null, true)).rejects.toMatchObject({ status: 409 });
  });
  it("does not start a file change after cancellation", async () => {
    const original = await workspace.readWorkspaceFile(id, "src/main.ts");
    const controller = new AbortController();
    controller.abort();
    await expect(workspace.writeWorkspaceFile(id, "src/main.ts", "canceled", original.sha256, true, controller.signal)).rejects.toBeDefined();
    expect((await workspace.readWorkspaceFile(id, "src/main.ts")).sha256).toBe(original.sha256);
  });
});
