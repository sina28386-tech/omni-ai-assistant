#!/usr/bin/env python3
"""Deterministic OpenAI-compatible mock used for Omni V2 tool-calling QA.

This server is intentionally small and dependency-free. It lets the Android
app exercise the real agent loop (streamed tool-call fragments, persisted tool
results, follow-up completion) without spending provider credits.

Run:
    python scripts/mock_openai_server.py --host 0.0.0.0 --port 8787 --key test-key

Then configure a provider in Omni with:
    Base URL: http://10.0.2.2:8787/v1   (emulator -> host)
    Endpoint: /chat/completions
    API key:  test-key
    Model:    mock-omni

The server is a test fixture: it never talks to the network and never executes
anything. Do not expose it outside a local development machine.
"""
from __future__ import annotations

import argparse
import json
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


TOOL_PATTERNS: list[tuple[re.Pattern[str], list[dict[str, Any]]]] = [
    (
        re.compile(r"(?:battery|device status|وضعیت (?:باتری|دستگاه)|باتری)", re.I),
        [
            {
                "id": "call_device_status_1",
                "type": "function",
                "function": {"name": "device_status", "arguments": "{}"},
            }
        ],
    ),
    (
        re.compile(r"(?:clipboard|کلیپ(?:\u200c| )?بورد|حافظه موقت)", re.I),
        [
            {
                "id": "call_clipboard_1",
                "type": "function",
                "function": {"name": "clipboard_get", "arguments": "{}"},
            }
        ],
    ),
    (
        re.compile(r"(?:read (?:my )?notifications|اعلان(?:\u200c| )?ها)", re.I),
        [
            {
                "id": "call_notifications_1",
                "type": "function",
                "function": {
                    "name": "notifications_read",
                    "arguments": json.dumps({"limit": 5}, ensure_ascii=False),
                },
            }
        ],
    ),
    (
        re.compile(r"(?:screen|صفحه(?:\u200c| )?نمایش)", re.I),
        [
            {
                "id": "call_screen_1",
                "type": "function",
                "function": {"name": "screen_read", "arguments": "{}"},
            }
        ],
    ),
    (
        re.compile(r"(?:list files|فایل(?:\u200c| )?ها)", re.I),
        [
            {
                "id": "call_files_1",
                "type": "function",
                "function": {
                    "name": "files_recent",
                    "arguments": json.dumps({"limit": 3}, ensure_ascii=False),
                },
            }
        ],
    ),
    (
        re.compile(r"(?:pc status|وضعیت (?:کامپیوتر|پی\u200cسی))", re.I),
        [
            {
                "id": "call_pc_1",
                "type": "function",
                "function": {"name": "pc_status", "arguments": "{}"},
            }
        ],
    ),
    (
        re.compile(r"(?:two tools|دو ابزار|همزمان)", re.I),
        [
            {
                "id": "call_parallel_1",
                "type": "function",
                "function": {"name": "device_status", "arguments": "{}"},
            },
            {
                "id": "call_parallel_2",
                "type": "function",
                "function": {"name": "clipboard_get", "arguments": "{}"},
            },
        ],
    ),
    (
        re.compile(r"(?:send sms|پیامک بفرست)", re.I),
        [
            {
                "id": "call_sms_1",
                "type": "function",
                "function": {
                    "name": "sms_send",
                    "arguments": json.dumps(
                        {"number": "+15555550123", "body": "Omni QA test message"},
                        ensure_ascii=False,
                    ),
                },
            }
        ],
    ),
    (
        re.compile(r"(?:missing permission|بدون دسترسی)", re.I),
        [
            {
                "id": "call_denied_1",
                "type": "function",
                "function": {"name": "call_log", "arguments": "{}"},
            }
        ],
    ),
    (
        re.compile(r"(?:unknown tool|ابزار ناشناس)", re.I),
        [
            {
                "id": "call_unknown_1",
                "type": "function",
                "function": {"name": "definitely_not_a_tool", "arguments": "{}"},
            }
        ],
    ),
]


