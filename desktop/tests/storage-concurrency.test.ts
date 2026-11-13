import { afterAll, describe, expect, it, vi } from "vitest";
import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { writeJson } from "../server/storage.js";

const directory = path.join(os.tmpdir(), `omni-storage-${randomUUID()}`);
afterAll(async () => { vi.restoreAllMocks(); await fs.rm(directory, { recursive: true, force: true }); });
describe("atomic local persistence", () => {
  it("serializes same-millisecond writes without reusing a temporary file", async () => {
    const clock = vi.spyOn(Date, "now").mockReturnValue(123456);
    const file = path.join(directory, "task.json");
    try {
      const results = await Promise.allSettled(Array.from({ length: 20 }, (_, index) => writeJson(file, { index })));
      expect(results.filter(result => result.status === "rejected")).toEqual([]);
      expect(JSON.parse(await fs.readFile(file, "utf8"))).toEqual({ index: 19 });
      expect((await fs.readdir(directory)).filter(name => name.endsWith(".tmp"))).toEqual([]);
    } finally { clock.mockRestore(); }
  });
});
