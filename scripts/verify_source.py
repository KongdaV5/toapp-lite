#!/usr/bin/env python3
"""Fail the build if forbidden telemetry/domain markers enter the source tree."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = {
    "baidu.mobstat",
    "BaiduMobAd_",
    "seegood.top",
    "toapp.website",
    "firebase.analytics",
    "google-analytics",
    "appcenter",
    "umeng",
    "adjust.com",
    "appsflyer",
}
TEXT_SUFFIXES = {
    ".java", ".kt", ".kts", ".xml", ".json", ".md", ".yml", ".yaml",
    ".properties", ".gradle", ".txt", "",
}

# Documentation necessarily names the domains/SDKs being excluded.
IGNORE_FILES = {
    ROOT / "README.md",
    ROOT / "PROJECT_STATUS.md",
    ROOT / "scripts" / "verify_source.py",
}

failures = []
for path in ROOT.rglob("*"):
    if not path.is_file() or path in IGNORE_FILES or "build" in path.parts or ".git" in path.parts:
        continue
    if path.suffix.lower() not in TEXT_SUFFIXES:
        continue
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        continue
    lower = text.lower()
    for marker in FORBIDDEN:
        if marker.lower() in lower:
            failures.append(f"{path.relative_to(ROOT)}: {marker}")

builder_manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
required_removals = [
    "android.permission.INTERNET",
    "android.permission.READ_PHONE_STATE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
]
for permission in required_removals:
    if permission not in builder_manifest or 'tools:node="remove"' not in builder_manifest:
        failures.append(f"builder manifest does not explicitly remove {permission}")

shell_manifest = (ROOT / "shell/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for permission in ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"]:
    if permission not in shell_manifest:
        failures.append(f"shell manifest missing {permission}")
for forbidden_permission in [
    "READ_PHONE_STATE", "READ_CONTACTS", "RECORD_AUDIO", "CAMERA",
    "ACCESS_FINE_LOCATION", "MANAGE_EXTERNAL_STORAGE", "REQUEST_INSTALL_PACKAGES",
]:
    if forbidden_permission in shell_manifest:
        failures.append(f"shell manifest contains forbidden permission {forbidden_permission}")

if failures:
    print("Source security verification failed:", file=sys.stderr)
    for failure in failures:
        print(f" - {failure}", file=sys.stderr)
    raise SystemExit(1)

print("Source security verification passed.")
