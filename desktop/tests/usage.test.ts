import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { promises as fs } from "node:fs";
import { randomUUID } from "node:crypto";
import os from "node:os";
import path from "node:path";

const dir = path.join(os.tmpdir(), `omni-usage-test-${randomUUID()}`);
process.env.OMNI_DATA_DIR = dir;

const { recordUsage, usageSummary } = await import("../server/usage.js");

beforeAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

afterAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

describe("usage accounting", () => {
  it("totals requests, tokens, and groups them by UTC day", async () => {
    await recordUsage({
      conversationId: "conversation-a",
      providerId: "provider",
      model: "mock",
      promptTokens: 20,
      completionTokens: 10,
    });
    await recordUsage({
      conversationId: "conversation-b",
      providerId: "provider",
      model: "mock",
      promptTokens: 5,
      completionTokens: 5,
    });

    const summary = await usageSummary();
    expect(summary.requests).toBe(2);
    expect(summary.promptTokens).toBe(25);
    expect(summary.completionTokens).toBe(15);
    expect(summary.byDay).toHaveLength(1);
    expect(summary.byDay[0].requests).toBe(2);
    expect(summary.byDay[0].promptTokens).toBe(25);
    expect(summary.byDay[0].day).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it("returns an empty summary when no requests exist", async () => {
    await fs.rm(path.join(dir, "usage.json"), { force: true });
    expect(await usageSummary()).toEqual({
      requests: 0,
      promptTokens: 0,
      completionTokens: 0,
      byDay: [],
    });
  });
});
