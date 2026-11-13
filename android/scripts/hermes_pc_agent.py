#!/usr/bin/env python3
"""
Hermes PC Bridge Agent - a small, token-protected HTTP bridge for Omni.

The Android app talks to this agent only when the user has saved both a PC
address and a token in Settings. Every request must carry that token as
``Authorization: Bearer <token>``.

Usage (PowerShell):
    $env:HERMES_TOKEN = "<a long random secret>"
    python scripts/hermes_pc_agent.py
    python scripts/hermes_pc_agent.py --host 0.0.0.0 --port 8765

Security rules:
- ``HERMES_TOKEN`` is required. ``OMNI_HERMES_TOKEN`` is accepted as a legacy
  alias. The token is never printed, logged, or returned in a response.
- The agent binds to 127.0.0.1 by default. Binding to 0.0.0.0 exposes the PC to
  the local network; do that only on a trusted network.
- Only the fixed actions in ``ALLOWED_ACTIONS`` are implemented. There is no
  arbitrary command or PowerShell action, and request data is never
  interpolated into a shell command.
- ``open_app`` accepts a small allowlist of harmless desktop apps.
- No CORS headers are sent. This agent is not a browser API.
- Standard library only.
"""

import argparse
import ctypes
import hmac
import ipaddress
import json
import os
import platform
import socket
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PORT = 8765
MAX_BODY_BYTES = 64 * 1024
MAX_OUTPUT_CHARS = 12_000
AUTH_FAILURE_DELAY_SECONDS = 0.25

TOKEN_ENV_VARS = ("HERMES_TOKEN", "OMNI_HERMES_TOKEN")

# The model-facing tool schema uses the same list; keep these in sync.
ALLOWED_ACTIONS = {
    "sys_info",
    "desktop_files",
    "open_app",
    "screenshot",
    "list_processes",
    "disk_info",
}

# Only fixed executable names. Nothing from the request is passed to a shell.
ALLOWED_APPS = {
    "notepad": "notepad.exe",
    "calc": "calc.exe",
    "explorer": "explorer.exe",
    "mspaint": "mspaint.exe",
}

# Fixed, non-interpolated commands. The request body never reaches these
# strings. They are read-only except for the screenshot file.
_SCREENSHOT_COMMAND = (
    "powershell -NoProfile -NonInteractive -Command \""
    "Add-Type -AssemblyName System.Windows.Forms;"
    "Add-Type -AssemblyName System.Drawing;"
    "$b=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds;"
    "$bmp=New-Object System.Drawing.Bitmap $b.Width,$b.Height;"
    "$g=[System.Drawing.Graphics]::FromImage($bmp);"
    "$g.CopyFromScreen($b.X,$b.Y,0,0,$bmp.Size);"
    "$p=Join-Path $env:TEMP 'hermes_screenshot.png';"
    "$bmp.Save($p,[System.Drawing.Imaging.ImageFormat]::Png);"
    "$g.Dispose();$bmp.Dispose();Write-Output $p\""
)

_LIST_PROCESSES_COMMAND = (
    "powershell -NoProfile -NonInteractive -Command \""
    "Get-Process | Sort-Object CPU -Descending | Select-Object -First 10 "
    "Name, Id, CPU | Format-Table -AutoSize | Out-String -Width 200\""
)

_DISK_INFO_COMMAND = (
    "powershell -NoProfile -NonInteractive -Command \""
    "Get-PSDrive -PSProvider FileSystem | "
    "Select-Object Name, @{N='Used(GB)';E={[math]::Round($_.Used/1GB,1)}}, "
    "@{N='Free(GB)';E={[math]::Round($_.Free/1GB,1)}} | "
    "Format-Table -AutoSize | Out-String -Width 200\""
)


def _env_token():
    """Read the token from the environment without ever echoing it."""
    for name in TOKEN_ENV_VARS:
        value = os.environ.get(name, "").strip()
        if value:
            return value
    return ""


