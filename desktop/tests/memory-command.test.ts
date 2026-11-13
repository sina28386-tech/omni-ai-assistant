import { describe, expect, it } from "vitest";
import { explicitMemory } from "../server/memory-command.js";

describe("explicit learning in chat", () => {
  it("recognizes a direct Persian or English request to remember", () => {
    expect(explicitMemory("به خاطر بسپار: من پاسخ کوتاه دوست دارم")).toBe("من پاسخ کوتاه دوست دارم");
    expect(explicitMemory("Remember: I prefer dark mode")).toBe("I prefer dark mode");
  });
  it("does not silently learn ordinary conversation or blank requests", () => {
    expect(explicitMemory("Do you remember my name?")).toBeNull();
    expect(explicitMemory("به خاطر بسپار:")).toBeNull();
    expect(explicitMemory("Remember: " + "x".repeat(1201))).toBeNull();
  });
});
