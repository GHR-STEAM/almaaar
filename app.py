"""Chromecast Hub - لوحة تحكم محلية لأجهزة Android TV / Google TV عبر ADB."""
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

BASE = Path(__file__).parent
app = FastAPI(title="Chromecast Hub")

CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0
STATE = {"serial": None}

# حزم النظام الحرجة: ممنوع تعطيلها أو حذفها من اللوحة
PROTECTED_PREFIXES = (
    "android",
    "com.android.",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.google.android.packageinstaller",
    "com.google.android.permissioncontroller",
    "com.google.android.tvlauncher",
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tv.provision",
    "com.google.android.inputmethod",
)
STOCK_LAUNCHERS = (
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tvlauncher",
)

PKG_RE = re.compile(r"^[A-Za-z0-9_.]{2,200}$")
HOST_RE = re.compile(r"^[A-Za-z0-9.\-]{1,100}$")
PATH_RE = re.compile(r"^/[\w\-. /()@+\[\]]*$")


def find_adb():
    exe = "adb.exe" if os.name == "nt" else "adb"
    local = BASE / "platform-tools" / exe
    if local.exists():
        return str(local)
    return shutil.which("adb")


ADB = find_adb()


def adb(args, timeout=30, binary=False):
    if not ADB:
        raise HTTPException(500, "ADB غير موجود. شغّل START.bat ليحمّله تلقائياً.")
    try:
        p = subprocess.run(
            [ADB] + args,
            capture_output=True,
            timeout=timeout,
            creationflags=CREATE_NO_WINDOW,
        )
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "انتهت مهلة الأمر")
    if binary:
        return p.returncode, p.stdout
    out = p.stdout.decode("utf-8", "replace")
    err = p.stderr.decode("utf-8", "replace")
    return p.returncode, (out + ("\n" + err if err.strip() else "")).strip()


def need_device():
    if not STATE["serial"]:
        raise HTTPException(400, "لا يوجد جهاز متصل. اتصل بالجهاز أولاً من تبويب الاتصال.")
    return STATE["serial"]


def sh(cmd, timeout=30):
    serial = need_device()
    return adb(["-s", serial, "shell", cmd], timeout=timeout)


def check_pkg(pkg):
    if not PKG_RE.match(pkg):
        raise HTTPException(400, "اسم حزمة غير صالح")
    return pkg


def is_protected(pkg):
    for p in PROTECTED_PREFIXES:
        if pkg == p or pkg.startswith(p.rstrip(".") + "."):
            return True
    return False


def check_path(path):
    if not PATH_RE.match(path) or ".." in path:
        raise HTTPException(400, "مسار غير صالح")
    return path


def list_devices():
    rc, out = adb(["devices", "-l"])
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] in ("device", "unauthorized", "offline"):
            devices.append({"serial": parts[0], "state": parts[1], "info": " ".join(parts[2:])})
    return devices


# ---------------------------------------------------------------- الاتصال
class ConnectIn(BaseModel):
    ip: str
    port: int = 5555


class PairIn(BaseModel):
    ip: str
    port: int
    code: str


@app.get("/api/status")
def status():
    devices = list_devices() if ADB else []
    serials = [d["serial"] for d in devices if d["state"] == "device"]
    if STATE["serial"] not in serials:
        STATE["serial"] = serials[0] if serials else None
    info = None
    if STATE["serial"]:
        rc, out = sh(
            "getprop ro.product.model; getprop ro.build.version.release; "
            "getprop ro.build.version.sdk; getprop ro.product.manufacturer"
        )
        p = out.splitlines()
        if len(p) >= 4:
            info = {"model": p[0], "android": p[1], "sdk": p[2], "maker": p[3]}
    return {"adb": bool(ADB), "devices": devices, "selected": STATE["serial"], "info": info}


@app.post("/api/connect")
def connect(body: ConnectIn):
    if not HOST_RE.match(body.ip):
        raise HTTPException(400, "عنوان IP غير صالح")
    rc, out = adb(["connect", f"{body.ip}:{body.port}"], timeout=15)
    ok = ("connected to" in out) and ("failed" not in out) and ("cannot" not in out)
    if ok:
        STATE["serial"] = f"{body.ip}:{body.port}"
    return {"ok": ok, "output": out}


@app.post("/api/pair")
def pair(body: PairIn):
    if not HOST_RE.match(body.ip) or not re.match(r"^\d{4,8}$", body.code):
        raise HTTPException(400, "بيانات الاقتران غير صالحة")
    rc, out = adb(["pair", f"{body.ip}:{body.port}", body.code], timeout=20)
    return {"ok": "Successfully paired" in out, "output": out}


@app.post("/api/disconnect")
def disconnect():
    serial = STATE["serial"]
    if serial:
        adb(["disconnect", serial])
    STATE["serial"] = None
    return {"ok": True}


@app.post("/api/select/{serial}")
def select(serial: str):
    if serial not in [d["serial"] for d in list_devices()]:
        raise HTTPException(404, "الجهاز غير موجود")
    STATE["serial"] = serial
    return {"ok": True}


