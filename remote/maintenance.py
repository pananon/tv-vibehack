"""TV upkeep for the remote: health readout, cache cleanup, and backup/restore of the TV's setup.

Every function takes a connected adb_shell device and blocks, so call them off the UI thread.
"""
import base64
import datetime
import json
import os
import re
import shutil

LAUNCHER = "com.gathin.tvhome"
STOCK_LAUNCHER = "com.google.android.tvlauncher"
DOCK_PREFS = "shared_prefs/dock.xml"
BACKUPS_KEPT = 14

# Settings a restore puts back: (namespace, key).
RESTORED_SETTINGS = [
    ("secure", "screensaver_enabled"),
    ("secure", "screensaver_components"),
    ("system", "screen_off_timeout"),
    ("global", "always_finish_activities"),
]


def _sh(dev, cmd, timeout=60.0):
    return dev.shell(cmd, read_timeout_s=timeout, transport_timeout_s=timeout)


# ---- health --------------------------------------------------------------------------------------


def health(dev):
    """Storage, memory and the apps using the most of each."""
    out = {}
    df = _sh(dev, "df -k /data | tail -1").split()
    if len(df) >= 4:
        out["storage"] = {"total_kb": int(df[1]), "used_kb": int(df[2]), "free_kb": int(df[3])}
    mem = dict(re.findall(r"^(\w+):\s+(\d+) kB", _sh(dev, "cat /proc/meminfo"), re.M))
    if "MemTotal" in mem:
        out["memory"] = {"total_kb": int(mem["MemTotal"]), "available_kb": int(mem.get("MemAvailable", 0))}

    meminfo = _sh(dev, "dumpsys meminfo", timeout=90.0)
    block = meminfo.split("Total PSS by process:", 1)[-1].split("\n\n", 1)[0] if "Total PSS by process:" in meminfo else ""
    procs = re.findall(r"([\d,]+)K: ([\w.:]+) \(pid", block)
    out["top_memory"] = [(_short(name), int(kb.replace(",", ""))) for kb, name in procs[:6]]

    stats = _sh(dev, "dumpsys diskstats", timeout=60.0)

    def arr(name):
        m = re.search(r"%s:\s*\[([^\]]*)\]" % re.escape(name), stats)
        return json.loads("[" + m.group(1) + "]") if m else []

    names, app, data = arr("Package Names"), arr("App Sizes"), arr("App Data Sizes")
    # diskstats sizes are bytes; report MB
    sizes = sorted(((n, (a + d) // 1048576) for n, a, d in zip(names, app, data)), key=lambda r: -r[1])
    out["top_storage"] = sizes[:6]
    return out


def _short(process):
    """com.foo:sandboxed_process1:org.chromium... -> com.foo (helper)"""
    return process.split(":", 1)[0] + " (helper)" if ":" in process else process


# ---- cleanup -------------------------------------------------------------------------------------


def read_debloat_list(path):
    pkgs = []
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                pkg = line.split("#", 1)[0].strip()
                if pkg:
                    pkgs.append(pkg)
    except OSError:
        pass
    return pkgs


def cleanup(dev, debloat_pkgs):
    """Clears app caches and reports disabled apps that came back (e.g. after a firmware update)."""
    before = _free_kb(dev)
    _sh(dev, "pm trim-caches 999G", timeout=120.0)
    after = _free_kb(dev)
    watch = list(dict.fromkeys(debloat_pkgs + [STOCK_LAUNCHER]))
    installed = set(_packages(dev, ""))
    disabled = set(_packages(dev, "-d"))
    came_back = [p for p in watch if p in installed and p not in disabled]
    return {"freed_kb": max(0, after - before), "came_back": came_back}


def disable(dev, pkgs):
    # One pm call per package: chained pm commands can stop after the first on some TVs.
    return {p: _sh(dev, f"pm disable-user --user 0 {p}").strip() for p in pkgs}


def _free_kb(dev):
    parts = _sh(dev, "df -k /data | tail -1").split()
    return int(parts[3]) if len(parts) >= 4 else 0


def _packages(dev, flag):
    return [l.replace("package:", "").strip() for l in _sh(dev, f"pm list packages {flag}").splitlines() if l.strip()]


# ---- backup / restore ----------------------------------------------------------------------------


def backup(dev, root):
    """Saves the TV's setup to root/<timestamp>/ and keeps the newest BACKUPS_KEPT backups."""
    stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H%M")
    folder = os.path.join(root, stamp)
    os.makedirs(folder, exist_ok=True)
    summary = {"time": stamp, "settings": {}, "disabled": _packages(dev, "-d")}
    for ns, key in RESTORED_SETTINGS:
        summary["settings"][f"{ns}/{key}"] = _sh(dev, f"settings get {ns} {key}").strip()
    summary["home"] = _sh(dev, "cmd package resolve-activity --brief -a android.intent.action.MAIN "
                               "-c android.intent.category.HOME | tail -1").strip()
    dock = _sh(dev, f"run-as {LAUNCHER} cat {DOCK_PREFS} 2>/dev/null")
    summary["dock_saved"] = dock.lstrip().startswith("<?xml")
    if summary["dock_saved"]:
        # newline="": keep the TV's line endings, so a restore writes back exactly what was saved.
        with open(os.path.join(folder, "dock.xml"), "w", encoding="utf-8", newline="") as f:
            f.write(dock)
    # Full settings dumps, for reference only (a restore never replays these wholesale).
    for ns in ("global", "secure", "system"):
        with open(os.path.join(folder, f"settings-{ns}.txt"), "w", encoding="utf-8") as f:
            f.write(_sh(dev, f"settings list {ns}"))
    with open(os.path.join(folder, "backup.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
    _prune(root)
    return folder


def list_backups(root):
    try:
        names = [n for n in os.listdir(root) if os.path.isfile(os.path.join(root, n, "backup.json"))]
    except OSError:
        return []
    return sorted(names, reverse=True)


def _prune(root):
    for old in list_backups(root)[BACKUPS_KEPT:]:
        shutil.rmtree(os.path.join(root, old), ignore_errors=True)


def restore(dev, folder):
    """Puts back the dock, the disabled apps, the screensaver and timeout settings, and the home screen.

    Returns a list of what was done. Needs the launcher installed (after a factory reset, run
    launcher/build-and-install first).
    """
    with open(os.path.join(folder, "backup.json"), encoding="utf-8") as f:
        summary = json.load(f)
    if LAUNCHER not in _packages(dev, ""):
        raise RuntimeError("The launcher isn't installed on the TV. Run launcher\\build-and-install.ps1 first.")
    done = []
    dock_file = os.path.join(folder, "dock.xml")
    if os.path.exists(dock_file):
        with open(dock_file, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()
        _sh(dev, f"am force-stop {LAUNCHER}")
        _sh(dev, f"echo {b64} | base64 -d | run-as {LAUNCHER} sh -c 'mkdir -p shared_prefs && cat > {DOCK_PREFS}'")
        done.append("Dock order")
    disabled_now = set(_packages(dev, "-d"))
    to_disable = [p for p in summary.get("disabled", []) if p not in disabled_now and p != LAUNCHER]
    if to_disable:
        disable(dev, to_disable)
        done.append(f"Disabled {len(to_disable)} app(s) again")
    for name, value in summary.get("settings", {}).items():
        ns, key = name.split("/", 1)
        if value and value != "null":
            _sh(dev, f"settings put {ns} {key} '{value}'")
    done.append("Screensaver and timeout settings")
    _sh(dev, f"cmd package set-home-activity --user 0 {LAUNCHER}/.MainActivity")
    _sh(dev, "input keyevent KEYCODE_HOME")
    done.append("Home screen")
    return done
