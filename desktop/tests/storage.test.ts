import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { promises as fs } from "node:fs";
import { randomUUID } from "node:crypto";
import os from "node:os";
import path from "node:path";

const dir = path.join(os.tmpdir(), `omni-storage-test-${randomUUID()}`);
process.env.OMNI_DATA_DIR = dir;

const { listJsonFiles, readJson, removeFile, writeJson } = await import("../server/storage.js");

beforeAll(async () => {
  await fs.mkdir(dir, { recursive: true });
});

afterAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

describe("JSON file storage", () => {
  it("returns a fallback for a missing file", async () => {
    expect(await readJson(path.join(dir, "missing.json"), { ok: true })).toEqual({ ok: true });
  });

  it("writes UTF-8 JSON atomically and reads it back", async () => {
    const file = path.join(dir, "facts.json");
    await writeJson(file, { title: "حافظه", rows: [1, 2, 3] });
    expect(await readJson(file, null)).toEqual({ title: "حافظه", rows: [1, 2, 3] });
    const raw = await fs.readFile(file, "utf8");
    expect(raw).toContain("حافظه");
    expect(raw.endsWith("\n")).toBe(false);
  });

  it("lists only JSON files and ignores directories", async () => {
    await fs.writeFile(path.join(dir, "note.txt"), "ignored");
    await fs.mkdir(path.join(dir, "nested"), { recursive: true });
    const files = await listJsonFiles(dir);
    expect(files.map((file) => path.basename(file))).toContain("facts.json");
    expect(files.map((file) => path.basename(file))).not.toContain("note.txt");
    expect(files.map((file) => path.basename(file))).not.toContain("nested");
  });

  it("removes a file idempotently", async () => {
    const file = path.join(dir, "temp.json");
    await writeJson(file, { ok: true });
    await removeFile(file);
    await expect(fs.stat(file)).rejects.toMatchObject({ code: "ENOENT" });
    await expect(removeFile(file)).resolves.toBeUndefined();
  });
});