# ---------------------------------------------------------------- المراقبة
@app.get("/api/stats")
def stats():
    cmd = (
        "head -1 /proc/stat; sleep 0.5; head -1 /proc/stat; echo ---; "
        "head -5 /proc/meminfo; echo ---; df /data | tail -1; echo ---; "
        "cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | head -4; echo ---; "
        "cat /proc/uptime"
    )
    rc, out = sh(cmd, timeout=20)
    sec = out.split("---")
    if len(sec) < 5:
        raise HTTPException(500, "تعذر قراءة حالة الجهاز")

    def cpu_vals(line):
        v = [int(x) for x in line.split()[1:9]]
        idle = v[3] + v[4]
        return sum(v), idle

    lines = [l for l in sec[0].strip().splitlines() if l.startswith("cpu")]
    cpu = None
    if len(lines) >= 2:
        t1, i1 = cpu_vals(lines[0])
        t2, i2 = cpu_vals(lines[1])
        if t2 > t1:
            cpu = round(100 * ((t2 - t1) - (i2 - i1)) / (t2 - t1), 1)

    mem = {}
    for l in sec[1].strip().splitlines():
        m = re.match(r"(\w+):\s+(\d+)", l)
        if m:
            mem[m.group(1)] = int(m.group(2))
    total = mem.get("MemTotal", 0)
    avail = mem.get("MemAvailable", mem.get("MemFree", 0))

    storage = None
    df = sec[2].split()
    if len(df) >= 4:
        try:
            storage = {"total_kb": int(df[1]), "used_kb": int(df[2]), "free_kb": int(df[3])}
        except ValueError:
            pass

    temps = []
    for l in sec[3].split():
        try:
            v = int(l)
            temps.append(v / 1000 if abs(v) > 1000 else v)
        except ValueError:
            pass
    if not temps:
        # أندرويد الحديث يمنع قراءة thermal_zone من shell؛ نقرأ من thermalservice
        try:
            _, tout = sh("dumpsys thermalservice 2>/dev/null | grep -i mValue", timeout=15)
            for m in re.finditer(r"mValue=(-?\d+(?:\.\d+)?)", tout):
                v = float(m.group(1))
                if 0 < v < 150:
                    temps.append(v)
        except Exception:
            pass
    uptime = None
    try:
        uptime = int(float(sec[4].split()[0]))
    except (ValueError, IndexError):
        pass

    return {
        "cpu_percent": cpu,
        "mem_total_kb": total,
        "mem_used_kb": total - avail,
        "storage": storage,
        "temp_c": round(max(temps), 1) if temps else None,
        "uptime_s": uptime,
    }


@app.get("/api/top")
def top():
    rc, out = sh("top -b -n 1 -m 15", timeout=20)
    if rc != 0 or len(out.strip().splitlines()) < 4:
        rc, out = sh("dumpsys cpuinfo | head -25", timeout=20)
    return {"output": out}


# ---------------------------------------------------------------- التطبيقات
@app.get("/api/apps")
def apps(kind: str = "user"):
    flags = {"user": "-3", "system": "-s", "disabled": "-d", "all": ""}
    if kind not in flags:
        raise HTTPException(400, "نوع غير صالح")
    rc, out = sh(f"pm list packages {flags[kind]}", timeout=30)
    pkgs = sorted(l.replace("package:", "").strip() for l in out.splitlines() if l.startswith("package:"))
    return {"apps": [{"pkg": p, "protected": is_protected(p)} for p in pkgs]}


class AppIn(BaseModel):
    pkg: str
    action: str


@app.post("/api/app")
def app_action(body: AppIn):
    pkg = check_pkg(body.pkg)
    a = body.action
    if a == "force-stop":
        rc, out = sh(f"am force-stop {pkg}")
    elif a == "launch":
        rc, out = sh(f"monkey -p {pkg} -c android.intent.category.LEANBACK_LAUNCHER 1")
    elif a == "enable":
        rc, out = sh(f"pm enable {pkg}")
    elif a == "restore":
        rc, out = sh(f"cmd package install-existing {pkg}")
    elif a in ("disable", "uninstall", "clear-data"):
        if is_protected(pkg):
            raise HTTPException(403, "هذي حزمة نظام حرجة، محمية من التعطيل والحذف.")
        if a == "disable":
            rc, out = sh(f"pm disable-user --user 0 {pkg}")
        elif a == "uninstall":
            rc, out = sh(f"pm uninstall -k --user 0 {pkg}")
        else:
            rc, out = sh(f"pm clear {pkg}")
    else:
        raise HTTPException(400, "إجراء غير معروف")
    return {"ok": rc == 0, "output": out}


@app.post("/api/trim-caches")
def trim_caches():
    rc, out = sh("pm trim-caches 999999999999", timeout=60)
    return {"ok": rc == 0, "output": out or "تم تنظيف الكاش"}


