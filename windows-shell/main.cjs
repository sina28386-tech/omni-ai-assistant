const { app, BrowserWindow, shell, dialog } = require('electron');
const { spawn } = require('node:child_process');
const path = require('node:path');
const http = require('node:http');

const requestedPort = Number(process.env.OMNI_PORT || 4317);
const PORT = Number.isInteger(requestedPort) && requestedPort > 0 && requestedPort < 65536 ? requestedPort : 4317;
const ORIGIN = `http://127.0.0.1:${PORT}`;
let backend = null;
let window = null;

function health() {
  return new Promise(resolve => {
    const request = http.get(`${ORIGIN}/api/health`, { timeout: 1200 }, response => {
      let body = '';
      response.on('data', chunk => { body += chunk; if (body.length > 4096) request.destroy(); });
      response.on('end', () => { try { const data = JSON.parse(body); resolve(response.statusCode === 200 && data.ok === true && data.version === '0.1.0'); } catch { resolve(false); } });
    });
    request.on('timeout', () => request.destroy());
    request.on('error', () => resolve(false));
  });
}

async function startBackend() {
  if (await health()) return;
  const runtime = path.join(app.getAppPath(), 'runtime');
  backend = spawn(process.execPath, [path.join(runtime, 'dist', 'server', 'index.js')], {
    cwd: runtime, windowsHide: true, stdio: 'ignore',
    env: { ...process.env, ELECTRON_RUN_AS_NODE: '1', OMNI_PORT: String(PORT) },
  });
  let exited = false;
  backend.on('error', () => { exited = true; });
  backend.on('exit', () => { exited = true; });
  for (let attempt = 0; attempt < 60; attempt++) {
    if (await health()) return;
    if (exited) break;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error(`The local Omni service could not start. Check whether port ${PORT} is occupied.`);
}

function makeWindow() {
  window = new BrowserWindow({
    width: 1440, height: 900, minWidth: 760, minHeight: 560, title: 'Omni Assistant',
    show: false, backgroundColor: '#191919',
    webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true, webSecurity: true },
  });
  window.setMenuBarVisibility(false);
  window.once('ready-to-show', () => window?.show());
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) void shell.openExternal(url);
    return { action: 'deny' };
  });
  window.webContents.on('will-navigate', (event, url) => {
    if (new URL(url).origin !== ORIGIN) event.preventDefault();
  });
  void window.loadURL(ORIGIN);
}

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { if (window) { if (window.isMinimized()) window.restore(); window.focus(); } });
  app.whenReady().then(async () => {
    try { await startBackend(); makeWindow(); }
    catch (error) { dialog.showErrorBox('Omni Assistant', error instanceof Error ? error.message : String(error)); app.quit(); }
  });
  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', () => { if (backend && !backend.killed) backend.kill(); });
}
