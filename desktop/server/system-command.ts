import { spawn } from "node:child_process";
import path from "node:path";
import { HttpError } from "./errors.js";

export type CommandShell = "powershell" | "cmd";
export interface CommandResult { ok: boolean; exitCode: number | null; stdout: string; stderr: string; truncated: boolean; timedOut: boolean }

export function validateSystemCommand(shell: unknown, command: unknown): asserts shell is CommandShell {
  if ((shell !== "powershell" && shell !== "cmd") || typeof command !== "string" || !command.trim() || command.length > 8192 || /[\0\u202a-\u202e\u2066-\u2069]/u.test(command)) {
    throw new HttpError(400, "command_invalid", "Choose PowerShell or CMD and provide a visible command of at most 8192 characters.");
  }
}

/** An approved command runs with this Windows user's permissions; this is not a sandbox. */
export async function runSystemCommand(shell: CommandShell, command: string, cwd: string, approved: boolean, signal: AbortSignal): Promise<CommandResult> {
  if (approved !== true) throw new HttpError(403, "command_approval_required", "Approve this exact command before running it.");
  if (process.platform !== "win32") throw new HttpError(400, "command_platform", "Windows commands require Windows.");
  validateSystemCommand(shell, command);
  return executeCommand(shell, command, cwd, signal);
}

/** For application-owned fixed scripts only; never pass model-generated PowerShell here. */
export async function runFixedPowerShell(command: string, cwd: string, signal: AbortSignal): Promise<CommandResult> {
  if (process.platform !== "win32") throw new HttpError(400, "command_platform", "Windows commands require Windows.");
  return executeCommand("powershell", command, cwd, signal);
}

async function executeCommand(shell: CommandShell, command: string, cwd: string, signal: AbortSignal): Promise<CommandResult> {
  signal.throwIfAborted();
  const systemRoot = process.env.SystemRoot || "C:\\Windows";
  const exe = shell === "powershell" ? path.join(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe") : path.join(systemRoot, "System32", "cmd.exe");
  const args = shell === "powershell" ? ["-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", Buffer.from(command, "utf16le").toString("base64")] : ["/d", "/s", "/c", command];
  return new Promise((resolve, reject) => {
    const child = spawn(exe, args, { cwd, windowsHide: true, shell: false, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "", stderr = "", truncated = false, timedOut = false, stopped = false;
    const append = (part: Buffer, which: "stdout" | "stderr") => {
      const value = part.toString("utf8");
      const remaining = Math.max(0, 32_000 - stdout.length - stderr.length);
      if (value.length > remaining) truncated = true;
      if (which === "stdout") stdout += value.slice(0, remaining); else stderr += value.slice(0, remaining);
    };
    child.stdout.on("data", part => append(part, "stdout"));
    child.stderr.on("data", part => append(part, "stderr"));
    const terminate = () => {
      if (stopped || !child.pid) return;
      stopped = true;
      // Terminate the normal child tree; independently detached processes may survive.
      const killer = spawn(path.join(systemRoot, "System32", "taskkill.exe"), ["/PID", String(child.pid), "/T", "/F"], { windowsHide: true, stdio: "ignore" });
      killer.on("error", () => child.kill());
      killer.on("exit", code => { if (code !== 0 && child.exitCode === null) child.kill(); });
    };
    const timer = setTimeout(() => { timedOut = true; terminate(); }, 30_000);
    signal.addEventListener("abort", terminate, { once: true });
    child.once("error", error => { clearTimeout(timer); signal.removeEventListener("abort", terminate); reject(error); });
    child.once("close", code => {
      clearTimeout(timer); signal.removeEventListener("abort", terminate);
      if (signal.aborted) { reject(signal.reason); return; }
      resolve({ ok: code === 0 && !timedOut, exitCode: code, stdout, stderr, truncated, timedOut });
    });
    if (signal.aborted) terminate();
  });
}
