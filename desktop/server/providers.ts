import path from "node:path";
import { randomUUID } from "node:crypto";
import { dataDir, readJson, writeJson } from "./storage.js";
import { HttpError } from "./errors.js";
import type { ProviderRecord, PublicProvider } from "./types.js";

const providersFile = path.join(dataDir, "providers.json");
const secretsFile = path.join(dataDir, "secrets.json");

type Secrets = Record<string, string>;

export async function listProviders(): Promise<ProviderRecord[]> {
  const rows = await readJson<ProviderRecord[]>(providersFile, []);
  return rows.filter(isProvider).sort((a, b) => a.createdAt - b.createdAt);
}

export async function getProvider(id: string): Promise<ProviderRecord | null> {
  const rows = await listProviders();
  return rows.find((row) => row.id === id) ?? null;
}

export async function createProvider(input: Partial<ProviderRecord> & { apiKey?: string }): Promise<PublicProvider> {
  const now = Date.now();
  const record: ProviderRecord = {
    id: randomUUID(),
    kind: input.kind === "codex" ? "codex" : "api",
    name: cleanName(input.name) || "New provider",
    baseUrl: validateBaseUrl(input.baseUrl ?? "https://api.openai.com/v1"),
    model: cleanModel(input.model) || "gpt-4.1-mini",
    enabled: input.enabled !== false,
    temperature: clampNumber(input.temperature, 0, 2, 0.7),
    maxTokens: Math.round(clampNumber(input.maxTokens, 64, 32000, 2048)),
    createdAt: now,
    updatedAt: now,
  };
  const rows = await listProviders();
  rows.push(record);
  await writeJson(providersFile, rows);
  if (typeof input.apiKey === "string" && input.apiKey.trim()) {
    await setProviderKey(record.id, input.apiKey);
  }
  return toPublic(record, Boolean(input.apiKey?.trim()));
}

export async function updateProvider(
  id: string,
  patch: Partial<ProviderRecord> & { apiKey?: string },
): Promise<PublicProvider | null> {
  const rows = await listProviders();
  const index = rows.findIndex((row) => row.id === id);
  if (index < 0) return null;
  const current = rows[index];
  const next: ProviderRecord = {
    ...current,
    name: patch.name !== undefined ? cleanName(patch.name) || current.name : current.name,
    baseUrl: patch.baseUrl !== undefined ? validateBaseUrl(patch.baseUrl) : current.baseUrl,
    model: patch.model !== undefined ? cleanModel(patch.model) || current.model : current.model,
    enabled: patch.enabled !== undefined ? patch.enabled !== false : current.enabled,
    temperature: patch.temperature !== undefined ? clampNumber(patch.temperature, 0, 2, current.temperature) : current.temperature,
    maxTokens:
      patch.maxTokens !== undefined ? Math.round(clampNumber(patch.maxTokens, 64, 32000, current.maxTokens)) : current.maxTokens,
    updatedAt: Date.now(),
  };
  rows[index] = next;
  await writeJson(providersFile, rows);
  if (typeof patch.apiKey === "string") {
    if (patch.apiKey.trim()) await setProviderKey(id, patch.apiKey);
    else await deleteProviderKey(id);
  }
  return toPublic(next, await hasProviderKey(id));
}

export async function deleteProvider(id: string): Promise<boolean> {
  const rows = await listProviders();
  const next = rows.filter((row) => row.id !== id);
  if (next.length === rows.length) return false;
  await writeJson(providersFile, next);
  await deleteProviderKey(id);
  return true;
}

export async function getProviderKey(id: string): Promise<string> {
  const secrets = await readJson<Secrets>(secretsFile, {});
  return typeof secrets[id] === "string" ? secrets[id] : "";
}

export async function hasProviderKey(id: string): Promise<boolean> {
  return Boolean(await getProviderKey(id));
}

export async function setProviderKey(id: string, key: string): Promise<void> {
  const secrets = await readJson<Secrets>(secretsFile, {});
  secrets[id] = key.trim();
  await writeJson(secretsFile, secrets);
}

export async function deleteProviderKey(id: string): Promise<void> {
  const secrets = await readJson<Secrets>(secretsFile, {});
  if (!(id in secrets)) return;
  delete secrets[id];
  await writeJson(secretsFile, secrets);
}

export function toPublic(record: ProviderRecord, hasKey = false): PublicProvider {
  return { ...record, hasKey, keyHint: hasKey ? "saved" : "" };
}

export async function publicProviders(): Promise<PublicProvider[]> {
  const rows = await listProviders();
  const secrets = await readJson<Secrets>(secretsFile, {});
  return rows.map((row) => toPublic(row, Boolean(secrets[row.id])));
}

export function validateBaseUrl(input: string): string {
  let raw = (input ?? "").trim();
  if (!raw) throw new HttpError(400, "provider_url_required", "A base URL is required.");
  const hasScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(raw);
  if (hasScheme && !/^https?:\/\//i.test(raw)) {
    throw new HttpError(400, "provider_url_protocol", "Only http and https providers are supported.");
  }
  if (!hasScheme) raw = `https://${raw}`;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new HttpError(400, "provider_url_invalid", "The provider URL is not valid.");
  }
  if (url.username || url.password) {
    throw new HttpError(400, "provider_url_credentials", "Do not put credentials inside the URL.");
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new HttpError(400, "provider_url_protocol", "Only http and https providers are supported.");
  }
  if (url.protocol === "http:" && !isPrivateHost(url.hostname)) {
    throw new HttpError(
      400,
      "provider_url_cleartext",
      "Plain HTTP is only allowed for local or private hosts. Use HTTPS for a public provider.",
    );
  }
  url.hash = "";
  return url.toString().replace(/\/+$/, "");
}

export function chatEndpoint(baseUrl: string): string {
  const clean = baseUrl.replace(/\/+$/, "");
  if (/\/chat\/completions$/i.test(clean)) return clean;
  return `${clean}/chat/completions`;
}

export function modelsEndpoint(baseUrl: string): string {
  const clean = baseUrl.replace(/\/+$/, "");
  if (/\/chat\/completions$/i.test(clean)) return clean.replace(/\/chat\/completions$/i, "/models");
  return `${clean}/models`;
}

export function isPrivateHost(hostname: string): boolean {
  const host = hostname.toLowerCase();
  if (host === "localhost" || host === "::1" || host.endsWith(".local")) return true;
  if (host === "127.0.0.1" || host.startsWith("127.")) return true;
  if (host.startsWith("10.")) return true;
  if (host.startsWith("192.168.")) return true;
  const match = /^172\.(\d{1,2})\./.exec(host);
  if (match) {
    const second = Number(match[1]);
    return second >= 16 && second <= 31;
  }
  return false;
}

function cleanName(value: unknown): string {
  return typeof value === "string" ? value.replace(/\s+/g, " ").trim().slice(0, 80) : "";
}

function cleanModel(value: unknown): string {
  return typeof value === "string" ? value.trim().slice(0, 160) : "";
}

function clampNumber(value: unknown, min: number, max: number, fallback: number): number {
  const n = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, n));
}

function isProvider(value: unknown): value is ProviderRecord {
  if (!value || typeof value !== "object") return false;
  const row = value as ProviderRecord;
  return typeof row.id === "string" && typeof row.baseUrl === "string" && typeof row.model === "string";
}
