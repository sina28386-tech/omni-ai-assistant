import { describe, expect, it } from "vitest";
import { applyPersonality, normalizePersonality } from "../server/personality.js";

describe("assistant personality", () => {
  it("preserves the existing system instructions and adds the selected style", () => {
    expect(applyPersonality("My existing instructions", "default")).toBe("My existing instructions");
    expect(applyPersonality("My existing instructions", "engineer")).toContain("My existing instructions\n\nCommunication style:");
  });
  it("falls back to the existing behavior for unknown or inherited values", () => {
    expect(normalizePersonality("__proto__")).toBe("default");
    expect(normalizePersonality(undefined)).toBe("default");
    expect(applyPersonality("Keep this", "unknown")).toBe("Keep this");
  });
});
