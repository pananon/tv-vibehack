"""TV Remote: a desktop remote control for an Android TV over network ADB.

Reuses tools/tv.py's ADB key, so the TV does not ask for permission again. Settings live in
remote/remote.json next to this file: {"host": ..., "port": 5555, "mac": ..., "apps": [...]}.

Run with:  tools\\venv\\Scripts\\pythonw.exe remote\\remote.pyw

Keyboard (when the text box is not focused):
    arrows = D-pad, Enter = OK, Backspace/Esc = Back, H = Home, +/- = volume, M = mute,
    Space = play/pause, , and . = rewind / fast-forward
"""
import concurrent.futures
import ipaddress
import json
import os
import queue
import re
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(HERE, "remote.json")
DEFAULT_CONFIG = {
    "host": "192.168.1.21",
    "port": 5555,
    "mac": "",
    "apps": [
        ["Netflix", "com.netflix.ninja"],
        ["Prime", "com.amazon.amazonvideo.livingroom"],
        ["YouTube", "com.google.android.youtube.tv"],
        ["Hotstar", "in.startv.hotstar"],
    ],
}


def load_config():
    try:
        with open(CONFIG, encoding="utf-8") as f:
            return {**DEFAULT_CONFIG, **json.load(f)}
    except (OSError, ValueError):
        return dict(DEFAULT_CONFIG)


def save_config():
    try:
        with open(CONFIG, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except OSError:
        pass


cfg = load_config()
# tv.py reads the host at import time and exits without one.
os.environ["TV_HOST"] = cfg["host"]
os.environ["TV_PORT"] = str(cfg["port"])
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "tools"))
import tv  # noqa: E402
from adb_shell.adb_device import AdbDeviceTcp  # noqa: E402


def connect_to(host, timeout=10.0):
    dev = AdbDeviceTcp(host, int(cfg["port"]), default_transport_timeout_s=timeout)
    dev.connect(rsa_keys=[tv.get_signer()], transport_timeout_s=timeout, auth_timeout_s=timeout)
    return dev


# ---- finding the TV when its IP changes ----------------------------------------------------------
# The TV is recognised by its MAC address (from this PC's ARP table), never by trying to log in to
# other devices: an ADB login attempt would pop an "Allow USB debugging?" prompt on them.

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)   # no console flashes under pythonw


def _arp_table():
    try:
        out = subprocess.run(["arp", "-a"], capture_output=True, text=True, timeout=10,
                             creationflags=NO_WINDOW).stdout
    except (OSError, subprocess.SubprocessError):
        return {}
    pairs = re.findall(r"(\d+\.\d+\.\d+\.\d+)\s+([0-9a-f]{2}(?:-[0-9a-f]{2}){5})", out, re.I)
    return {ip: mac.lower() for ip, mac in pairs}


def mac_of(host):
    return _arp_table().get(host, "")


def _local_ipv4():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))   # picks the LAN interface; UDP connect sends nothing
        return s.getsockname()[0]
    finally:
        s.close()


def _port_open(ip, port):
    try:
        with socket.create_connection((ip, port), timeout=0.4):
            return True
    except OSError:
        return False


def find_tv(mac, port):
    """Probe the local /24 for the ADB port (which also fills the ARP table), then match the MAC."""
    if not mac:
        return None
    try:
        net = ipaddress.ip_network(_local_ipv4() + "/24", strict=False)
    except OSError:
        return None
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as pool:
        hosts = [str(h) for h in net.hosts()]
        open_hosts = {h for h, ok in zip(hosts, pool.map(lambda h: _port_open(h, port), hosts)) if ok}
    for ip, m in _arp_table().items():
        if m == mac and ip in open_hosts:
            return ip
    return None


# ---- connections ---------------------------------------------------------------------------------


