import { spawn } from "node:child_process";
import { closeUiWorker, runUiAction } from "../server/windows-ui.js";

const fixture = spawn("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", ["-NoProfile", "-File", "scripts/windows-ui-list-fixture.ps1"], { windowsHide: true, stdio: "ignore" });
const signal = new AbortController().signal;
try {
  let window: { title: string; windowHandle: number; processId: number } | undefined;
  for (let i = 0; i < 25 && !window; i++) {
    const windows = await runUiAction({ action: "list_windows" }, signal) as { windows: typeof window[] };
    window = windows.windows.find(item => item?.title === "Omni isolated list test");
    if (!window) await new Promise(resolve => setTimeout(resolve, 300));
  }
  if (!window) throw new Error("List fixture did not start");
  let result: { controls: Array<{ name: string; type: string; runtimeId: string; canInvoke: boolean }> } = { controls: [] };
  let item: (typeof result.controls)[number] | undefined;
  for (let i = 0; i < 12 && !item; i++) {
    result = await runUiAction({ action: "read_window", windowHandle: window.windowHandle, processId: window.processId }, signal) as typeof result;
    item = result.controls.find(control => control.name === "Open isolated conversation" && control.type === "ControlType.ListItem");
    if (!item) await new Promise(resolve => setTimeout(resolve, 300));
  }
  if (!item?.canInvoke) throw new Error(`Fixture list item is not reported as activatable: ${JSON.stringify(result.controls)}`);
  await new Promise(resolve => setTimeout(resolve, 1800));
  await runUiAction({ action: "invoke", ...window, windowTitle: window.title, name: item.name, runtimeId: item.runtimeId }, signal);
  const after = await runUiAction({ action: "list_windows" }, signal) as { windows: Array<{ title: string }> };
  if (!after.windows.some(item => item.title === "Omni isolated list opened")) throw new Error("List activation did not open the fixture");
  console.log("Activating an accessible list item opened the isolated fixture.");
} finally { closeUiWorker(); fixture.kill(); }
