import { HttpError } from "./errors.js";
import { runFixedPowerShell } from "./system-command.js";
import os from "node:os";
import path from "node:path";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import readline from "node:readline";

export type UiAction = "list_apps" | "open_app" | "list_windows" | "read_window" | "type_text" | "invoke" | "select";
export interface UiRequest { action: UiAction; query?: string; appId?: string; windowHandle?: number; windowTitle?: string; processId?: number; runtimeId?: string; name?: string; text?: string; overwrite?: boolean }
export interface UiContext { windows?: Array<{ title: string; windowHandle: number; processId: number }>; lastWindow?: { title: string; windowHandle: number; processId: number; controls: Array<{ name: string; type: string; runtimeId: string }>; complete: boolean; observedAt: number } }

/** Only fixed UI Automation operations are supported; model strings are JSON data, never script. */
export function validateUiRequest(input: unknown): UiRequest {
  if (!input || typeof input !== "object" || Array.isArray(input)) throw new HttpError(400, "ui_invalid", "Invalid computer action.");
  const a = input as Record<string, unknown>;
  if (!["list_apps", "open_app", "list_windows", "read_window", "type_text", "invoke", "select"].includes(String(a.action))) throw new HttpError(400, "ui_invalid", "Unsupported computer action.");
  for (const key of ["query", "appId", "runtimeId", "name", "windowTitle", "text"]) if (a[key] !== undefined && (typeof a[key] !== "string" || (a[key] as string).length > (key === "text" ? 1000 : 512) || /[\0\u202a-\u202e\u2066-\u2069]/u.test(a[key] as string))) throw new HttpError(400, "ui_invalid", "Invalid action text.");
  if (a.action === "list_apps" && (typeof a.query !== "string" || !a.query.trim())) throw new HttpError(400, "ui_invalid", "Provide an app name to search.");
  if (a.action === "open_app" && (typeof a.appId !== "string" || !a.appId.trim())) throw new HttpError(400, "ui_invalid", "Choose an installed app.");
  const hasIdentity = Number.isSafeInteger(a.windowHandle) && Number(a.windowHandle) > 0 && Number.isSafeInteger(a.processId) && Number(a.processId) > 0;
  if (a.action === "read_window" && !hasIdentity && (typeof a.windowTitle !== "string" || a.windowTitle.trim().length < 2)) throw new HttpError(400, "ui_invalid", "For read_window provide windowTitle, or windowHandle and processId from list_windows.");
  if (["type_text", "invoke", "select"].includes(String(a.action)) && !hasIdentity) throw new HttpError(400, "ui_invalid", "Read the target window first; then use its windowHandle and processId.");
  if (["type_text", "invoke", "select"].includes(String(a.action)) && (typeof a.windowTitle !== "string" || !a.windowTitle.trim())) throw new HttpError(400, "ui_invalid", "Provide the exact window title.");
  if (["type_text", "invoke", "select"].includes(String(a.action)) && (typeof a.runtimeId !== "string" || !/^-?\d+(?:,-?\d+)*$/.test(a.runtimeId) || typeof a.name !== "string")) throw new HttpError(400, "ui_invalid", "Read the window first and choose an unambiguous control.");
  if (a.action === "type_text" && typeof a.text !== "string") throw new HttpError(400, "ui_invalid", "Provide text to type.");
  if (a.overwrite !== undefined && typeof a.overwrite !== "boolean") throw new HttpError(400, "ui_invalid", "Invalid overwrite option.");
  return a as unknown as UiRequest;
}

