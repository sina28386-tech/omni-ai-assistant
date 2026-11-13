/** Known security classifiers should not be presented as conversational models. */
export function isPromptGuard(model: string | undefined): boolean {
  return /(?:^|\/)llama-prompt-guard-2-(?:22m|86m)(?::[^/]*)?$/i.test(model?.trim() ?? "");
}

/** Detect a provider's explicit output/context-limit rejection without guessing its limit. */
export function isTokenLimitError(message: string): boolean {
  return /max_tokens/i.test(message) && /context_window|context window|less than or equal|maximum/i.test(message);
}
