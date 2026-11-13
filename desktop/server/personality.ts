export type Personality = "default" | "concise" | "teacher" | "engineer";

const instructions: Record<Personality, string> = {
  default: "",
  concise: "Communication style: give the direct answer first, keep explanations brief, and ask only necessary follow-up questions.",
  teacher: "Communication style: explain ideas patiently in clear steps and use short examples appropriate to the user's level.",
  engineer: "Communication style: act as a practical engineering collaborator; examine the problem, explain tradeoffs, and provide concrete steps.",
};

export function normalizePersonality(value: unknown): Personality {
  return typeof value === "string" && Object.hasOwn(instructions, value) ? value as Personality : "default";
}

export function applyPersonality(basePrompt: string, value: unknown): string {
  const style = instructions[normalizePersonality(value)];
  return style ? `${basePrompt}\n\n${style}` : basePrompt;
}
