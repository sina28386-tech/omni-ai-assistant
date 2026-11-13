#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
import json, re, sys, tomllib, xml.etree.ElementTree as ET, zipfile
import base64

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
results: dict[str, object] = {}
failures: list[str] = []

# XML syntax.
xml_errors = []
xml_files = sorted((APP / "src").rglob("*.xml"))
for file in xml_files:
    try:
        ET.parse(file)
    except Exception as exc:
        xml_errors.append({"file": str(file.relative_to(ROOT)), "error": str(exc)})
results["xml"] = {"files": len(xml_files), "errors": xml_errors}
if xml_errors:
    failures.append("XML parse errors")

# String resource parity / duplicates.
# The UI is split into per-area string files (strings_home.xml, strings_chat.xml,
# ...), so every file in the folder has to take part in the comparison.
def string_files(folder: Path) -> list[Path]:
    return sorted(folder.glob("strings*.xml"))


def string_names(folder: Path) -> list[str]:
    names: list[str] = []
    for path in string_files(folder):
        root = ET.parse(path).getroot()
        names.extend(item.attrib.get("name") for item in root if item.tag == "string")
    return names


en = string_names(APP / "src/main/res/values")
fa = string_names(APP / "src/main/res/values-fa")
dup_en = sorted({x for x in en if en.count(x) > 1})
dup_fa = sorted({x for x in fa if fa.count(x) > 1})
missing_fa = sorted(set(en) - set(fa))
extra_fa = sorted(set(fa) - set(en))
results["strings"] = {
    "english_files": [p.name for p in string_files(APP / "src/main/res/values")],
    "persian_files": [p.name for p in string_files(APP / "src/main/res/values-fa")],
    "english_count": len(en), "persian_count": len(fa),
    "duplicates_english": dup_en, "duplicates_persian": dup_fa,
    "missing_in_persian": missing_fa, "extra_in_persian": extra_fa,
}
if dup_en or dup_fa or missing_fa or extra_fa:
    failures.append("String resource parity")

# Every R.string reference in Kotlin must exist in the canonical resource set.
string_refs = set()
for file in sorted((APP / "src").rglob("*.kt")):
    text = file.read_text("utf-8", errors="ignore")
    string_refs.update(re.findall(r"\bR\.string\.([A-Za-z0-9_]+)", text))
missing_string_refs = sorted(string_refs - set(en))
results["string_references"] = {
    "referenced_count": len(string_refs),
    "missing": missing_string_refs,
}
if missing_string_refs:
    failures.append("Missing R.string resources")

# TOML validity.
toml_errors = []
for file in sorted(ROOT.rglob("*.toml")):
    try:
        tomllib.loads(file.read_text("utf-8"))
    except Exception as exc:
        toml_errors.append({"file": str(file.relative_to(ROOT)), "error": str(exc)})
results["toml"] = {"files": len(list(ROOT.rglob('*.toml'))), "errors": toml_errors}
if toml_errors:
    failures.append("TOML parse errors")

# Production anti-patterns.
prod_files = [p for p in (APP / "src/main").rglob("*") if p.is_file() and p.suffix in {".kt", ".xml"}]
forbidden = re.compile(r"TODO|FIXME|GlobalScope|allowMainThreadQueries|fallbackToDestructiveMigration|AsyncTask|notifyDataSetChanged")
forbidden_hits = []
for file in prod_files:
    text = file.read_text("utf-8", errors="ignore")
    for no, line in enumerate(text.splitlines(), 1):
        if forbidden.search(line):
            forbidden_hits.append(f"{file.relative_to(ROOT)}:{no}:{line.strip()}")
results["production_forbidden_patterns"] = forbidden_hits
if forbidden_hits:
    failures.append("Production forbidden patterns")

# Common-looking real API key formats. Deliberately avoids matching placeholder words.
secret_patterns = [
    re.compile(r"AIza[0-9A-Za-z_-]{30,}"),
    re.compile(r"sk-[A-Za-z0-9_-]{16,}"),
    re.compile(r"nvapi-[A-Za-z0-9_-]{16,}"),
    re.compile(r"gsk_[A-Za-z0-9_-]{16,}"),
]