def _env_port():
    raw = os.environ.get("HERMES_PORT", "").strip()
    if not raw:
        return PORT
    try:
        value = int(raw)
    except ValueError:
        return PORT
    return value if 1 <= value <= 65535 else PORT


def _is_loopback(host):
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def _desktop_dir():
    home = os.path.expanduser("~")
    candidates = [
        os.path.join(home, "Desktop"),
        os.path.join(home, "OneDrive", "Desktop"),
    ]
    for path in candidates:
        if os.path.isdir(path):
            return path
    return candidates[0]


def _desktop_file_count():
    folder = _desktop_dir()
    try:
        return len(os.listdir(folder))
    except OSError:
        return 0


def _total_memory_gb():
    """Total physical memory on Windows, or None when unavailable."""
    if os.name != "nt":
        return None

    class MEMORYSTATUSEX(ctypes.Structure):
        _fields_ = [
            ("dwLength", ctypes.c_ulong),
            ("dwMemoryLoad", ctypes.c_ulong),
            ("ullTotalPhys", ctypes.c_ulonglong),
            ("ullAvailPhys", ctypes.c_ulonglong),
            ("ullTotalPageFile", ctypes.c_ulonglong),
            ("ullAvailPageFile", ctypes.c_ulonglong),
            ("ullTotalVirtual", ctypes.c_ulonglong),
            ("ullAvailVirtual", ctypes.c_ulonglong),
            ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
        ]

    status = MEMORYSTATUSEX()
    status.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
    try:
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            return status.ullTotalPhys / (1024.0 ** 3)
    except Exception:
        pass
    return None


def _read_output_file(path):
    try:
        with open(path, "rb") as handle:
            raw = handle.read()
    except OSError:
        return ""

    if raw.startswith(b"\xff\xfe") or raw.startswith(b"\xfe\xff"):
        try:
            return raw.decode("utf-16").strip()
        except UnicodeDecodeError:
            pass
    try:
        return raw.decode("utf-8").strip()
    except UnicodeDecodeError:
        try:
            return raw.decode("mbcs", errors="replace").strip()
        except LookupError:
            return raw.decode("latin-1", errors="replace").strip()


def _run_fixed(command):
    """Run one of the fixed command strings above.

    ``os.system`` is used instead of subprocess because the Python install on
    some target machines does not ship subprocess. The command argument is
    always a module constant; no request data is ever included.
    """
    handle, path = tempfile.mkstemp(prefix="hermes_cmd_", suffix=".txt")
    os.close(handle)
    try:
        exit_code = os.system('{} > "{}" 2>&1'.format(command, path))
        return _read_output_file(path), exit_code
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


def _sys_info():
    uname = platform.uname()
    processor = platform.processor() or uname.machine or "Unknown"
    lines = [
        "System: {} {}".format(uname.system, uname.release).strip(),
        "Node: {}".format(uname.node),
        "Processor: {}".format(processor),
    ]
    memory = _total_memory_gb()
    if memory:
        lines.append("Memory: {:.1f} GB".format(memory))
    return "\n".join(lines)


def _desktop_files():
    folder = _desktop_dir()
    try:
        names = sorted(os.listdir(folder), key=str.lower)
    except OSError:
        return False, "Desktop folder not found."

    visible = [name.replace("\r", " ").replace("\n", " ") for name in names[:15]]
    if not visible:
        return True, "The Desktop folder is empty."
    return True, "Desktop files:\n" + "\n".join("\u2022 " + name for name in visible)


def _open_app(payload):
    name = payload.strip().lower()
    executable = ALLOWED_APPS.get(name)
    if executable is None:
        return False, "Unsupported app. Allowed apps: {}.".format(
            ", ".join(sorted(ALLOWED_APPS)),
        )
    if not hasattr(os, "startfile"):
        return False, "Opening apps is only supported on Windows."
    try:
        os.startfile(executable)  # type: ignore[attr-defined]
    except OSError:
        return False, "Could not start {}.".format(executable)
    return True, "Opened {} on the PC.".format(executable)


