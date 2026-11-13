import { describe, expect, it } from "vitest";
import os from "node:os";
import { runSystemCommand } from "../server/system-command.js";

describe("Windows command execution", () => {
  it("rejects execution without approval and before starting a process", async () => {
    await expect(runSystemCommand("powershell", "Write-Output 'hello'", os.tmpdir(), false, new AbortController().signal)).rejects.toMatchObject({ code: "command_approval_required" });
  });
  it.runIf(process.platform === "win32")("runs a harmless explicitly approved command and reports real output", async () => {
    const result = await runSystemCommand("powershell", "[Console]::Write('omni-command-ok')", os.tmpdir(), true, new AbortController().signal);
    expect(result.ok).toBe(true);
    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain("omni-command-ok");
  });
  it.runIf(process.platform === "win32")("runs an approved CMD command and reports its exit code", async () => {
    const result = await runSystemCommand("cmd", "echo omni-cmd-ok", os.tmpdir(), true, new AbortController().signal);
    expect(result.ok).toBe(true);
    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain("omni-cmd-ok");
  });
  it("rejects an already canceled command", async () => {
    const controller = new AbortController(); controller.abort();
    await expect(runSystemCommand("powershell", "Write-Output 'hello'", os.tmpdir(), true, controller.signal)).rejects.toMatchObject({ name: "AbortError" });
  });
  it.runIf(process.platform === "win32")("terminates a running command when canceled", async () => {
    const controller = new AbortController();
    const pending = runSystemCommand("powershell", "Start-Sleep -Seconds 20", os.tmpdir(), true, controller.signal);
    setTimeout(() => controller.abort(), 1000);
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  }, 15_000);
  it("rejects invisible direction controls in a command", async () => {
    await expect(runSystemCommand("powershell", "Write-Output '\u202eevil'", os.tmpdir(), true, new AbortController().signal)).rejects.toMatchObject({ code: "command_invalid" });
  });
});
