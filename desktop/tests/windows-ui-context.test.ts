import { describe, expect, it } from "vitest";
import { rememberUiResult, resolveUiRequest, type UiContext } from "../server/windows-ui.js";

const controls = [{ name: "Message", type: "ControlType.Edit", runtimeId: "42,1" }];
function observed(options: { truncated?: boolean; query?: string; controls?: typeof controls } = {}): UiContext {
  const context: UiContext = {};
  rememberUiResult(context, { action: "read_window", windowTitle: "Test", query: options.query }, {
    title: "Test", windowHandle: 10, processId: 20, controls: options.controls ?? controls, truncated: options.truncated ?? false,
  });
  return context;
}

describe("computer UI observation context", () => {
  it("uses a recent unique observation when the model omits technical identifiers", () => {
    expect(resolveUiRequest({ action: "type_text", name: "Message", text: "hello" }, observed())).toMatchObject({
      windowHandle: 10, processId: 20, windowTitle: "Test", runtimeId: "42,1",
    });
  });
  it("does not guess from an ambiguous, truncated, filtered, stale, or different window", () => {
    const request = { action: "type_text", name: "Message", text: "hello" };
    expect(() => resolveUiRequest(request, observed({ controls: [...controls, { ...controls[0], runtimeId: "42,2" }] }))).toThrow();
    expect(() => resolveUiRequest(request, observed({ truncated: true }))).toThrow();
    expect(() => resolveUiRequest(request, observed({ query: "Message" }))).toThrow();
    const stale = observed(); stale.lastWindow!.observedAt -= 61_000;
    expect(() => resolveUiRequest(request, stale)).toThrow();
    expect(() => resolveUiRequest({ ...request, windowHandle: 11 }, observed())).toThrow();
  });
  it("invalidates observations after an action that can change the screen", () => {
    const context = observed();
    rememberUiResult(context, { action: "select" }, { ok: true });
    expect(() => resolveUiRequest({ action: "type_text", name: "Message", text: "hello" }, context)).toThrow();
  });
  it("resolves an observed list item for activation", () => {
    const context = observed({ controls: [{ name: "Conversation", type: "ControlType.ListItem", runtimeId: "5,1" }] });
    expect(resolveUiRequest({ action: "invoke", name: "Conversation" }, context)).toMatchObject({ runtimeId: "5,1", windowHandle: 10 });
  });
});
