import { describe, expect, it } from "vitest";
import { UiProgressGuard, UiStalledError } from "../server/ui-progress.js";

describe("computer control progress", () => {
  it("stops repeated identical observations without an action", () => {
    const guard = new UiProgressGuard();
    const result = { windowHandle: 12, controls: [{ runtimeId: "1,2" }] };
    guard.success({ action: "read_window" }, result);
    guard.success({ action: "read_window" }, result);
    expect(() => guard.success({ action: "read_window" }, result)).toThrow(UiStalledError);
  });
  it("stops after three failures even if intervening reads succeed", () => {
    const guard = new UiProgressGuard();
    guard.failure(); guard.success({ action: "read_window" }, { controls: [1] });
    guard.failure(); guard.success({ action: "read_window" }, { controls: [2] });
    expect(() => guard.failure()).toThrow(UiStalledError);
  });
  it("lets a successful operation reset the loop counter", () => {
    const guard = new UiProgressGuard();
    guard.failure(); guard.failure();
    guard.success({ action: "invoke" }, { ok: true });
    guard.failure(); guard.failure();
    expect(() => guard.success({ action: "read_window" }, { controls: [] })).not.toThrow();
  });
});
