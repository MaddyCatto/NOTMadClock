"""Verify the actual paired APKs, not just source manifests. Requires Android SDK tools."""
import os
import argparse
import hashlib
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
CONTROL = "com.madclock.audio.permission.CONTROL_AUDIO"
AUDIO_INFO = "com.madclock.audio.AudioInfoActivity"
ICON_SHA256 = "566901bf4fde3f1fd9d5928e5e1744f294ca9394336d4dccb28741256465d2df"
MEDIA_SHA256 = {
    "default_alarm.mp3": "f8e33bea1cb16e3c9c034ecf8bb8240074dedd0af0f052ce1b66d77f1388d8f8",
    "sleep_01_white_30s_seamless.wav": "ac8bb565e9d36e8f1aa4a6c7ba7d3ec2c5d71fa337ba862aba163343ee18f529",
    "sleep_02_pink_30s_seamless.wav": "175f6861da851f01cefce96a22e4d15672d8c8eb7987940eba491032b0107bd2",
    "sleep_03_brown_30s_seamless.wav": "6b26aa2f37e79d81cb054524a4fbad0ff1fbe541d4934cd1c16ae884707e6768",
    "sleep_04_blue_30s_seamless.wav": "a4e786473b8e3056a9a85bab4a2f0e3f465fe5c7416da3c4560798fead11e4e4",
    "sleep_05_violet_30s_seamless.wav": "9ae01fe5dd000c7e6ec5911e3f8c41a6b812102cb4ed894c8129199ed772da8b",
    "sleep_06_gray_approx_30s_seamless.wav": "4c8aca29ed05ca1681922c986a62021d2286fcdd185d49b1db0dc80dd7a11e6f",
    "sleep_07_green_midrange_30s_seamless.wav": "1b71789a8e42048459918ee4b5c5d3a777825185cbe639b64999d6bc0f8bc845",
    "sleep_08_soft_white_30s_seamless.wav": "17cca11656b7653ad0aaae56ad195aad435bced4aeac53a0739f110e70a17b06",
    "sleep_09_warm_pink_30s_seamless.wav": "0f866c11eab6e959b5f88e331c7bc39f4d9a1f8f4ea73ec82ef7a016b3804098",
    "sleep_10_deep_brown_30s_seamless.wav": "f7f5dbd8333c77082baf3af10f41d365d1071248a459cc5f43f8c8924fa90cac",
}
parser = argparse.ArgumentParser()
parser.add_argument("--variant", choices=("debug", "release"), default="debug")
variant = parser.parse_args().variant
MAIN = ROOT / f"app/build/outputs/apk/{variant}/app-{variant}.apk"
AUDIO = ROOT / f"audio/build/outputs/apk/{variant}/audio-{variant}.apk"


def manifest(apk):
    return ET.fromstring(subprocess.check_output([ANALYZER, "manifest", "print", str(apk)], text=True))


def fingerprint(apk):
    output = subprocess.check_output([SIGNER, "verify", "--print-certs", str(apk)], text=True)
    return re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", output).group(1)


def asset_digest(apk, filename):
    names = [name for name in apk.namelist() if name.endswith("/" + filename)]
    assert len(names) == 1, filename
    with apk.open(names[0]) as asset:
        return hashlib.file_digest(asset, "sha256").hexdigest()


def permissions(root):
    return {p.get(NS + "name") for p in root.findall("uses-permission")}


