import { describe, expect, it } from "vitest";
import { isPromptGuard, isTokenLimitError } from "../src/lib/modelWarnings";

describe("chat model guidance", () => {
  it("identifies the two known Prompt Guard security classifiers without blocking chat models", () => {
    expect(isPromptGuard("meta-llama/llama-prompt-guard-2-22m")).toBe(true);
    expect(isPromptGuard("meta-llama/llama-prompt-guard-2-86m")).toBe(true);
    expect(isPromptGuard("llama-3.3-70b-versatile")).toBe(false);
    expect(isPromptGuard(undefined)).toBe(false);
  });
  it("recognizes the provider token/context rejection in the reported error", () => {
    expect(isTokenLimitError("The provider returned 400. `max_tokens` must be less than or equal to `512`, the maximum value for `max_tokens` is less than the `context_window` for this model")).toBe(true);
    expect(isTokenLimitError("The provider rejected the API key")).toBe(false);
  });
});
