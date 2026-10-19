#!/usr/bin/env python3
"""Virtual Clay: сборщик одной командой.  python build.py
Ставит зависимости, Android SDK, Gradle и собирает VirtualClay.apk (armeabi-v7a + arm64-v8a в одном APK).
Работает в Termux; на обычном Linux тоже (нужен JDK 17).
Опции: --release  release-сборка (подпись debug-ключом) | --install  поставить APK через adb, если он есть
"""
import json, os, platform, shutil, stat, subprocess, sys, urllib.request, zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
HOME = os.path.expanduser("~")
PREFIX = os.environ.get("PREFIX", "")
TERMUX = bool(PREFIX) and os.path.isdir(PREFIX) and "termux" in PREFIX
TMP = os.environ.get("TMPDIR", "/tmp") + "/virtualclay_build"
SDK = os.path.join(HOME, "android-sdk")
GRADLE_VER = "8.9"
GRADLE = os.path.join(HOME, f"gradle-{GRADLE_VER}")
IS_ARM = platform.machine().lower() in ("aarch64", "arm64", "armv8l", "armv7l")
CMDTOOLS = "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
UA = {"User-Agent": "VirtualClay-builder"}
os.makedirs(TMP, exist_ok=True)


def log(m): print(f"\n\033[1;36m==> {m}\033[0m", flush=True)
def die(m): print(f"\n\033[1;31mОШИБКА: {m}\033[0m"); sys.exit(1)


def run(cmd, **kw):
    print("$", " ".join(cmd) if isinstance(cmd, list) else cmd, flush=True)
    r = subprocess.run(cmd, **kw)
    if r.returncode != 0:
        die(f"команда завершилась с кодом {r.returncode}")


def download(url, dest):
    print(f"  скачиваю {url}", flush=True)
    req = urllib.request.Request(url, headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=60) as r, open(dest, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0); got = 0
            while True:
                b = r.read(1 << 16)
                if not b: break
                f.write(b); got += len(b)
                if total: print(f"\r  {got * 100 // total}% ({got >> 20} MB)", end="", flush=True)
        print()
    except Exception as e:
        die(f"не скачалось {url}: {e}")


