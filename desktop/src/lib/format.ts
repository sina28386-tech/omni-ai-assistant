import type { MessageKey } from "./i18n";
import type { ConversationSummary, Language } from "./types";

export function formatNumber(value: number, language: Language): string {
  return new Intl.NumberFormat(language === "fa" ? "fa-IR" : "en-US").format(value);
}

export function formatDate(
  timestamp: number,
  language: Language,
  options: Intl.DateTimeFormatOptions = { month: "short", day: "numeric" },
): string {
  return new Intl.DateTimeFormat(language === "fa" ? "fa-IR" : "en-US", options).format(timestamp);
}

export function formatTime(timestamp: number, language: Language): string {
  return new Intl.DateTimeFormat(language === "fa" ? "fa-IR" : "en-US", {
    hour: "2-digit",
    minute: "2-digit",
  }).format(timestamp);
}

export function relativeTime(
  timestamp: number,
  language: Language,
  t: (key: MessageKey, vars?: Record<string, string | number>) => string,
): string {
  const diff = Math.max(0, Date.now() - timestamp);
  const minute = 60_000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diff < minute) return t("time.justNow");
  if (diff < hour) return t("time.minutes", { count: Math.floor(diff / minute) });
  if (diff < day) return t("time.hours", { count: Math.floor(diff / hour) });
  if (diff < 7 * day) return t("time.days", { count: Math.floor(diff / day) });
  return formatDate(timestamp, language);
}

export function greetingKey(now = new Date()): MessageKey {
  const hour = now.getHours();
  if (hour < 5) return "chat.greetingNight";
  if (hour < 12) return "chat.greetingMorning";
  if (hour < 17) return "chat.greetingAfternoon";
  return "chat.greetingEvening";
}

export type ConversationBucket = "pinned" | "today" | "yesterday" | "previous7" | "older";

export interface ConversationGroup {
  bucket: ConversationBucket;
  items: ConversationSummary[];
}

export function groupConversations(rows: ConversationSummary[]): ConversationGroup[] {
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startOfYesterday = startOfToday - 86_400_000;
  const sevenDaysAgo = startOfToday - 6 * 86_400_000;
  const buckets: Record<ConversationBucket, ConversationSummary[]> = {
    pinned: [],
    today: [],
    yesterday: [],
    previous7: [],
    older: [],
  };
  for (const row of rows) {
    if (row.pinned) buckets.pinned.push(row);
    else if (row.updatedAt >= startOfToday) buckets.today.push(row);
    else if (row.updatedAt >= startOfYesterday) buckets.yesterday.push(row);
    else if (row.updatedAt >= sevenDaysAgo) buckets.previous7.push(row);
    else buckets.older.push(row);
  }
  return (Object.keys(buckets) as ConversationBucket[])
    .filter((bucket) => buckets[bucket].length > 0)
    .map((bucket) => ({ bucket, items: buckets[bucket] }));
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function totalTokens(prompt: number, completion: number): number {
  return (prompt || 0) + (completion || 0);
}

export function truncate(text: string, length: number): string {
  const clean = text.replace(/\s+/g, " ").trim();
  return clean.length > length ? `${clean.slice(0, Math.max(0, length - 1))}…` : clean;
}

export function providerInitial(name: string): string {
  const clean = name.trim();
  return clean ? clean.slice(0, 1).toUpperCase() : "O";
}
