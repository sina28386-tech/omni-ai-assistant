/** A clear opt-in phrase only; ordinary conversation is never silently saved. */
export function explicitMemory(text: string): string | null {
  const match = /^\s*(?:به\s+خاطر\s+بسپار|یادت\s+بمونه|remember)\s*[:：]\s*([\s\S]+?)\s*$/iu.exec(text);
  const value = match?.[1]?.trim();
  return value && value.length <= 1200 ? value : null;
}