def _screenshot():
    path = os.path.join(tempfile.gettempdir(), "hermes_screenshot.png")
    try:
        os.remove(path)
    except OSError:
        pass

    output, _ = _run_fixed(_SCREENSHOT_COMMAND)
    if os.path.exists(path):
        return True, "Screenshot saved: {}".format(path), path
    detail = " {}".format(output) if output else ""
    return False, "Screenshot failed.{}".format(detail), None


class HermesBridgeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 15

    # -- responses ---------------------------------------------------------

    def _send_json(self, status, data, extra_headers=None):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def _unauthorized(self):
        # Slow down token guessing without blocking the whole server.
        time.sleep(AUTH_FAILURE_DELAY_SECONDS)
        self._send_json(
            401,
            {"success": False, "error": "Unauthorized"},
            {"WWW-Authenticate": "Bearer"},
        )

    def _authorized(self):
        header = self.headers.get("Authorization", "")
        parts = header.split(None, 1)
        if len(parts) != 2 or parts[0].lower() != "bearer":
            return False
        candidate = parts[1].strip()
        if not candidate:
            return False
        expected = getattr(self.server, "token", "")
        return hmac.compare_digest(
            candidate.encode("utf-8"),
            expected.encode("utf-8"),
        )

    def _scrub(self, text):
        value = "" if text is None else str(text)
        token = getattr(self.server, "token", "")
        if token:
            value = value.replace(token, "***")
        return value[:MAX_OUTPUT_CHARS]

    # -- request handlers --------------------------------------------------

    def do_GET(self):
        try:
            if not self._authorized():
                self._unauthorized()
                return

            path = urlsplit(self.path).path
            if path not in ("/", "/status"):
                self._send_json(404, {"success": False, "error": "Not found"})
                return

            uname = platform.uname()
            self._send_json(
                200,
                {
                    "status": "connected",
                    "system": uname.system,
                    "node": uname.node,
                    "release": uname.release,
                    "desktop_files_count": _desktop_file_count(),
                    "platform": platform.platform(),
                },
            )
        except Exception as error:
            self.log_message("GET error: %s", self._scrub(error))
            self._send_json(500, {"success": False, "error": "Internal server error"})

    def do_POST(self):
        try:
            if not self._authorized():
                self._unauthorized()
                return

            if urlsplit(self.path).path != "/execute":
                self._send_json(404, {"success": False, "error": "Not found"})
                return

            request = self._read_json_body()
            if request is None:
                return

            action = request.get("action")
            payload = request.get("payload", "")
            if not isinstance(action, str) or action not in ALLOWED_ACTIONS:
                self._send_json(
                    200,
                    {
                        "success": False,
                        "action": action if isinstance(action, str) else "unknown",
                        "output": "Unsupported action. Allowed actions: {}.".format(
                            ", ".join(sorted(ALLOWED_ACTIONS)),
                        ),
                    },
                )
                return
            if not isinstance(payload, str):
                payload = ""

            result = {"success": False, "action": action, "output": ""}

            if action == "sys_info":
                result = {
                    "success": True,
                    "action": action,
                    "output": self._scrub(_sys_info()),
                }
            elif action == "desktop_files":
                success, output = _desktop_files()
                result = {
                    "success": success,
                    "action": action,
                    "output": self._scrub(output),
                }
            elif action == "open_app":
                success, output = _open_app(payload)
                result = {
                    "success": success,
                    "action": action,
                    "output": self._scrub(output),
                }
            elif action == "screenshot":
                success, output, path = _screenshot()
                result = {
                    "success": success,
                    "action": action,
                    "output": self._scrub(output),
                }
                if path:
                    result["file_path"] = self._scrub(path)
            elif action == "list_processes":
                output, exit_code = _run_fixed(_LIST_PROCESSES_COMMAND)
                result = {
                    "success": exit_code == 0 and bool(output),
                    "action": action,
                    "output": self._scrub(output or "(no output)"),
                }
            elif action == "disk_info":
                output, exit_code = _run_fixed(_DISK_INFO_COMMAND)
                result = {
                    "success": exit_code == 0 and bool(output),
                    "action": action,
                    "output": self._scrub(output or "(no output)"),
                }

            self._send_json(200, result)
        except Exception as error:
            self.log_message("POST error: %s", self._scrub(error))
            self._send_json(500, {"success": False, "error": "Internal server error"})

    def _read_json_body(self):
        raw_length = self.headers.get("Content-Length", "").strip()
        try:
            length = int(raw_length)
        except ValueError:
            self._send_json(400, {"success": False, "error": "Missing Content-Length"})
            return None
        if length <= 0:
            self._send_json(400, {"success": False, "error": "Empty request body"})
            return None
        if length > MAX_BODY_BYTES:
            self._send_json(413, {"success": False, "error": "Request body too large"})
            return None

        try:
            raw = self.rfile.read(length)
            data = json.loads(raw.decode("utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            self._send_json(400, {"success": False, "error": "Invalid JSON body"})
            return None
        if not isinstance(data, dict):
            self._send_json(400, {"success": False, "error": "Expected a JSON object"})
            return None
        return data

    # -- logging -----------------------------------------------------------

    def log_request(self, code="-", size="-"):
        # Log only the method, path and status: no query, headers, body or token.
        path = urlsplit(getattr(self, "path", "")).path or "/"
        print(
            "[Hermes] {} {} {} {}".format(
                self.address_string(),
                getattr(self, "command", "-"),
                path,
                code,
            ),
            flush=True,
        )

    def log_message(self, fmt, *args):
        # Protocol-level errors only. Request bodies and headers are never logged.
        print(
            "[Hermes] {} {}".format(self.address_string(), fmt % args),
            flush=True,
        )


class HermesBridgeServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, handler, token):
        super().__init__(address, handler)
        self.token = token


def run(argv=None):
    parser = argparse.ArgumentParser(
        description="Token-protected Hermes PC bridge for the Omni Android app.",
    )
    parser.add_argument(
        "--host",
        default=os.environ.get("HERMES_HOST", "127.0.0.1").strip() or "127.0.0.1",
        help="address to bind (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=_env_port(),
        help="port to listen on (default: 8765 or HERMES_PORT)",
    )
    args = parser.parse_args(argv)

    if not (1 <= args.port <= 65535):
        print("Invalid port. Use a number between 1 and 65535.", file=sys.stderr)
        return 2

    token = _env_token()
    if not token:
        print(
            "HERMES_TOKEN is not set. Create a long random secret, set it in the "
            "environment, and use the same value in Omni Settings.",
            file=sys.stderr,
        )
        print(
            'PowerShell: $env:HERMES_TOKEN = "<secret>"; '
            "python scripts/hermes_pc_agent.py",
            file=sys.stderr,
        )
        return 2
    if len(token) < 16:
        print(
            "Warning: HERMES_TOKEN is shorter than 16 characters. Use a long "
            "random value to make guessing impractical.",
            file=sys.stderr,
        )

    if not _is_loopback(args.host):
        print(
            "WARNING: binding to {} exposes this bridge to your local network. "
            "Use it only on a trusted network with a strong HERMES_TOKEN.".format(
                args.host,
            ),
            file=sys.stderr,
        )

    if ":" in args.host:
        HermesBridgeServer.address_family = socket.AF_INET6

    try:
        server = HermesBridgeServer((args.host, args.port), HermesBridgeHandler, token)
    except OSError as error:
        print("Could not listen on {}:{}: {}".format(args.host, args.port, error), file=sys.stderr)
        return 1

    uname = platform.uname()
    print("=== Hermes PC Bridge ===")
    print("Listening on http://{}:{}".format(args.host, args.port))
    print("System: {} {}".format(uname.system, uname.release))
    print("Node: {}".format(uname.node))
    print("Actions: {}".format(", ".join(sorted(ALLOWED_ACTIONS))))
    print("Bearer token required. The token is never printed.")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(run())