class Link:
    """Key presses and commands, on their own connection and thread so the window never freezes."""

    def __init__(self, on_status):
        self.q = queue.Queue()
        self.on_status = on_status
        self.dev = None
        self._held = None
        threading.Thread(target=self._run, daemon=True).start()

    def key(self, code):
        self.q.put(("key", code))

    def shell(self, cmd):
        self.q.put(("shell", cmd))

    def reconnect(self):
        self.q.put(("reconnect", None))

    def _connect(self):
        host = cfg["host"]
        self.on_status("connecting", f"Connecting to {host}…")
        try:
            self.dev = connect_to(host)
        except Exception:   # unreachable, TV off, auth refused
            self.dev = None
            if self._rediscover():
                return
            self.on_status("error", f"Can't reach the TV at {host}")
            return
        if not cfg.get("mac"):
            cfg["mac"] = mac_of(host)   # remember the TV so it can be found if its IP changes
            save_config()
        self.on_status("ok", f"Connected · {host}")

    def _rediscover(self):
        if not cfg.get("mac"):
            return False
        self.on_status("connecting", "Looking for the TV on the network…")
        ip = find_tv(cfg["mac"], int(cfg["port"]))
        if not ip or ip == cfg["host"]:
            return False
        try:
            self.dev = connect_to(ip)
        except Exception:
            return False
        cfg["host"] = ip
        save_config()
        self.on_status("ok", f"Connected · {ip} (TV's address changed)")
        return True

    def _close(self):
        if self.dev is not None:
            try:
                self.dev.close()
            except Exception:
                pass
        self.dev = None

    def _run(self):
        self._connect()
        while True:
            try:
                self._step()
            except Exception:   # never let one bad command kill the worker and freeze the remote
                self._close()

    def _next(self):
        if self._held is not None:
            item, self._held = self._held, None
            return item
        return self.q.get()

    def _step(self):
        kind, arg = self._next()
        if kind == "reconnect":
            self._close()
            self._connect()
            return
        if kind == "key":
            # Collapse a burst of presses into one command: `input` is slow to start on the TV.
            codes = [arg]
            while self._held is None:
                try:
                    item = self.q.get_nowait()
                except queue.Empty:
                    break
                if item[0] == "key":
                    codes.append(item[1])
                else:
                    self._held = item   # keep order: run it right after this burst
            cmd = "input keyevent " + " ".join(codes)
        else:
            cmd = arg
        for _attempt in (1, 2):
            if self.dev is None:
                self._connect()
                if self.dev is None:
                    time.sleep(1)
                    return
            try:
                self.dev.shell(cmd, read_timeout_s=15.0, transport_timeout_s=10.0)
                return
            except Exception:
                self._close()   # dropped (TV slept, Wi-Fi blip): reconnect once and retry


class Monitor:
    """Second connection for slow reads (screen preview, volume, power state) so they never delay keys."""

    STATUS_EVERY = 5.0

    def __init__(self, on_frame, on_info):
        self.on_frame, self.on_info = on_frame, on_info
        self.preview = False
        self.wake = threading.Event()
        self.dev = None
        self._next_status = 0
        self._reset = False
        threading.Thread(target=self._run, daemon=True).start()

    def reset(self):
        """Drop the connection, e.g. after the TV address changed."""
        self._reset = True
        self.wake.set()

    def poke(self):
        """Read volume/power soon (after a volume key, say)."""
        self._next_status = 0
        self.wake.set()

    def _run(self):
        while True:
            try:
                if self._reset:
                    self._reset = False
                    raise ConnectionResetError
                if self.dev is None:
                    self.dev = connect_to(cfg["host"])
                if time.time() >= self._next_status:
                    self._read_status()
                    self._next_status = time.time() + self.STATUS_EVERY
                if self.preview:
                    self._grab_frame()
                    continue
            except Exception:
                if self.dev is not None:
                    try:
                        self.dev.close()
                    except Exception:
                        pass
                self.dev = None
                time.sleep(2)
            self.wake.wait(0.5)
            self.wake.clear()

    def _read_status(self):
        out = self.dev.shell("dumpsys power | grep -m1 mWakefulness=; "
                             "cmd media_session volume --stream 3 --get 2>&1 | tail -1",
                             read_timeout_s=10.0)
        awake = "Asleep" not in out and "Dozing" not in out
        m = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", out)
        vol = (int(m.group(1)), int(m.group(3))) if m else None
        self.on_info(awake, vol)

    def _grab_frame(self):
        data = self.dev.shell("screencap -p", decode=False, read_timeout_s=20.0)
        start = data.find(b"\x89PNG")   # some TVs print a log line before the image
        if start >= 0:
            self.on_frame(data[start:])


