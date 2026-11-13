import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { promises as fs } from "node:fs";
import { randomUUID } from "node:crypto";
import os from "node:os";
import path from "node:path";

const dir = path.join(os.tmpdir(), `omni-memory-test-${randomUUID()}`);
process.env.OMNI_DATA_DIR = dir;

const { clearMemory, createMemory, deleteMemory, listMemory, recallMemory, updateMemory } = await import(
  "../server/memory.js"
);

beforeAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

beforeEach(async () => {
  await clearMemory();
});

afterAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

describe("memory facts", () => {
  it("validates required fields", async () => {
    await expect(createMemory({ key: "", value: "x" })).rejects.toMatchObject({ status: 400 });
    await expect(createMemory({ key: "City", value: "" })).rejects.toMatchObject({ status: 400 });
  });

  it("replaces a fact with the same key instead of duplicating it", async () => {
    const first = await createMemory({ key: "Favorite color", value: "green" });
    const second = await createMemory({ key: "favorite COLOR", value: "blue" });
    expect(second.id).toBe(first.id);
    expect(second.createdAt).toBe(first.createdAt);
    const rows = await listMemory();
    expect(rows).toHaveLength(1);
    expect(rows[0].value).toBe("blue");
  });

  it("rejects a rename that collides with another key", async () => {
    const city = await createMemory({ key: "City", value: "Tehran" });
    await createMemory({ key: "Food", value: "Rice" });
    await expect(updateMemory(city.id, { key: "food" })).rejects.toMatchObject({ status: 409 });
  });

  it("updates and deletes a fact", async () => {
    const fact = await createMemory({ key: "City", value: "Tehran" });
    const updated = await updateMemory(fact.id, { value: "Shiraz", source: "manual" });
    expect(updated.value).toBe("Shiraz");
    expect(await deleteMemory(fact.id)).toBe(true);
    expect(await deleteMemory(fact.id)).toBe(false);
    expect(await listMemory()).toHaveLength(0);
  });
});

describe("explainable recall", () => {
  it("matches shared meaningful words and ranks the stronger match first", async () => {
    await createMemory({ key: "شهر من", value: "تهران" });
    await createMemory({ key: "رنگ مورد علاقه", value: "سبز" });
    await createMemory({ key: "غذای مورد علاقه", value: "قورمه سبزی" });

    const results = await recallMemory("رنگ مورد علاقه من چیست؟");
    expect(results.map((row) => row.key)).toEqual(["رنگ مورد علاقه", "غذای مورد علاقه"]);
  });

  it("ignores stopwords and short tokens", async () => {
    await createMemory({ key: "سلام", value: "دنیا" });
    const results = await recallMemory("سلام، این چیست؟");
    expect(results).toEqual([]);
  });

  it("returns an empty list when nothing matches", async () => {
    await createMemory({ key: "City", value: "Tehran" });
    expect(await recallMemory("quantum chromodynamics")).toEqual([]);
  });
});