def contains_secret(text: str) -> bool:
    if any(pattern.search(text) for pattern in secret_patterns):
        return True
    # Also catch Base64-wrapped keys (the previous demo key used this trick).
    for candidate in re.findall(r"[A-Za-z0-9+/]{40,}={0,2}", text):
        try:
            decoded = base64.b64decode(candidate, validate=True).decode("utf-8", "ignore")
        except Exception:
            continue
        if any(pattern.search(decoded) for pattern in secret_patterns):
            return True
    return False


secret_hits = []
secret_suffixes = {".kt", ".kts", ".xml", ".toml", ".md", ".properties", ".py", ".json", ".txt", ".gradle"}
for file in (p for p in ROOT.rglob("*") if p.is_file() and p.suffix.lower() in secret_suffixes):
    rel = file.relative_to(ROOT)
    if any(part in {".git", "build", ".gradle"} for part in rel.parts):
        continue
    text = file.read_text("utf-8", errors="ignore")
    if contains_secret(text):
        secret_hits.append(str(rel))
results["possible_secret_files"] = secret_hits
if secret_hits:
    failures.append("Possible hardcoded secrets")

# Manifest assertions.
manifest = (APP / "src/main/AndroidManifest.xml").read_text("utf-8")
debug_manifest = (APP / "src/debug/AndroidManifest.xml").read_text("utf-8")
manifest_checks = {
    "main_allow_backup_false": 'android:allowBackup="false"' in manifest,
    "main_cleartext_false": 'android:usesCleartextTraffic="false"' in manifest,
    "debug_cleartext_override_has_tools_replace": 'tools:replace="android:networkSecurityConfig,android:usesCleartextTraffic"' in debug_manifest,
    "record_audio_declared": 'android.permission.RECORD_AUDIO' in manifest,
    # V2 is a real phone assistant, so these dangerous capabilities are
    # intentional and gated by the in-app CapabilityCenter at runtime.
    "camera_permission_declared": 'android.permission.CAMERA' in manifest,
    "call_phone_declared": 'android.permission.CALL_PHONE' in manifest,
    "send_sms_declared": 'android.permission.SEND_SMS' in manifest,
    "read_contacts_declared": 'android.permission.READ_CONTACTS' in manifest,
    "read_sms_declared": 'android.permission.READ_SMS' in manifest,
    "read_calendar_declared": 'android.permission.READ_CALENDAR' in manifest,
    "all_files_is_explicit": 'android.permission.MANAGE_EXTERNAL_STORAGE' in manifest,
    "accessibility_bound": 'android.permission.BIND_ACCESSIBILITY_SERVICE' in manifest,
    "notification_listener_bound": 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' in manifest,
    "file_provider_not_exported": re.search(
        r'androidx\.core\.content\.FileProvider[\s\S]{0,240}?android:exported="false"', manifest
    ) is not None,
    "no_debuggable_in_main": 'android:debuggable="true"' not in manifest,
}
results["manifest_checks"] = manifest_checks
if not all(manifest_checks.values()):
    failures.append("Manifest security assertions")

# Gradle wrapper properties pin HTTPS distribution and SHA.
props = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text("utf-8")
wrapper_checks = {
    "https_distribution": "distributionUrl=https\\://services.gradle.org/" in props,
    "sha256_pinned": bool(re.search(r"^distributionSha256Sum=[0-9a-f]{64}$", props, re.M)),
    "scripts_present": (ROOT / "gradlew").exists() and (ROOT / "gradlew.bat").exists(),
    "jar_present": (ROOT / "gradle/wrapper/gradle-wrapper.jar").exists(),
}
results["wrapper_checks"] = wrapper_checks
if not all(wrapper_checks.values()):
    failures.append("Wrapper file assertions")

results["passed"] = not failures
results["failures"] = failures
print(json.dumps(results, indent=2, ensure_ascii=False))
sys.exit(0 if not failures else 1)