/** Fill in only identities from this run's recent, unambiguous observations. */
export function resolveUiRequest(input: unknown, context: UiContext): UiRequest {
  if (!input || typeof input !== "object" || Array.isArray(input)) return validateUiRequest(input);
  const a = { ...(input as Record<string, unknown>) };
  for (const field of ["windowHandle", "processId"]) if (typeof a[field] === "string" && /^\d+$/.test(a[field] as string)) a[field] = Number(a[field]);
  const recent = context.lastWindow && Date.now() - context.lastWindow.observedAt <= 60_000 ? context.lastWindow : undefined;
  if (["type_text", "invoke", "select"].includes(String(a.action)) && recent &&
      (a.windowHandle === undefined || a.windowHandle === recent.windowHandle) &&
      (a.processId === undefined || a.processId === recent.processId) &&
      (a.windowTitle === undefined || a.windowTitle === recent.title)) {
    a.windowHandle ??= recent.windowHandle; a.processId ??= recent.processId; a.windowTitle ??= recent.title;
    const allowed = a.action === "type_text" ? ["ControlType.Edit"] : a.action === "invoke" ? ["ControlType.Button", "ControlType.ListItem", "ControlType.DataItem", "ControlType.TreeItem", "ControlType.TabItem"] : ["ControlType.ListItem", "ControlType.DataItem", "ControlType.TreeItem", "ControlType.TabItem"];
    const candidates = recent.controls.filter(c => allowed.includes(c.type) && (a.runtimeId === undefined || c.runtimeId === a.runtimeId) && (a.name === undefined || c.name === a.name));
    if (candidates.length === 1 && (recent.complete || a.runtimeId !== undefined)) { a.runtimeId ??= candidates[0].runtimeId; a.name ??= candidates[0].name; }
  }
  if (a.action === "read_window" && (a.windowHandle === undefined || a.processId === undefined) && typeof a.windowTitle === "string") {
    const matches = (context.windows ?? []).filter(w => w.title.toLocaleLowerCase() === (a.windowTitle as string).toLocaleLowerCase());
    if (matches.length === 1) { a.windowHandle ??= matches[0].windowHandle; a.processId ??= matches[0].processId; }
  }
  return validateUiRequest(a);
}

export function rememberUiResult(context: UiContext, request: UiRequest, result: unknown): void {
  if (!result || typeof result !== "object") return;
  const value = result as Record<string, unknown>;
  if (request.action === "list_windows" && Array.isArray(value.windows)) context.windows = value.windows as UiContext["windows"];
  if (request.action === "read_window" && typeof value.title === "string" && Number.isSafeInteger(value.windowHandle) && Number.isSafeInteger(value.processId) && Array.isArray(value.controls)) {
    context.lastWindow = { title: value.title, windowHandle: value.windowHandle as number, processId: value.processId as number, controls: value.controls as NonNullable<UiContext["lastWindow"]>["controls"], complete: value.truncated === false && !request.query, observedAt: Date.now() };
  }
  if (request.action === "select" || request.action === "invoke") delete context.lastWindow;
}