# ---- UI -------------------------------------------------------------------------------------------

BG = "#0e0f12"
PANEL = "#17191e"
BTN = "#24272e"
BTN_HOVER = "#30343d"
BTN_DOWN = "#3d424d"
FG = "#e9eaee"
DIM = "#8a8f9a"
ACCENT = "#4f8cff"
RED = "#e5484d"
GREEN = "#3ecf8e"
AMBER = "#f5a524"
FONT = ("Segoe UI", 10)
FONT_B = ("Segoe UI Semibold", 10)
SMALL = ("Segoe UI", 9)
CAPTION = ("Segoe UI Semibold", 8)
ICON = ("Segoe UI Symbol", 13)

SCALE = 1.0      # display DPI / 96, set once the window exists
_PIXEL = None    # 1x1 image: lets a Label take its width/height in pixels instead of characters


class Button(tk.Label):
    """Flat button with hover and press states (tk.Button can't be styled well on Windows).
    w and h are in pixels at 100% display scaling."""

    def __init__(self, parent, text, command, w=72, h=40, bg=BTN, fg=FG, font=FONT_B, repeat=False):
        super().__init__(parent, text=text, bg=bg, fg=fg, font=font, image=_PIXEL, compound="center",
                         width=int(w * SCALE), height=int(h * SCALE), cursor="hand2", bd=0)
        self.base, self.command, self.repeat, self._job = bg, command, repeat, None
        self.bind("<Enter>", lambda e: self.config(bg=BTN_HOVER if self.base in (BTN, BG) else self.base))
        self.bind("<Leave>", self._release)
        self.bind("<ButtonPress-1>", self._press)
        self.bind("<ButtonRelease-1>", self._release)

    def set_base(self, bg, fg=None):
        self.base = bg
        self.config(bg=bg, fg=fg or self.cget("fg"))

    def _press(self, _e=None):
        self.config(bg=BTN_DOWN)
        self.command()
        if self.repeat:   # hold to repeat, like a real remote
            self._job = self.after(450, self._again)

    def _again(self):
        self.command()
        self._job = self.after(130, self._again)

    def _release(self, _e=None):
        if self._job:
            self.after_cancel(self._job)
            self._job = None
        self.config(bg=self.base)


def looks_like_link(text):
    return re.match(r"^(https?://|www\.)\S+$", text.strip(), re.I) is not None


def shell_quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


