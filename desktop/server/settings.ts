import path from "node:path";
import { dataDir, readJson, writeJson } from "./storage.js";
import type { Settings } from "./types.js";
import { normalizePersonality } from "./personality.js";

const settingsFile = path.join(dataDir, "settings.json");

export const DEFAULT_SYSTEM_PROMPT = [
  "You are Omni, a calm and precise personal AI assistant.",
  "Answer in the language the user writes in.",
  "Be direct. Prefer short paragraphs and concrete steps.",
  "Never claim to have performed an action you did not perform.",
  "If a request needs a capability this client does not have, say so plainly and offer the closest alternative.",
].join(" ");

export function defaultSettings(): Settings {
  const now = Date.now();
  return {
    language: "fa",
    theme: "dark",
    density: "comfortable",
    mode: "simple",
    sendOnEnter: true,
    autoLowRisk: false,
    showInspector: true,
    systemPrompt: DEFAULT_SYSTEM_PROMPT,
    personality: "default",
    activeProviderId: "",
    createdAt: now,
    updatedAt: now,
  };
}

export async function getSettings(): Promise<Settings> {
  const raw = await readJson<Partial<Settings> | null>(settingsFile, null);
  if (!raw) {
    const fresh = defaultSettings();
    await writeJson(settingsFile, fresh);
    return fresh;
  }
  return sanitizeSettings({ ...defaultSettings(), ...raw });
}

export async function updateSettings(patch: Partial<Settings>): Promise<Settings> {
  const current = await getSettings();
  const next = sanitizeSettings({ ...current, ...patch, updatedAt: Date.now() });
  await writeJson(settingsFile, next);
  return next;
}

function sanitizeSettings(value: Settings): Settings {
  const language = value.language === "en" ? "en" : "fa";
  const theme = value.theme === "light" || value.theme === "system" ? value.theme : "dark";
  const density = value.density === "compact" ? "compact" : "comfortable";
  const mode = value.mode === "studio" ? "studio" : "simple";
  const prompt = typeof value.systemPrompt === "string" ? value.systemPrompt.trim().slice(0, 8000) : "";
  return {
    language,
    theme,
    density,
    mode,
    sendOnEnter: value.sendOnEnter !== false,
    autoLowRisk: value.autoLowRisk === true,
    showInspector: value.showInspector !== false,
    systemPrompt: prompt || DEFAULT_SYSTEM_PROMPT,
    personality: normalizePersonality(value.personality),
    activeProviderId: typeof value.activeProviderId === "string" ? value.activeProviderId.slice(0, 80) : "",
    createdAt: Number.isFinite(value.createdAt) ? value.createdAt : Date.now(),
    updatedAt: Number.isFinite(value.updatedAt) ? value.updatedAt : Date.now(),
  };
}
