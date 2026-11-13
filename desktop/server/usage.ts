import path from "node:path";
import { randomUUID } from "node:crypto";
import { dataDir, readJson, writeJson } from "./storage.js";
import type { UsageEvent, UsageSummary } from "./types.js";

const usageFile = path.join(dataDir, "usage.json");

export async function recordUsage(input: Omit<UsageEvent, "id" | "createdAt">): Promise<void> {
  const rows = await readJson<UsageEvent[]>(usageFile, []);
  rows.push({ ...input, id: randomUUID(), createdAt: Date.now() });
  // Thirty days of usage is enough for the meter and keeps the file small.
  const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
  await writeJson(usageFile, rows.filter((row) => row.createdAt >= cutoff).slice(-5000));
}

export async function usageSummary(): Promise<UsageSummary> {
  const rows = await readJson<UsageEvent[]>(usageFile, []);
  const byDay = new Map<string, { requests: number; promptTokens: number; completionTokens: number }>();
  let promptTokens = 0;
  let completionTokens = 0;
  for (const row of rows) {
    promptTokens += row.promptTokens || 0;
    completionTokens += row.completionTokens || 0;
    const day = new Date(row.createdAt).toISOString().slice(0, 10);
    const entry = byDay.get(day) ?? { requests: 0, promptTokens: 0, completionTokens: 0 };
    entry.requests += 1;
    entry.promptTokens += row.promptTokens || 0;
    entry.completionTokens += row.completionTokens || 0;
    byDay.set(day, entry);
  }
  return {
    requests: rows.length,
    promptTokens,
    completionTokens,
    byDay: [...byDay.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([day, value]) => ({ day, ...value })),
  };
}