main, audio = manifest(MAIN), manifest(AUDIO)
assert main.get("package") == "com.madclock.app"
assert audio.get("package") == "com.madclock.audio"
assert main.get(NS + "sharedUserId") is None and audio.get(NS + "sharedUserId") is None
assert fingerprint(MAIN) == fingerprint(AUDIO), "Both APKs must be installed from one same-signer pair"
for root in (main, audio):
    assert "android.permission.INTERNET" not in permissions(root)
    assert "android.permission.ACCESS_NETWORK_STATE" not in permissions(root)
    application = root.find("application")
    assert application.get(NS + "allowBackup") == "false"
    assert application.get(NS + "dataExtractionRules") is not None
    assert application.get(NS + "fullBackupContent") is not None
    assert (application.get(NS + "debuggable") == "true") == (variant == "debug")
    for provider in application.findall("provider"):
        assert provider.get(NS + "exported") == "false", provider.attrib
    for component in application:
        if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        if component.get(NS + "exported") != "true":
            continue
        name = component.get(NS + "name")
        if root is main and name == "com.madclock.app.MainActivity":
            continue  # Launcher only; does not accept alarm commands from Intent extras.
        if root is audio and name == AUDIO_INFO:
            continue  # Information only. Its manifest and launcher filter are checked below.
        allowed = {CONTROL} if root is audio else {CONTROL, "android.permission.DUMP"}
        assert component.get(NS + "permission") in allowed, component.attrib
assert not permissions(main).intersection({"android.permission.WAKE_LOCK",
    "android.permission.POST_NOTIFICATIONS", "android.permission.USE_FULL_SCREEN_INTENT",
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"})
with zipfile.ZipFile(MAIN) as apk:
    assert not any(name.endswith("glass_signal_alarm_60s.mp3") for name in apk.namelist())
    assert asset_digest(apk, "ic_launcher_artwork.png") == ICON_SHA256
    assert not any(Path(name).name in MEDIA_SHA256 for name in apk.namelist()), "Bundled audio belongs in Audio"
with zipfile.ZipFile(AUDIO) as apk:
    assert asset_digest(apk, "ic_launcher_artwork.png") == ICON_SHA256
    for filename, expected in MEDIA_SHA256.items():
        assert asset_digest(apk, filename) == expected, filename
for service in main.findall("./application/service"):
    assert service.get(NS + "foregroundServiceType") is None
    assert not service.get(NS + "name").endswith("AlarmService")
definition = next(p for p in audio.findall("permission") if p.get(NS + "name") == CONTROL)
assert definition.get(NS + "protectionLevel") in {"signature", "0x2"}
helper = next(s for s in audio.findall("./application/service") if s.get(NS + "name").endswith("AudioService"))
assert helper.get(NS + "permission") == CONTROL and helper.get(NS + "exported") == "true"
assert helper.get(NS + "foregroundServiceType") in {"mediaPlayback", "0x2"}
assert helper.get(NS + "stopWithTask") == "false"
assert CONTROL in permissions(main) and CONTROL in permissions(audio)
status = next(r for r in audio.findall("./application/receiver") if r.get(NS + "name").endswith("SleepStatusReceiver"))
assert status.get(NS + "exported") == "true" and status.get(NS + "permission") == CONTROL
assert not status.findall("intent-filter")
launchers = []
for activity in audio.findall("./application/activity"):
    assert activity.get(NS + "excludeFromRecents") == "true"
    if activity.get(NS + "name") == AUDIO_INFO:
        assert activity.get(NS + "exported") == "true"
        assert activity.get(NS + "taskAffinity") == ""
        filters = activity.findall("intent-filter")
        assert len(filters) == 1
        assert [a.get(NS + "name") for a in filters[0].findall("action")] == ["android.intent.action.MAIN"]
        assert [c.get(NS + "name") for c in filters[0].findall("category")] == ["android.intent.category.LAUNCHER"]
        assert not filters[0].findall("data")
        launchers.append(activity)
    else:
        assert activity.get(NS + "permission") == CONTROL
        assert not activity.findall("intent-filter")
assert len(launchers) == 1
assert not audio.findall("./application/activity-alias")
for source in (ROOT / "app/src/main").rglob("*.kt"):
    assert not re.search(r"\b(MediaPlayer|AudioTrack|SoundPool|RingtoneManager|PowerManager)\b", source.read_text()), source
print("APK pair verified: same signer, separate packages, protected IPC, no main playback, no Internet, helper-only audio.")