class RemoteApp:
    def __init__(self, root):
        self.root = root
        root.title("TV Remote")
        root.configure(bg=BG)
        root.resizable(False, False)

        self.status_text = tk.StringVar(value="Starting…")
        self.vol_text = tk.StringVar(value="Volume –")
        self.awake = True
        self.connected = False
        self.dragging_volume = False
        self.mini = False
        self.pinned = False
        self.frame_img = None

        self.volume_known = False
        # Worker threads must not touch Tk; they post here and the window drains it.
        self.ui_q = queue.Queue()
        post = lambda fn: lambda *a: self.ui_q.put((fn, a))
        self.link = Link(post(self._set_status))
        self.monitor = Monitor(post(self._show_frame), post(self._set_info))
        self._build()
        self._bind_keys()
        self._drain()

    def _drain(self):
        try:
            while True:
                fn, args = self.ui_q.get_nowait()
                fn(*args)
        except queue.Empty:
            pass
        self.root.after(50, self._drain)

    # ---- state shown in the window

    def _set_status(self, state, text):
        self.connected = state == "ok"
        self.status_canvas.itemconfig(self.status_dot, fill={"ok": GREEN, "connecting": AMBER, "error": RED}[state])
        self.status_text.set(text if self.awake or not self.connected else text + " · TV asleep")
        if state == "ok":
            self.monitor.poke()

    def _set_info(self, awake, vol):
        if awake != self.awake:
            self.awake = awake
            base = self.status_text.get().replace(" · TV asleep", "")
            self.status_text.set(base if awake else base + " · TV asleep")
        if vol and not self.dragging_volume:
            self.volume_known = True
            level, top = vol
            self.volume.config(to=top)
            self.volume.set(level)
            self.vol_text.set(f"Volume {level}")

    def _show_frame(self, png):
        if not self.monitor.preview:
            return
        try:
            img = tk.PhotoImage(data=png)
        except tk.TclError:
            return
        factor = max(1, round(img.width() / (256 * SCALE)))
        self.frame_img = img.subsample(factor)
        self.preview_label.config(image=self.frame_img, text="", width=0, height=0)

    # ---- actions

    def k(self, code):
        return lambda: self.link.key(code)

    def kv(self, code):   # volume keys: also refresh the volume read-out
        def press():
            self.link.key(code)
            self.root.after(400, self.monitor.poke)
        return press

    def _launch(self, pkg):
        self.link.shell(f"monkey -p {pkg} -c android.intent.category.LEANBACK_LAUNCHER 1")

    def _send(self, text):
        text = text.strip()
        if not text:
            return
        if looks_like_link(text):
            url = text if "://" in text else "https://" + text
            # Opens in the matching TV app (YouTube links in YouTube), else the browser if one is installed.
            self.link.shell(f"am start -a android.intent.action.VIEW -d {shell_quote(url)}")
        else:
            # `input text` takes spaces as %s.
            self.link.shell("input text " + shell_quote(text.replace("%", r"\%").replace(" ", "%s")))

    def _send_entry(self):
        self._send(self.entry.get())
        self.entry.delete(0, "end")

    def _send_clipboard(self):
        try:
            self._send(self.root.clipboard_get())
        except tk.TclError:   # clipboard empty or not text
            pass

    def _volume_released(self, _e=None):
        self.dragging_volume = False
        if not self.volume_known:   # never push the slider's placeholder 0 to the TV
            return
        level = int(self.volume.get())
        self.vol_text.set(f"Volume {level}")
        self.link.shell(f"cmd media_session volume --stream 3 --set {level}")
        self.root.after(600, self.monitor.poke)

    def _toggle_preview(self):
        on = not self.monitor.preview
        self.monitor.preview = on
        self.preview_btn.set_base(ACCENT if on else BTN)
        if on:
            self.preview_label.config(image=_PIXEL, text="Loading the TV screen…",
                                      width=int(256 * SCALE), height=int(144 * SCALE))
            self.monitor.wake.set()
        else:
            self.frame_img = None
        self._layout()

    def _toggle_pin(self):
        self.pinned = not self.pinned
        self.root.attributes("-topmost", self.pinned)
        self.pin_btn.set_base(BG, ACCENT if self.pinned else DIM)

    def _toggle_mini(self):
        self.mini = not self.mini
        self.mini_btn.set_base(BG, ACCENT if self.mini else DIM)
        if self.mini and not self.pinned:
            self._toggle_pin()   # a mini remote is for keeping on top of other windows
        self._layout()

    # ---- layout

    def _section(self, name, **pack):
        f = tk.Frame(self.root, bg=BG)
        self.sections.append((name, f, pack))
        return f

    def _layout(self):
        """(Re)pack sections in order; mini mode keeps only the essentials."""
        mini_keep = {"top", "dpad", "nav", "volkeys"}
        for _, f, _ in self.sections:
            f.pack_forget()
        for name, f, pack in self.sections:
            if self.mini and name not in mini_keep:
                continue
            if name == "preview" and not self.monitor.preview:
                continue
            f.pack(**pack)

    def _build(self):
        self.sections = []
        px = dict(padx=14)

        # status bar, pin, mini, address
        top = self._section("top", fill="x", pady=(10, 4), **px)
        self.status_canvas = tk.Canvas(top, width=10, height=10, bg=BG, highlightthickness=0)
        self.status_dot = self.status_canvas.create_oval(1, 1, 9, 9, fill=AMBER, outline="")
        self.status_canvas.pack(side="left")
        tk.Label(top, textvariable=self.status_text, bg=BG, fg=DIM, font=SMALL).pack(side="left", padx=6)
        Button(top, "⚙", self._edit_host, w=26, h=26, bg=BG, fg=DIM, font=ICON).pack(side="right")
        self.mini_btn = Button(top, "Mini", self._toggle_mini, w=38, h=26, bg=BG, fg=DIM, font=SMALL)
        self.mini_btn.pack(side="right")
        self.pin_btn = Button(top, "Pin", self._toggle_pin, w=34, h=26, bg=BG, fg=DIM, font=SMALL)
        self.pin_btn.pack(side="right")

        # power / input / settings
        row = self._section("power", fill="x", pady=6, **px)
        Button(row, "⏻", self.k("KEYCODE_POWER"), w=56, bg=RED, font=ICON).pack(side="left")
        Button(row, "Settings", lambda: self.link.shell("am start -a android.settings.SETTINGS"),
               w=84).pack(side="right")
        Button(row, "Input", self.k("KEYCODE_TV_INPUT"), w=72).pack(side="right", padx=6)

        # screen preview
        prev = self._section("preview", pady=(6, 0), **px)
        self.preview_label = tk.Label(prev, bg=PANEL, fg=DIM, font=SMALL, compound="center")
        self.preview_label.pack()
        tk.Label(prev, text="Netflix, Prime and Hotstar show black (copy protection)",
                 bg=BG, fg=DIM, font=("Segoe UI", 8), wraplength=int(240 * SCALE)).pack(pady=(2, 0))

        # D-pad
        holder = self._section("dpad", pady=8, **px)
        pad_frame = tk.Frame(holder, bg=PANEL, padx=10, pady=10)
        pad_frame.pack()
        dp = dict(w=64, h=56, font=ICON, repeat=True)
        Button(pad_frame, "▲", self.k("KEYCODE_DPAD_UP"), **dp).grid(row=0, column=1, pady=3)
        Button(pad_frame, "◀", self.k("KEYCODE_DPAD_LEFT"), **dp).grid(row=1, column=0, padx=3)
        Button(pad_frame, "OK", self.k("KEYCODE_DPAD_CENTER"), w=64, h=56, bg=ACCENT).grid(row=1, column=1)
        Button(pad_frame, "▶", self.k("KEYCODE_DPAD_RIGHT"), **dp).grid(row=1, column=2, padx=3)
        Button(pad_frame, "▼", self.k("KEYCODE_DPAD_DOWN"), **dp).grid(row=2, column=1, pady=3)

        # back / home / menu
        row = self._section("nav", pady=4, **px)
        for text, code in (("Back", "KEYCODE_BACK"), ("Home", "KEYCODE_HOME"), ("Menu", "KEYCODE_MENU")):
            Button(row, text, self.k(code), w=72).pack(side="left", padx=3)

        # media
        row = self._section("media", pady=(10, 4), **px)
        for text, code in (("⏪", "KEYCODE_MEDIA_REWIND"), ("⏯", "KEYCODE_MEDIA_PLAY_PAUSE"),
                           ("⏩", "KEYCODE_MEDIA_FAST_FORWARD")):
            Button(row, text, self.k(code), w=72, h=44, font=ICON).pack(side="left", padx=3)

        # volume keys
        row = self._section("volkeys", pady=4, **px)
        Button(row, "Vol −", self.kv("KEYCODE_VOLUME_DOWN"), w=72, repeat=True).pack(side="left", padx=3)
        Button(row, "Mute", self.kv("KEYCODE_VOLUME_MUTE"), w=72).pack(side="left", padx=3)
        Button(row, "Vol +", self.kv("KEYCODE_VOLUME_UP"), w=72, repeat=True).pack(side="left", padx=3)

        # volume level
        row = self._section("volume", fill="x", pady=(2, 0), padx=17)
        tk.Label(row, textvariable=self.vol_text, bg=BG, fg=DIM, font=SMALL, width=10, anchor="w").pack(side="left")
        self.volume = tk.Scale(row, from_=0, to=100, orient="horizontal", showvalue=False, bg=BG,
                               troughcolor=BTN, activebackground=ACCENT, highlightthickness=0, bd=0,
                               sliderrelief="flat", sliderlength=int(16 * SCALE), width=int(10 * SCALE))
        self.volume.pack(side="left", fill="x", expand=True)
        self.volume.bind("<ButtonPress-1>", lambda e: setattr(self, "dragging_volume", True))
        self.volume.bind("<ButtonRelease-1>", self._volume_released)
        self.volume.bind("<B1-Motion>", lambda e: self.vol_text.set(f"Volume {int(self.volume.get())}"))

        # apps
        sec = self._section("apps", fill="x", **px)
        tk.Label(sec, text="APPS", bg=BG, fg=DIM, font=CAPTION).pack(anchor="w", pady=(10, 2))
        grid = tk.Frame(sec, bg=BG)
        grid.pack()
        for i, (label, pkg) in enumerate(cfg["apps"]):
            Button(grid, label, lambda p=pkg: self._launch(p), w=111, h=40).grid(
                row=i // 2, column=i % 2, padx=3, pady=3)

        # type or send a link
        sec = self._section("send", fill="x", pady=(0, 6), **px)
        tk.Label(sec, text="TYPE ON TV OR SEND A LINK", bg=BG, fg=DIM, font=CAPTION).pack(anchor="w", pady=(10, 2))
        row = tk.Frame(sec, bg=BG)
        row.pack(fill="x")
        self.entry = tk.Entry(row, bg=BTN, fg=FG, insertbackground=FG, relief="flat", font=FONT, width=16)
        self.entry.pack(side="left", fill="x", expand=True, ipady=6)
        self.entry.bind("<Return>", lambda e: self._send_entry())
        Button(row, "Send", self._send_entry, w=52, h=34, bg=ACCENT).pack(side="left", padx=(6, 0))
        Button(sec, "Send clipboard to TV", self._send_clipboard, w=234, h=32, font=SMALL).pack(pady=(6, 0))

        # preview toggle
        row = self._section("previewbtn", pady=(4, 14), **px)
        self.preview_btn = Button(row, "Show TV screen", self._toggle_preview, w=234, h=32, font=SMALL)
        self.preview_btn.pack()

        self._layout()

    def _edit_host(self):
        win = tk.Toplevel(self.root, bg=BG, padx=14, pady=12)
        win.title("TV address")
        win.resizable(False, False)
        win.transient(self.root)
        tk.Label(win, text="TV IP address", bg=BG, fg=DIM, font=FONT).pack(anchor="w")
        e = tk.Entry(win, bg=BTN, fg=FG, insertbackground=FG, relief="flat", font=FONT, width=22)
        e.insert(0, cfg["host"])
        e.pack(pady=6, ipady=5)
        e.focus_set()

        def apply(_ev=None):
            host = e.get().strip()
            if host and host != cfg["host"]:
                cfg["host"] = host
                cfg["mac"] = ""   # a different TV, perhaps: learn its MAC again
                save_config()
                self.link.reconnect()
                self.monitor.reset()
            win.destroy()

        e.bind("<Return>", apply)
        Button(win, "Connect", apply, w=110, h=34, bg=ACCENT).pack(pady=(4, 0))

    def _bind_keys(self):
        keys = {
            "Up": "KEYCODE_DPAD_UP", "Down": "KEYCODE_DPAD_DOWN", "Left": "KEYCODE_DPAD_LEFT",
            "Right": "KEYCODE_DPAD_RIGHT", "Return": "KEYCODE_DPAD_CENTER", "BackSpace": "KEYCODE_BACK",
            "Escape": "KEYCODE_BACK", "h": "KEYCODE_HOME", "space": "KEYCODE_MEDIA_PLAY_PAUSE",
            "comma": "KEYCODE_MEDIA_REWIND", "period": "KEYCODE_MEDIA_FAST_FORWARD",
        }
        volume_keys = {"plus": "KEYCODE_VOLUME_UP", "equal": "KEYCODE_VOLUME_UP",
                       "minus": "KEYCODE_VOLUME_DOWN", "m": "KEYCODE_VOLUME_MUTE"}

        def on_key(event):
            if isinstance(event.widget, tk.Entry):   # typing in a text box, not remote keys
                return
            if event.keysym in volume_keys:
                self.kv(volume_keys[event.keysym])()
                return "break"
            code = keys.get(event.keysym)
            if code:
                self.link.key(code)
                return "break"

        self.root.bind_all("<Key>", on_key)


def main():
    try:   # crisp text on high-DPI screens; must happen before the window exists
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass
    root = tk.Tk()
    global SCALE, _PIXEL
    SCALE = max(1.0, root.winfo_fpixels("1i") / 96.0)
    _PIXEL = tk.PhotoImage(width=1, height=1)
    RemoteApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
