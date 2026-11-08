"""Verify the actual paired APKs, not just source manifests. Requires Android SDK tools."""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
ANALYZER = shutil.which("apkanalyzer") or str(next(SDK.glob("cmdline-tools/*/bin/apkanalyzer")))
SIGNER = str(SDK / "build-tools/36.0.0/apksigner")
NS = "{http://schemas.android.com/apk/res/android}"
MAIN = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
AUDIO = ROOT / "audio/build/outputs/apk/debug/audio-debug.apk"


def manifest(apk):
    return ET.fromstring(subprocess.check_output([ANALYZER, "manifest", "print", str(apk)], text=True))


def fingerprint(apk):
    output = subprocess.check_output([SIGNER, "verify", "--print-certs", str(apk)], text=True)
    return re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", output).group(1)


def permissions(root):
    return {p.get(NS + "name") for p in root.findall("uses-permission")}


main, audio = manifest(MAIN), manifest(AUDIO)
assert main.get("package") == "com.madclock.app"
assert audio.get("package") == "com.madclock.audio"
assert main.get(NS + "sharedUserId") is None and audio.get(NS + "sharedUserId") is None
assert fingerprint(MAIN) == fingerprint(AUDIO), "Both APKs must be installed from one same-signer pair"
for root in (main, audio):
    assert "android.permission.INTERNET" not in permissions(root)
assert not permissions(main).intersection({"android.permission.WAKE_LOCK",
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"})
for service in main.findall("./application/service"):
    assert service.get(NS + "foregroundServiceType") is None
    assert not service.get(NS + "name").endswith("AlarmService")
control = "com.madclock.audio.permission.CONTROL_AUDIO"
definition = next(p for p in audio.findall("permission") if p.get(NS + "name") == control)
assert definition.get(NS + "protectionLevel") in {"signature", "0x2"}
helper = next(s for s in audio.findall("./application/service") if s.get(NS + "name").endswith("AudioService"))
assert helper.get(NS + "permission") == control and helper.get(NS + "exported") == "true"
assert helper.get(NS + "foregroundServiceType") in {"mediaPlayback", "0x2"}
for activity in audio.findall("./application/activity"):
    assert activity.get(NS + "excludeFromRecents") == "true"
    assert activity.get(NS + "permission") == control
    assert not any(c.get(NS + "name") == "android.intent.category.LAUNCHER"
        for c in activity.findall("./intent-filter/category"))
with zipfile.ZipFile(MAIN) as apk:
    sound = apk.read("res/raw/glass_signal_alarm_60s.mp3")
assert hashlib.sha256(sound).hexdigest() == "3a86b3ba04d338ef3b23b1d1c702e548ec118a30cf1843122f92baade9d7308a"
for source in (ROOT / "app/src/main").rglob("*.kt"):
    assert not re.search(r"\b(MediaPlayer|AudioTrack|SoundPool|RingtoneManager|PowerManager)\b", source.read_text()), source
print("APK pair verified: same signer, separate packages, protected IPC, no main playback, no Internet, original MP3.")