def unzip_member(zpath, member_suffix, dest, exe=False):
    with zipfile.ZipFile(zpath) as z:
        names = [n for n in z.namelist() if n == member_suffix or n.endswith("/" + member_suffix)]
        if not names: die(f"в {os.path.basename(zpath)} нет {member_suffix}")
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with z.open(names[0]) as s, open(dest, "wb") as d: shutil.copyfileobj(s, d)
    if exe: os.chmod(dest, os.stat(dest).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


# ---------- 1. зависимости ----------
def deps():
    log("Зависимости")
    if TERMUX:
        run(["pkg", "install", "-y", "openjdk-17", "aapt2"])
        java = os.path.join(PREFIX, "lib/jvm/java-17-openjdk")
    else:
        java = os.environ.get("JAVA_HOME", "")
        if not java and shutil.which("java"):
            java = os.path.dirname(os.path.dirname(os.path.realpath(shutil.which("java"))))
    if not java or not os.path.isdir(java):
        die("JDK 17 не найден (в Termux: pkg install openjdk-17; на Linux задай JAVA_HOME)")
    os.environ["JAVA_HOME"] = java
    os.environ["PATH"] = os.path.join(java, "bin") + os.pathsep + os.environ["PATH"]
    return java


# ---------- 3. SDK + Gradle ----------
def sdk():
    log("Android SDK")
    sm = f"{SDK}/cmdline-tools/latest/bin/sdkmanager"
    if not os.path.isfile(sm):
        z = f"{TMP}/cmdtools.zip"; download(CMDTOOLS, z)
        ex = f"{TMP}/ct"; shutil.rmtree(ex, ignore_errors=True)
        with zipfile.ZipFile(z) as zf: zf.extractall(ex)
        os.makedirs(f"{SDK}/cmdline-tools", exist_ok=True)
        shutil.move(f"{ex}/cmdline-tools", f"{SDK}/cmdline-tools/latest")
        for root, _, files in os.walk(f"{SDK}/cmdline-tools/latest/bin"):
            for f in files: os.chmod(os.path.join(root, f), 0o755)
    env = dict(os.environ, ANDROID_HOME=SDK)
    subprocess.run(f"yes | {sm} --licenses > /dev/null", shell=True, env=env)
    if not (os.path.isdir(f"{SDK}/platforms/android-34") and os.path.isdir(f"{SDK}/build-tools/34.0.0")):
        run([sm, "platforms;android-34", "build-tools;34.0.0"], env=env)


def gradle():
    log(f"Gradle {GRADLE_VER}")
    if not os.path.isfile(f"{GRADLE}/bin/gradle"):
        z = f"{TMP}/gradle.zip"
        download(f"https://services.gradle.org/distributions/gradle-{GRADLE_VER}-bin.zip", z)
        with zipfile.ZipFile(z) as zf: zf.extractall(HOME)
        os.chmod(f"{GRADLE}/bin/gradle", 0o755)


# ---------- 4. aapt2 для ARM ----------
def aapt2():
    """Gradle качает x86_64-aapt2 — на телефоне (ARM) он не запускается. Нужен свой, нативный."""
    if not IS_ARM: return None
    log("Нативный aapt2 (для ARM)")
    p = os.path.join(PREFIX, "bin/aapt2") if TERMUX else shutil.which("aapt2")
    if not p and not TERMUX and shutil.which("apt-get"):
        subprocess.run(["apt-get", "update"])
        subprocess.run(["apt-get", "install", "-y", "aapt"])
        p = shutil.which("aapt2")
    if not p:
        die("aapt2 не найден. Debian/Ubuntu: apt install aapt (или google-android-build-tools-installer); Termux: pkg install aapt2")
    r = subprocess.run([p, "version"], capture_output=True, text=True)
    if r.returncode != 0: die(f"aapt2 не запускается: {r.stderr.strip()}")
    print("  ", r.stdout.strip(), "->", p)
    return p


# ---------- 5. рабочая папка ----------
def workdir():
    """sdcard (fuse) ломает права/симлинки — собираем во внутренней памяти."""
    if ROOT.startswith(("/storage/", "/sdcard", "/mnt/sdcard", "/mnt/user", "/data/media")):
        w = f"{HOME}/virtualclay_work"
        log(f"Копирую проект во внутреннюю память: {w}")
        shutil.rmtree(w + "/app/src", ignore_errors=True)
        shutil.copytree(ROOT, w, dirs_exist_ok=True, ignore=shutil.ignore_patterns("build", ".gradle", "*.apk", "*.zip"))
        clean_foreign(w)
        return w
    clean_foreign(ROOT)
    return ROOT


KNOWN = {"Secret.kt", "Ui.kt", "Admin.kt", "ClayDnsService.kt", "MainActivity.kt"}


def clean_foreign(root):
    """Оставляем только пакет app.virtualclay и его файлы: чужие .kt из старых проектов ломают компиляцию."""
    java = os.path.join(root, "app/src/main/java/app")
    if not os.path.isdir(java): return
    for name in os.listdir(java):
        path = os.path.join(java, name)
        if os.path.isdir(path) and name != "virtualclay":
            print(f"  ! удаляю чужой пакет: app/{name}"); shutil.rmtree(path, ignore_errors=True)
    pkg = os.path.join(java, "virtualclay")
    if os.path.isdir(pkg):
        for f in os.listdir(pkg):
            if f.endswith(".kt") and f not in KNOWN:
                print(f"  ! удаляю чужой файл: app/virtualclay/{f}"); os.remove(os.path.join(pkg, f))


# ---------- 6. сборка ----------
def build(release, a2):
    w = workdir()
    log("Сборка APK")
    with open(f"{w}/local.properties", "w") as f: f.write(f"sdk.dir={SDK}\n")
    task = "assembleRelease" if release else "assembleDebug"
    cmd = [f"{GRADLE}/bin/gradle", "--no-daemon", "-p", w, task]
    if a2: cmd.append(f"-Pandroid.aapt2FromMavenOverride={a2}")
    run(cmd, env=dict(os.environ, ANDROID_HOME=SDK))
    kind = "release" if release else "debug"
    src = f"{w}/app/build/outputs/apk/{kind}/app-{kind}.apk"
    if not os.path.isfile(src): die("APK не найден после сборки")
    out = f"{ROOT}/VirtualClay.apk"; shutil.copy(src, out)
    for dl in (f"{HOME}/storage/downloads", "/sdcard/Download", "/storage/emulated/0/Download"):
        if os.path.isdir(dl):
            try: shutil.copy(out, f"{dl}/VirtualClay.apk"); print(f"  копия: {dl}/VirtualClay.apk"); break
            except Exception: pass
    log(f"ГОТОВО: {out} ({os.path.getsize(out) >> 10} KB)")
    print("Установи VirtualClay.apk (разреши установку из неизвестных источников). Работает на ARMv7 и ARM64, Android 9+.")


def main():
    a = sys.argv[1:]
    if TERMUX and not os.path.isdir(f"{HOME}/storage"): print("Совет: выполни termux-setup-storage, чтобы APK копировался в Загрузки")
    deps()
    sdk(); gradle(); build("--release" in a, aapt2())


if __name__ == "__main__":
    main()
