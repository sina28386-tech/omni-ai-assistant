import { createHash } from "node:crypto";
import type { UiRequest } from "./windows-ui.js";

/** Stops unproductive UI loops without saving window contents or counting approval wait time. */
export class UiStalledError extends Error {
  constructor() { super("Computer control stopped after repeated observations or failures without progress. No further action was attempted; inspect the app or provide a clearer target."); this.name = "UiStalledError"; }
}

export class UiProgressGuard {
  private lastSnapshot = "";
  private sameReads = 0;
  private reads = 0;
  private failures = 0;
  success(request: UiRequest, result: unknown): void {
    if (request.action === "read_window") {
      const snapshot = createHash("sha256").update(JSON.stringify(result)).digest("hex");
      this.sameReads = snapshot === this.lastSnapshot ? this.sameReads + 1 : 1;
      this.lastSnapshot = snapshot;
      this.reads++;
      if (this.sameReads >= 3 || this.reads >= 6) throw new UiStalledError();
    } else if (["type_text", "invoke", "select", "open_app"].includes(request.action)) {
      this.lastSnapshot = ""; this.sameReads = 0; this.reads = 0; this.failures = 0;
    }
  }
  failure(): void { if (++this.failures >= 3) throw new UiStalledError(); }
}
