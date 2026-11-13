import path from "node:path";
import { randomUUID } from "node:crypto";
import { dataDir, readJson, writeJson } from "./storage.js";
import { HttpError } from "./errors.js";
import type { MemoryFact } from "./types.js";

const memoryFile = path.join(dataDir, "memory.json");

export async function listMemory(): Promise<MemoryFact[]> {
  const rows = await readJson<MemoryFact[]>(memoryFile, []);
  return rows
    .filter((row) => row && typeof row.id === "string" && typeof row.key === "string")
    .sort((a, b) => b.updatedAt - a.updatedAt);
}

export async function createMemory(input: { key?: string; value?: string; source?: string }): Promise<MemoryFact> {
  const key = cleanKey(input.key);
  const value = cleanValue(input.value);
  if (!key) throw new HttpError(400, "memory_key_required", "A memory needs a short label.");
  if (!value) throw new HttpError(400, "memory_value_required", "A memory needs a value.");
  const now = Date.now();
  const rows = await listMemory();
  const index = rows.findIndex((row) => row.key.toLowerCase() === key.toLowerCase());
  const fact: MemoryFact = {
    id: index >= 0 ? rows[index].id : randomUUID(),
    key,
    value,
    source: typeof input.source === "string" ? input.source.trim().slice(0, 200) : "manual",
    createdAt: index >= 0 ? rows[index].createdAt : now,
    updatedAt: now,
  };
  if (index >= 0) rows[index] = fact;
  else rows.unshift(fact);
  await writeJson(memoryFile, rows.slice(0, 300));
  return fact;
}

export async function updateMemory(
  id: string,
  patch: { key?: string; value?: string; source?: string },
): Promise<MemoryFact> {
  const rows = await listMemory();
  const fact = rows.find((row) => row.id === id);
  if (!fact) throw new HttpError(404, "memory_not_found", "Memory not found.");
  if (patch.key !== undefined) {
    const nextKey = cleanKey(patch.key);
    const collision = rows.find((row) => row.id !== id && row.key.toLowerCase() === nextKey.toLowerCase());
    if (collision) throw new HttpError(409, "memory_key_conflict", "Another memory already uses this label.");
    fact.key = nextKey;
  }
  if (patch.value !== undefined) fact.value = cleanValue(patch.value);
  if (patch.source !== undefined) fact.source = patch.source.trim().slice(0, 200);
  if (!fact.key) throw new HttpError(400, "memory_key_required", "A memory needs a short label.");
  if (!fact.value) throw new HttpError(400, "memory_value_required", "A memory needs a value.");
  fact.updatedAt = Date.now();
  await writeJson(memoryFile, rows);
  return fact;
}

export async function deleteMemory(id: string): Promise<boolean> {
  const rows = await listMemory();
  const next = rows.filter((row) => row.id !== id);
  if (next.length === rows.length) return false;
  await writeJson(memoryFile, next);
  return true;
}

export async function clearMemory(): Promise<void> {
  await writeJson(memoryFile, []);
}

/**
 * A deliberately small, explainable recall step: score facts by shared
 * meaningful tokens. No embeddings, no silent cloud call, no prompt growth
 * when nothing matches.
 */
export async function recallMemory(message: string, limit = 6): Promise<MemoryFact[]> {
  const tokens = tokenize(message);
  if (!tokens.size) return [];
  const rows = await listMemory();
  return rows
    .map((fact) => {
      const factTokens = tokenize(`${fact.key} ${fact.value}`);
      let hits = 0;
      for (const token of factTokens) if (tokens.has(token)) hits += 1;
      return { fact, hits };
    })
    .filter((entry) => entry.hits > 0)
    .sort((a, b) => b.hits - a.hits || b.fact.updatedAt - a.fact.updatedAt)
    .slice(0, limit)
    .map((entry) => entry.fact);
}

function tokenize(text: string): Set<string> {
  return new Set(
    text
      .toLocaleLowerCase()
      .split(/[^\p{L}\p{N}]+/u)
      .map((token) => token.trim())
      .filter((token) => token.length >= 2 && !STOPWORDS.has(token)),
  );
}

const STOPWORDS = new Set([
  "the",
  "and",
  "for",
  "you",
  "your",
  "with",
  "this",
  "that",
  "from",
  "have",
  "are",
  "was",
  "were",
  "will",
  "about",
  "what",
  "when",
  "where",
  "how",
  "why",
  "can",
  "could",
  "should",
  "would",
  "please",
  "hello",
  "thanks",
  "thank",
  "but",
  "not",
  "all",
  "any",
  "our",
  "their",
  "them",
  "they",
  "his",
  "her",
  "its",
  "into",
  "over",
  "under",
  "then",
  "than",
  "there",
  "here",
  "also",
  "just",
  "want",
  "need",
  "است",
  "هست",
  "هستم",
  "هستی",
  "هستیم",
  "هستید",
  "هستند",
  "بود",
  "بودم",
  "بودی",
  "بودیم",
  "بودند",
  "باشد",
  "باشم",
  "باشی",
  "باشیم",
  "باشند",
  "شد",
  "شده",
  "شدم",
  "شدیم",
  "شدند",
  "می",
  "نمی",
  "که",
  "این",
  "آن",
  "را",
  "به",
  "با",
  "از",
  "در",
  "برای",
  "یک",
  "من",
  "تو",
  "او",
  "ما",
  "شما",
  "آنها",
  "و",
  "یا",
  "اگر",
  "اما",
  "ولی",
  "چه",
  "چی",
  "چرا",
  "چطور",
  "چگونه",
  "کجا",
  "کی",
  "هم",
  "نیز",
  "تا",
  "بر",
  "روی",
  "کرد",
  "کردم",
  "کردی",
  "کردیم",
  "کردند",
  "کن",
  "کنم",
  "کنی",
  "کنیم",
  "کنند",
  "کنید",
  "دارم",
  "داری",
  "دارد",
  "داریم",
  "دارید",
  "دارند",
  "گفت",
  "گفتم",
  "بگو",
  "بگویید",
  "لطفا",
  "سلام",
  "ممنون",
]);

function cleanKey(value: unknown): string {
  return typeof value === "string" ? value.replace(/\s+/g, " ").trim().slice(0, 80) : "";
}

function cleanValue(value: unknown): string {
  return typeof value === "string" ? value.trim().slice(0, 1200) : "";
}
