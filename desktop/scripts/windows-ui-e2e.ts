import { spawn } from "node:child_process";
import { closeUiWorker, runUiAction } from "../server/windows-ui.js";

const powershell = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
const fixture = spawn(powershell, ["-NoProfile", "-File", "scripts/windows-ui-fixture.ps1"], { stdio: "ignore", windowsHide: true });
const signal = new AbortController().signal;
const pause = () => new Promise(resolve => setTimeout(resolve, 500));
try {
  let window: { title: string; windowHandle: number; processId: number } | undefined;
  for (let i = 0; i < 30 && !window; i++) {
    const result = await runUiAction({ action: "list_windows" }, signal) as { windows: typeof window[] };
    window = result.windows.find(row => row?.title === "Omni isolated UI test window");
    if (!window) await pause();
  }
  if (!window) throw new Error("Test window not visible to UI Automation");
  let result: { controls: Array<{ name: string; type: string; runtimeId: string }> } = { controls: [] };
  let edit; let button;
  for (let i = 0; i < 12 && (!edit || !button); i++) {
    result = await runUiAction({ action: "read_window", windowHandle: window.windowHandle, processId: window.processId }, signal) as typeof result;
    edit = result.controls.find(row => row.type === "ControlType.Edit" && row.name === "Omni test input");
    button = result.controls.find(row => row.type === "ControlType.Button" && row.name === "Omni test button");
    if (!edit || !button) await pause();
  }
  if (!edit || !button) throw new Error(`Fixture controls not found: ${JSON.stringify(result.controls)}`);
  const base = { windowHandle: window.windowHandle, processId: window.processId, windowTitle: window.title };
  await new Promise(resolve => setTimeout(resolve, 1800));
  await runUiAction({ action: "type_text", ...base, name: edit.name, runtimeId: edit.runtimeId, text: "آزمایش Omni" }, signal);
  console.log("Fixture text entered.");
  await new Promise(resolve => setTimeout(resolve, 1800));
  await runUiAction({ action: "invoke", ...base, name: button.name, runtimeId: button.runtimeId }, signal);
  const after = await runUiAction({ action: "list_windows" }, signal) as { windows: Array<{ title: string }> };
  if (!after.windows.some(row => row.title === "Omni isolated UI test completed")) throw new Error("Button did not run");
  console.log("Isolated Windows window, editable control and button interaction passed.");
} finally { closeUiWorker(); fixture.kill(); }
