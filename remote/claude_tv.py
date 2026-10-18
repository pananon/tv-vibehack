"""Claude on TV: shows the live Claude Code chat on the TV (view only).

The chat comes from Claude Code's own transcript (~/.claude/projects/*/<session>.jsonl, the newest
one), turned into a short list of messages. The HTTP server only answers the TV's IP address, and
only with the random key handed to the TV over adb when the bridge starts.
"""
import glob
import http.server
import json
import os
import re
import secrets
import threading
import urllib.parse

CLAUDE_DIR = os.path.join(os.path.expanduser("~"), ".claude")
PORT = 8765
KEEP = 300   # messages kept for the TV


# ---- reading the chat ----------------------------------------------------------------------------


def newest_transcript():
    files = glob.glob(os.path.join(CLAUDE_DIR, "projects", "*", "*.jsonl"))
    return max(files, key=os.path.getmtime) if files else None


def session_process(session_id):
    """(pid, name) of the running Claude Code process for a session, from ~/.claude/sessions/."""
    for f in glob.glob(os.path.join(CLAUDE_DIR, "sessions", "*.json")):
        try:
            with open(f, encoding="utf-8") as fh:
                d = json.load(fh)
        except (OSError, ValueError):
            continue
        if d.get("sessionId") == session_id:
            return int(d["pid"]), d.get("name") or ""
    return None, ""


# Wrappers the terminal never shows as your words.
_HIDDEN_TAGS = re.compile(
    r"<(system-reminder|local-command-stdout|local-command-stderr|command-name|command-message|"
    r"command-args|bash-input|bash-stdout|bash-stderr)>[\s\S]*?</\1>")


def _clean_user_text(text):
    return _HIDDEN_TAGS.sub("", text).strip()


def _tool_line(block):
    inp = block.get("input") or {}
    detail = inp.get("description") or inp.get("command") or inp.get("file_path") or inp.get("pattern") \
        or inp.get("query") or inp.get("url") or ""
    detail = " ".join(str(detail).split())
    if len(detail) > 140:
        detail = detail[:137] + "…"
    return f"{block.get('name', 'Tool')}: {detail}" if detail else str(block.get("name", "Tool"))


class Feed:
    """Follows the newest transcript, parsing only the lines added since the last refresh."""

    def __init__(self):
        self.lock = threading.Lock()
        self.path = None
        self.offset = 0
        self.partial = b""
        self.items = []
        self.next_id = 1
        self.title = ""
        self.busy = False
        self.session_id = None

    def refresh(self):
        path = newest_transcript()
        if path is None:
            return
        with self.lock:
            if path != self.path:   # a different session became the newest: start over
                self.path, self.offset, self.partial = path, 0, b""
                self.items, self.title, self.busy = [], "", False
                self.session_id = os.path.splitext(os.path.basename(path))[0]
            try:
                with open(path, "rb") as f:
                    f.seek(self.offset)
                    data = f.read()
            except OSError:
                return
            self.offset += len(data)
            lines = (self.partial + data).split(b"\n")
            self.partial = lines.pop()   # an incomplete last line waits for the next refresh
            for raw in lines:
                try:
                    self._add(json.loads(raw))
                except (ValueError, KeyError, TypeError, AttributeError):
                    continue
            del self.items[:-KEEP]

    def _item(self, role, text):
        self.items.append({"id": self.next_id, "role": role, "text": text})
        self.next_id += 1

    def _add(self, o):
        kind = o.get("type")
        if kind == "ai-title" and o.get("aiTitle"):
            self.title = o["aiTitle"]
            return
        if kind not in ("user", "assistant") or o.get("isSidechain") or o.get("isMeta"):
            return
        msg = o.get("message") or {}
        content = msg.get("content")
        blocks = [{"type": "text", "text": content}] if isinstance(content, str) else (content or [])
        if kind == "user":
            for b in blocks:
                if b.get("type") == "text":
                    text = _clean_user_text(b.get("text", ""))
                    if text:
                        self._item("user", text)
                        self.busy = True
                elif b.get("type") == "tool_result":
                    self.busy = True
        else:
            for b in blocks:
                if b.get("type") == "text" and b.get("text", "").strip():
                    self._item("assistant", b["text"].strip())
                elif b.get("type") == "tool_use":
                    self._item("tool", _tool_line(b))
            stop = msg.get("stop_reason")
            if stop == "end_turn":
                self.busy = False
            elif stop == "tool_use":
                self.busy = True

    def since(self, last_id):
        with self.lock:
            pid, name = session_process(self.session_id) if self.session_id else (None, "")
            return {
                "title": self.title or name or "Claude",
                "busy": self.busy,
                "connected": pid is not None,
                "items": [i for i in self.items if i["id"] > last_id][-200:],
            }


# ---- the bridge ----------------------------------------------------------------------------------


class Bridge:
    def __init__(self):
        self.feed = Feed()
        self.server = None
        self.token = None
        self.allowed_ip = None
        self.url = None

    @property
    def running(self):
        return self.server is not None

    def start(self, lan_ip, tv_ip):
        self.stop()
        self.token = secrets.token_urlsafe(18)
        self.allowed_ip = tv_ip
        bridge = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):   # quiet: no console under pythonw
                pass

            def _check(self):
                q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
                ok = (self.client_address[0] == bridge.allowed_ip
                      and secrets.compare_digest(q.get("token", [""])[0], bridge.token))
                if not ok:
                    self.send_error(403)
                return ok, q

            def _json(self, code, obj):
                body = json.dumps(obj).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                ok, q = self._check()
                if not ok:
                    return
                if urllib.parse.urlparse(self.path).path != "/feed":
                    return self.send_error(404)
                bridge.feed.refresh()
                self._json(200, bridge.feed.since(int(q.get("since", ["0"])[0] or 0)))

        self.server = http.server.ThreadingHTTPServer((lan_ip, PORT), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://{lan_ip}:{PORT}"
        return self.url, self.token

    def stop(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
        self.server = None