const script = String.raw`
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class OmniInputActivity {
  [StructLayout(LayoutKind.Sequential)] public struct LastInput { public uint size; public uint tick; }
  [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LastInput input);
  [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
  [DllImport("kernel32.dll")] static extern uint GetTickCount();
  public static bool IsActive(uint targetPid, uint thresholdMs) {
    uint current; GetWindowThreadProcessId(GetForegroundWindow(), out current);
    if (current != targetPid) return false;
    var input = new LastInput { size = (uint)Marshal.SizeOf(typeof(LastInput)) };
    if (!GetLastInputInfo(ref input)) return true;
    return unchecked(GetTickCount() - input.tick) < thresholdMs;
  }
}
'@
function result($value) { [Console]::Out.WriteLine(($value | ConvertTo-Json -Depth 6 -Compress)) }
function Invoke-OmniAction($a) {
if ($a.action -eq 'list_apps') {
  $items = @(Get-StartApps | Where-Object { $_.Name -like ('*' + $a.query + '*') } | Select-Object -First 50 Name,AppID)
  result @{ ok=$true; apps=$items }; return
}
if ($a.action -eq 'open_app') {
  $matches = @(Get-StartApps | Where-Object { $_.AppID -ceq $a.appId })
  if ($matches.Count -ne 1) { throw 'Installed app not found or ambiguous.' }
  Start-Process -FilePath explorer.exe -ArgumentList ('shell:AppsFolder\' + $matches[0].AppID) | Out-Null
  result @{ok=$true; dispatched=$true; app=$matches[0].Name; note='Launch request dispatched; verify the window before claiming it opened.'}; return
}
$root = [System.Windows.Automation.AutomationElement]::RootElement
if ($a.action -eq 'list_windows') {
  $windows = $root.FindAll([System.Windows.Automation.TreeScope]::Children,[System.Windows.Automation.Condition]::TrueCondition)
  $items = @(); foreach($w in $windows) { try { $c=$w.Current; if($c.Name -and !$c.IsOffscreen -and $c.NativeWindowHandle -gt 0) { $items += @{title=$c.Name; windowHandle=$c.NativeWindowHandle; processId=$c.ProcessId} } } catch {} }
  result @{ok=$true; windows=@($items | Select-Object -First 100)}; return
}
$handle=[int]$a.windowHandle; $processId=[int]$a.processId
if ($a.action -eq 'read_window' -and (!$handle -or !$processId)) {
  $all=$root.FindAll([System.Windows.Automation.TreeScope]::Children,[System.Windows.Automation.Condition]::TrueCondition)
  $exact=@(); $partial=@(); foreach($w in $all) { try { $c=$w.Current; if(!$c.IsOffscreen -and $c.NativeWindowHandle -gt 0 -and $c.Name) { if($c.Name -ieq $a.windowTitle) { $exact += $w } elseif($c.Name.IndexOf([string]$a.windowTitle,[StringComparison]::OrdinalIgnoreCase) -ge 0) { $partial += $w } } } catch {} }
  $candidates=if($exact.Count -gt 0){$exact}else{$partial}
  if($candidates.Count -ne 1){throw 'Window title is missing or ambiguous. Use list_windows to choose an exact window.'}
  $handle=$candidates[0].Current.NativeWindowHandle; $processId=$candidates[0].Current.ProcessId
}
$window = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]::new($handle))
if (!$window -or $window.Current.ProcessId -ne $processId -or $window.Current.IsOffscreen) { throw 'The window changed or is no longer visible.' }
if ($a.action -ne 'read_window' -and $window.Current.Name -cne $a.windowTitle) { throw 'The window title changed; inspect the window again.' }
$cache=New-Object System.Windows.Automation.CacheRequest
foreach($property in @([System.Windows.Automation.AutomationElement]::NameProperty,[System.Windows.Automation.AutomationElement]::ControlTypeProperty,[System.Windows.Automation.AutomationElement]::IsOffscreenProperty,[System.Windows.Automation.AutomationElement]::IsEnabledProperty,[System.Windows.Automation.AutomationElement]::IsPasswordProperty,[System.Windows.Automation.AutomationElement]::RuntimeIdProperty,[System.Windows.Automation.AutomationElement]::IsInvokePatternAvailableProperty,[System.Windows.Automation.AutomationElement]::IsSelectionItemPatternAvailableProperty)) { $cache.Add($property) }
$cache.Push()
try { $nodes = $window.FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.Condition]::TrueCondition) } finally { $cache.Pop() }
if ($nodes.Count -gt 5000) { throw 'This window has too many controls to inspect safely.' }
if ($a.action -eq 'read_window') {
  $items=@(); foreach($node in $nodes) { try { $c=$node.Cached; $name=[string]$c.Name; if(!$c.IsOffscreen -and !$c.IsPassword -and $c.IsEnabled -and $name.Length -le 512 -and ($name -or $c.ControlType -eq [System.Windows.Automation.ControlType]::Edit) -and (!$a.query -or $name.IndexOf([string]$a.query,[StringComparison]::OrdinalIgnoreCase) -ge 0)) { $items += @{name=$name; type=$c.ControlType.ProgrammaticName; runtimeId=($node.GetCachedPropertyValue([System.Windows.Automation.AutomationElement]::RuntimeIdProperty) -join ','); canInvoke=[bool]$node.GetCachedPropertyValue([System.Windows.Automation.AutomationElement]::IsInvokePatternAvailableProperty); canSelect=[bool]$node.GetCachedPropertyValue([System.Windows.Automation.AutomationElement]::IsSelectionItemPatternAvailableProperty)} } } catch {} }
  result @{ok=$true; title=$window.Current.Name; windowHandle=$handle; processId=$processId; controls=@($items | Select-Object -First 200); truncated=($items.Count -gt 200)}; return
}
if([OmniInputActivity]::IsActive([uint32]$processId,1500)){ throw 'The user is actively using this application. Wait until they pause, then read the window again.' }
$matching=@(); foreach($node in $nodes) { try { if(($node.GetCachedPropertyValue([System.Windows.Automation.AutomationElement]::RuntimeIdProperty) -join ',') -ceq $a.runtimeId) { $matching += $node } } catch {} }
if ($matching.Count -ne 1) { throw 'The control changed; inspect the window again.' }
$element=$matching[0]; $current=$element.Current
if ($current.IsOffscreen -or !$current.IsEnabled -or $current.IsPassword -or [string]$current.Name -cne [string]$a.name) { throw 'The control changed, is protected, or is not available.' }
if ($a.action -eq 'type_text') {
  if ($current.ControlType -ne [System.Windows.Automation.ControlType]::Edit) { throw 'Only editable text fields can be used.' }
  $value=$element.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
  if ($value.Current.IsReadOnly) { throw 'The field is read-only.' }
  if ($value.Current.Value -and $value.Current.Value -cne [string]$a.text -and $a.overwrite -ne $true) { throw 'The field already contains text. Ask before overwriting; use overwrite=true only for an approved replacement.' }
  $value.SetValue([string]$a.text)
  result @{ok=$true; action='type_text'; characters=([string]$a.text).Length}; return
}
if ($a.action -eq 'invoke') {
  if ($current.ControlType -notin @([System.Windows.Automation.ControlType]::Button,[System.Windows.Automation.ControlType]::ListItem,[System.Windows.Automation.ControlType]::DataItem,[System.Windows.Automation.ControlType]::TreeItem,[System.Windows.Automation.ControlType]::TabItem)) { throw 'Only supported buttons and list, tree, or tab items can be activated.' }
  $invoke=$null
  if (!$element.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern,[ref]$invoke)) { throw 'This control does not support activation. Do not repeatedly retry; explain the limitation.' }
  $invoke.Invoke()
  result @{ok=$true; action='invoke'; name=$current.Name; note='Control activated; verify the resulting screen before claiming completion or delivery.'}; return
}
if ($a.action -eq 'select') {
  if ($current.ControlType -notin @([System.Windows.Automation.ControlType]::ListItem,[System.Windows.Automation.ControlType]::DataItem,[System.Windows.Automation.ControlType]::TreeItem,[System.Windows.Automation.ControlType]::TabItem)) { throw 'Only selectable list, tree, or tab items are supported.' }
  $selection=$element.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern); $selection.Select()
  result @{ok=$true; action='select'; name=$current.Name; note='Item selected; verify the resulting window.'}; return
}
throw 'Unsupported computer action.'
}
[Console]::Out.WriteLine('{"ready":true}')
while ($null -ne ($line=[Console]::In.ReadLine())) {
  try { Invoke-OmniAction (ConvertFrom-Json $line) } catch { result @{ok=$false; error=$_.Exception.Message} }
}
`;