def tool_round_already_done(messages: list[dict[str, Any]]) -> bool:
    for message in reversed(messages):
        if message.get("role") == "user":
            return False
        if message.get("role") == "tool":
            return True
    return False


# A chain that mirrors a real request: find a file, share it, open an app and
# read the device. Every step consumes the *real* result of the previous one,
# so the test proves that results actually flow between steps.
CHAIN_STEPS = ["files_search", "file_share", "app_open", "device_status"]


def chain_requested(text: str) -> bool:
    return bool(re.search(r"(?:\bchain\b|زنجیره)", text, re.I))


def chain_step(messages: list[dict[str, Any]]) -> int:
    """How many chain steps already produced a result."""
    return sum(1 for message in messages if message.get("role") == "tool")


def last_tool_payload(messages: list[dict[str, Any]]) -> dict[str, Any]:
    for message in reversed(messages):
        if message.get("role") != "tool":
            continue
        raw = message.get("content")
        if isinstance(raw, str):
            try:
                parsed = json.loads(raw)
                if isinstance(parsed, dict):
                    return parsed
            except ValueError:
                continue
    return {}


def chain_calls(step: int, messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """The tool call for the given step of the chain."""
    if step < 0 or step >= len(CHAIN_STEPS):
        return []
    name = CHAIN_STEPS[step]
    if name == "files_search":
        arguments = {"query": "report", "kind": "document", "limit": 3}
    elif name == "file_share":
        payload = last_tool_payload(messages)
        results = payload.get("data", {}).get("results", [])
        path = results[0].get("path") if results else None
        arguments = {"path": path or "/sdcard/Download/report.pdf"}
    elif name == "app_open":
        arguments = {"app": "Settings"}
    else:
        arguments = {}
    return [
        {
            "id": f"call_chain_{step}_{name}",
            "type": "function",
            "function": {"name": name, "arguments": json.dumps(arguments, ensure_ascii=False)},
        }
    ]


def media_requested(text: str) -> bool:
    return bool(re.search(r"(?:\bplay\b|\bmusic\b|\bsong\b|پخش|آهنگ|موسیقی)", text, re.I))


def media_calls(step: int, messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Find a track and then play the one that was actually found."""
    if step == 0:
        return [
            {
                "id": "call_media_search",
                "type": "function",
                "function": {
                    "name": "media_search",
                    "arguments": json.dumps({"query": "omar", "kind": "audio"}, ensure_ascii=False),
                },
            }
        ]
    if step == 1:
        payload = last_tool_payload(messages)
        results = payload.get("data", {}).get("results", [])
        first = results[0] if results else {}
        path = first.get("path")
        arguments = {"path": path} if path else {"query": "omar"}
        return [
            {
                "id": "call_media_play",
                "type": "function",
                "function": {"name": "media_play", "arguments": json.dumps(arguments, ensure_ascii=False)},
            }
        ]
    return []


def media_summary(messages: list[dict[str, Any]]) -> str:
    for message in reversed(messages):
        if message.get("role") != "tool":
            continue
        raw = message.get("content")
        if isinstance(raw, str):
            try:
                parsed = json.loads(raw)
            except ValueError:
                continue
            data = parsed.get("data", {})
            if data.get("playing"):
                title = data.get("title", "")
                artist = data.get("artist", "")
                return f"Playing {title}" + (f" — {artist}" if artist else "") + "."
    return "I could not find a track to play on this phone."


def chain_summary(messages: list[dict[str, Any]]) -> str:
    steps = []
    for message in messages:
        if message.get("role") != "tool":
            continue
        name = message.get("name") or "tool"
        raw = message.get("content")
        summary = ""
        if isinstance(raw, str):
            try:
                parsed = json.loads(raw)
                summary = str(parsed.get("summary", ""))[:120]
            except ValueError:
                summary = raw[:120]
        steps.append(f"{name}: {summary}")
    return "\n".join(steps)


def last_user_text(messages: list[dict[str, Any]]) -> str:
    for message in reversed(messages):
        if message.get("role") != "user":
            continue
        content = message.get("content")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            return " ".join(
                part.get("text", "")
                for part in content
                if isinstance(part, dict) and part.get("type") == "text"
            )
    return ""


def tool_result_summary(messages: list[dict[str, Any]]) -> str:
    summaries: list[str] = []
    for message in messages:
        if message.get("role") != "tool":
            continue
        raw = message.get("content") or ""
        try:
            payload = json.loads(raw) if isinstance(raw, str) else raw
        except (TypeError, ValueError):
            payload = {"ok": False, "error": "unparseable tool payload"}
        name = message.get("name") or "tool"
        if isinstance(payload, dict) and payload.get("ok") is True:
            summaries.append(f"{name}: {payload.get('summary', 'ok')}")
        elif isinstance(payload, dict):
            summaries.append(f"{name}: {payload.get('error', 'failed')}")
        else:
            summaries.append(f"{name}: completed")
    return "\n".join(summaries[-4:])


def choose_tools(text: str) -> list[dict[str, Any]]:
    for pattern, calls in TOOL_PATTERNS:
        if pattern.search(text):
            return calls
    return []


def completion_text(text: str, tool_summary: str) -> str:
    if tool_summary:
        return (
            "QA tool loop completed.\n\n"
            f"{tool_summary}\n\n"
            "This answer was generated after the app executed the tool and sent "
            "the real result back to the mock model."
        )
    if re.search(r"(?:hello|سلام)", text, re.I):
        return "سلام! Omni mock provider آماده است."
    if re.search(r"(?:persian|فارسی)", text, re.I):
        return "پاسخ آزمایشی فارسی: حلقهٔ ابزار واقعی است و نتیجه از دستگاه برگشته است."
    return "Mock response. Ask about battery, clipboard, notifications, files, screen, PC status, or two tools."


class MockHandler(BaseHTTPRequestHandler):
    server_version = "OmniMock/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[mock] {self.address_string()} {fmt % args}", flush=True)

    def _authorized(self) -> bool:
        expected = self.server.api_key  # type: ignore[attr-defined]
        if not expected:
            return True
        header = self.headers.get("Authorization", "")
        return header == f"Bearer {expected}"
    def _send_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path.rstrip("/") in ("", "/health"):
            self._send_json(200, {"status": "ok", "mock": True})
            return
        if self.path.rstrip("/").endswith("/last_request"):
            # The last request body, so a test can assert what the app really
            # sent to the model (system prompt, tool schemas, transcript).
            self._send_json(200, {"request": getattr(self.server, "last_request", {})})
            return
        if self.path.rstrip("/").endswith("/models"):
            if not self._authorized():
                self._send_json(401, {"error": {"message": "Invalid API key"}})
                return
            self._send_json(
                200,
                {
                    "object": "list",
                    "data": [
                        {
                            "id": "mock-omni",
                            "object": "model",
                            "owned_by": "omni-qa",
                        }
                    ],
                },
            )
            return
        self._send_json(404, {"error": {"message": "Not found"}})

    def do_POST(self) -> None:  # noqa: N802
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self._send_json(404, {"error": {"message": "Not found"}})
            return
        if not self._authorized():
            self._send_json(401, {"error": {"message": "Invalid API key"}})
            return
        length = int(self.headers.get("Content-Length", "0"))
        try:
            request = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            self._send_json(400, {"error": {"message": "Invalid JSON"}})
            return

        messages = request.get("messages") or []
        if not isinstance(messages, list):
            self._send_json(400, {"error": {"message": "messages must be an array"}})
            return
        self.server.last_request = request  # type: ignore[attr-defined]
        stream = bool(request.get("stream"))
        text = last_user_text(messages)
        already_used_tools = tool_round_already_done(messages)

        if chain_requested(text):
            step = chain_step(messages)
            content = ""
            if step < len(CHAIN_STEPS):
                tools = chain_calls(step, messages)
            else:
                tools = []
                content = (
                    "Chain finished.\n\n"
                    + chain_summary(messages)
                    + "\n\nEvery step consumed the real result of the step before it."
                )
        elif media_requested(text) and not already_used_tools:
            tools = media_calls(0, messages)
            content = ""
        elif media_requested(text) and already_used_tools:
            step = chain_step(messages)
            tools = media_calls(step, messages)
            content = "" if tools else media_summary(messages)
        elif already_used_tools:
            tools = []
            content = completion_text(text, tool_result_summary(messages))
        else:
            tools = choose_tools(text)
            content = "" if tools else completion_text(text, "")

        print(
            f"[mock] model={request.get('model')} stream={stream} "
            f"messages={len(messages)} tools={len(request.get('tools') or [])} "
            f"calls={[call['function']['name'] for call in tools]}",
            flush=True,
        )

        if stream:
            self._stream(messages, content, tools)
        else:
            self._json_completion(content, tools)

    def _json_completion(self, content: str, tools: list[dict[str, Any]]) -> None:
        message: dict[str, Any] = {"role": "assistant", "content": content}
        if tools:
            message["tool_calls"] = tools
        self._send_json(
            200,
            {
                "id": f"chatcmpl-mock-{int(time.time() * 1000)}",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": "mock-omni",
                "choices": [
                    {
                        "index": 0,
                        "message": message,
                        "finish_reason": "tool_calls" if tools else "stop",
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30},
            },
        )

    def _sse(self, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.wfile.write(b"data: " + data + b"\n\n")
        self.wfile.flush()

    def _stream(
        self,
        messages: list[dict[str, Any]],
        content: str,
        tools: list[dict[str, Any]],
    ) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        now = int(time.time())
        base = {
            "id": f"chatcmpl-mock-{int(time.time() * 1000)}",
            "object": "chat.completion.chunk",
            "created": now,
            "model": "mock-omni",
        }

        def chunk(delta: dict[str, Any], finish: str | None = None) -> None:
            payload = dict(base)
            payload["choices"] = [{"index": 0, "delta": delta, "finish_reason": finish}]
            self._sse(payload)

        chunk({"role": "assistant", "content": ""})
        if tools:
            # Deliberately split names/ids/arguments across chunks the way real
            # providers do, so the app's accumulator is exercised.
            for call_index, call in enumerate(tools):
                function = call["function"]
                name = function["name"]
                arguments = function["arguments"]
                midpoint = max(1, len(arguments) // 2)
                fragments = [arguments[:midpoint], arguments[midpoint:]]
                for fragment_index, fragment in enumerate(fragments):
                    self._sse(
                        {
                            **base,
                            "choices": [
                                {
                                    "index": 0,
                                    "delta": {
                                        "tool_calls": [
                                            {
                                                "index": call_index,
                                                "id": call["id"] if fragment_index == 0 else None,
                                                "type": "function" if fragment_index == 0 else None,
                                                "function": {
                                                    "name": name if fragment_index == 0 else None,
                                                    "arguments": fragment,
                                                },
                                            }
                                        ]
                                    },
                                    "finish_reason": None,
                                }
                            ],
                        }
                    )
                    time.sleep(0.02)
            chunk({}, "tool_calls")
        else:
            for piece in re.findall(r"\S+\s*", content):
                chunk({"content": piece})
                time.sleep(0.01)
            chunk({}, "stop")

        self._sse(
            {
                **base,
                "choices": [],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30},
            }
        )
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()
        self.close_connection = True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--key", default="test-key")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), MockHandler)
    server.api_key = args.key  # type: ignore[attr-defined]
    server.last_request = {}  # type: ignore[attr-defined]
    print(
        f"[mock] listening on http://{args.host}:{args.port}/v1 "
        f"(key={'set' if args.key else 'disabled'})",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock] stopping", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