@app.post("/api/install")
async def install(file: UploadFile = File(...)):
    serial = need_device()
    if not file.filename.lower().endswith(".apk"):
        raise HTTPException(400, "الملف لازم يكون APK")
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / "app.apk"
        p.write_bytes(await file.read())
        rc, out = adb(["-s", serial, "install", "-r", str(p)], timeout=240)
    return {"ok": "Success" in out, "output": out}


# ---------------------------------------------------------------- السجلات
@app.get("/api/logcat")
def logcat(mode: str = "all", level: str = "W", lines: int = 300, q: str = ""):
    lines = max(50, min(lines, 3000))
    if level not in list("VDIWEF"):
        raise HTTPException(400, "مستوى غير صالح")
    if mode == "crash":
        cmd = f"logcat -d -b crash -t {lines}"
    elif mode == "anr":
        cmd = f"logcat -d -b main,system -t 3000 | grep -E 'ANR in|Application Not Responding'"
    else:
        cmd = f"logcat -d -t {lines} '*:{level}'"
    rc, out = sh(cmd, timeout=40)
    if q:
        ql = q.lower()
        out = "\n".join(l for l in out.splitlines() if ql in l.lower())
    return {"output": out or "لا توجد نتائج"}


@app.post("/api/logcat/clear")
def logcat_clear():
    rc, out = sh("logcat -c")
    return {"ok": rc == 0}


# ---------------------------------------------------------------- الملفات
@app.get("/api/files")
def files(path: str = "/sdcard/"):
    check_path(path)
    if not path.endswith("/"):
        path += "/"
    rc, out = sh(f"ls -1p '{path}'")
    if rc != 0:
        raise HTTPException(400, out or "تعذر فتح المسار")
    items = [l for l in out.splitlines() if l.strip()]
    return {
        "path": path,
        "items": [{"name": i.rstrip("/"), "dir": i.endswith("/")} for i in items],
    }


@app.get("/api/files/download")
def download(path: str):
    serial = need_device()
    check_path(path)
    d = tempfile.mkdtemp()
    dest = Path(d) / Path(path).name
    rc, out = adb(["-s", serial, "pull", path, str(dest)], timeout=300)
    if rc != 0 or not dest.exists():
        raise HTTPException(400, out or "فشل التحميل")
    return FileResponse(dest, filename=dest.name)


@app.post("/api/files/upload")
async def upload(path: str = Form("/sdcard/"), file: UploadFile = File(...)):
    serial = need_device()
    check_path(path)
    if not path.endswith("/"):
        path += "/"
    name = re.sub(r"[^\w\-. ]", "_", file.filename or "file")
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / name
        p.write_bytes(await file.read())
        rc, out = adb(["-s", serial, "push", str(p), path + name], timeout=600)
    return {"ok": rc == 0, "output": out}


# ---------------------------------------------------------------- أدوات
@app.get("/api/screenshot")
def screenshot():
    serial = need_device()
    rc, data = adb(["-s", serial, "exec-out", "screencap", "-p"], timeout=30, binary=True)
    if rc != 0 or not data:
        raise HTTPException(500, "فشل أخذ اللقطة")
    return Response(content=data, media_type="image/png")


@app.get("/api/launchers")
def launchers():
    rc, out = sh(
        "cmd package query-activities --brief -a android.intent.action.MAIN "
        "-c android.intent.category.HOME"
    )
    pkgs = sorted({l.split("/")[0].strip() for l in out.splitlines() if "/" in l})
    return {"launchers": pkgs, "stock": [p for p in pkgs if p in STOCK_LAUNCHERS]}


@app.post("/api/launcher/{action}")
def launcher_action(action: str):
    info = launchers()
    stock_present = info["stock"]
    if action == "disable-stock":
        others = [p for p in info["launchers"] if p not in STOCK_LAUNCHERS]
        if not others:
            raise HTTPException(
                400, "ما فيه لانشر بديل مثبّت. ثبّت واحد أولاً (مثل Projectivy) ثم أعد المحاولة."
            )
        outs = [sh(f"pm disable-user --user 0 {p}")[1] for p in stock_present]
        return {"ok": True, "output": "\n".join(outs) or "تم", "alternatives": others}
    if action == "enable-stock":
        outs = [sh(f"pm enable {p}")[1] for p in STOCK_LAUNCHERS]
        return {"ok": True, "output": "\n".join(outs)}
    raise HTTPException(400, "إجراء غير معروف")


@app.post("/api/key/{name}")
def key(name: str):
    keys = {"home": 3, "back": 4, "up": 19, "down": 20, "left": 21, "right": 22, "ok": 23, "menu": 82}
    if name not in keys:
        raise HTTPException(400, "زر غير معروف")
    rc, out = sh(f"input keyevent {keys[name]}")
    return {"ok": rc == 0}


@app.post("/api/reboot")
def reboot():
    serial = need_device()
    adb(["-s", serial, "reboot"], timeout=15)
    return {"ok": True}


@app.exception_handler(HTTPException)
async def http_exc(request, exc):
    return JSONResponse({"detail": exc.detail}, status_code=exc.status_code)


app.mount("/", StaticFiles(directory=str(BASE / "static"), html=True), name="static")