class UiWorker {
  private readonly child: ChildProcessWithoutNullStreams;
  private readyResolve!: () => void;
  private readyReject!: (error: Error) => void;
  readonly ready = new Promise<void>((resolve, reject) => { this.readyResolve = resolve; this.readyReject = reject; });
  private pending?: { resolve: (value: unknown) => void; reject: (error: Error) => void };
  private closed = false;
  private idle?: NodeJS.Timeout;
  constructor() {
    const exe = path.join(process.env.SystemRoot || "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    this.child = spawn(exe, ["-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", Buffer.from(script, "utf16le").toString("base64")], { windowsHide: true, stdio: ["pipe", "pipe", "pipe"] });
    this.child.stderr.resume();
    readline.createInterface({ input: this.child.stdout }).on("line", line => {
      let value: { ready?: boolean; ok?: boolean; error?: string }; try { value = JSON.parse(line) as typeof value; } catch { return; }
      if (value.ready === true) { this.readyResolve(); return; }
      const pending = this.pending; if (!pending) return; this.pending = undefined;
      if (value.ok === false) pending.reject(new HttpError(400, "ui_failed", typeof value.error === "string" ? value.error.slice(0, 500) : "The application could not be controlled.")); else pending.resolve(value);
    });
    const failed = () => { this.closed = true; const error = new Error("The Windows interaction service stopped."); this.readyReject(error); this.pending?.reject(error); this.pending = undefined; };
    this.child.on("error", failed); this.child.on("exit", failed);
    void this.ready.catch(() => undefined);
  }
  async execute(request: UiRequest, signal: AbortSignal): Promise<unknown> {
    if (this.idle) clearTimeout(this.idle);
    signal.throwIfAborted();
    const timeout = setTimeout(() => this.stop(), 30_000);
    const abort = () => this.stop();
    signal.addEventListener("abort", abort, { once: true });
    try {
      await this.ready; signal.throwIfAborted();
      if (this.closed) throw new Error("The Windows interaction service is unavailable.");
      return await new Promise((resolve, reject) => { this.pending = { resolve, reject }; this.child.stdin.write(`${JSON.stringify(request)}\n`); });
    } finally {
      clearTimeout(timeout); signal.removeEventListener("abort", abort);
      if (!this.closed) this.idle = setTimeout(() => this.stop(), 60_000);
    }
  }
  stop() { if (this.idle) clearTimeout(this.idle); if (!this.closed) { this.closed = true; this.child.kill(); } }
  get alive() { return !this.closed; }
}
let uiWorker: UiWorker | undefined;
let uiQueue: Promise<unknown> = Promise.resolve();
export function closeUiWorker(): void { uiWorker?.stop(); uiWorker = undefined; }
process.once("exit", closeUiWorker);

export async function runUiAction(request: UiRequest, signal: AbortSignal): Promise<unknown> {
  const input = validateUiRequest(request);
  if (process.platform !== "win32") throw new HttpError(400, "ui_platform", "Windows interaction requires Windows.");
  const work = uiQueue.catch(() => undefined).then(async () => {
    signal.throwIfAborted();
    if (!uiWorker?.alive) uiWorker = new UiWorker();
    return uiWorker.execute(input, signal);
  });
  uiQueue = work.catch(() => undefined);
  return work;
}

export async function startWindowsDictation(signal: AbortSignal): Promise<void> {
  const command = String.raw`Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public static class OmniDictation { [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, System.UIntPtr extra); }'; [OmniDictation]::keybd_event(0x5B,0,0,[System.UIntPtr]::Zero); [OmniDictation]::keybd_event(0x48,0,0,[System.UIntPtr]::Zero); [OmniDictation]::keybd_event(0x48,0,2,[System.UIntPtr]::Zero); [OmniDictation]::keybd_event(0x5B,0,2,[System.UIntPtr]::Zero)`;
  const result = await runFixedPowerShell(command, os.homedir(), signal);
  if (!result.ok) throw new HttpError(400, "voice_failed", "Windows voice typing could not start.");
}
