import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from pathlib import Path
from typing import Iterable, Optional

# Ensure test output renders Unicode correctly on Windows (cp1252 default crashes on → etc.)
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PASS = "PASS"
FAIL = "FAIL"
WARN = "WARN"
SKIP = "SKIP"

ANDROID_NS = "http://schemas.android.com/apk/res/android"

FAST_TIMEOUT = 120  # seconds for fast tests (NNN < 90)
SLOW_TIMEOUT = 240  # seconds for slow tests (NNN >= 90)


@contextmanager
def time_guard(test_path: Path):
    """
    Context manager that enforces per-test time limits.
    Fast tests (NNN < 90): 120s limit
    Slow tests (NNN >= 90): 240s limit
    Prints 'TIME FAIL' and exits if exceeded.
    """
    try:
        num = int(test_path.stem.split("_")[1])
    except (IndexError, ValueError):
        num = 0
    limit = SLOW_TIMEOUT if num >= 90 else FAST_TIMEOUT
    t0 = time.monotonic()
    yield
    elapsed = time.monotonic() - t0
    if elapsed > limit:
        print(f"TIME FAIL: test exceeded {limit}s limit ({elapsed:.1f}s)")
        result(FAIL, f"TIME FAIL: exceeded {limit}s limit ({elapsed:.1f}s)")


def result(status: str, reason: str) -> None:
    """Print the structured result line and exit with appropriate code."""
    print(f"RESULT: {status}: {reason}")
    sys.exit(0 if status in (PASS, WARN, SKIP) else 1)


def parse_manifest(manifest_path: Path) -> ET.Element:
    """Parse AndroidManifest.xml and return root element with namespace registered."""
    ET.register_namespace("android", ANDROID_NS)
    tree = ET.parse(manifest_path)
    return tree.getroot()


def android_attr(element: ET.Element, attr: str) -> str:
    """Get an android-namespaced attribute value, or empty string."""
    return element.get(f"{{{ANDROID_NS}}}{attr}", "")


def class_name_to_path(name: str, java_src: Path) -> Path:
    """
    Convert manifest class name to expected Java file path.
    Handles both full names ("com.Break.Foo") and short names (".Foo", ".bar.Foo").
    java_src should be the .../java/com/Break directory.
    """
    if name.startswith("."):
        name = "com.Break" + name
    relative = name.replace(".", "/") + ".java"
    # java_src = project/.../java/com/Break  ->  java base = .../java
    java_base = java_src.parent.parent
    return java_base / relative


def grep_java(java_src: Path, pattern: str) -> list:
    """
    Recursively grep all .java files under java_src for a regex pattern.
    Returns list of (file_path, line_number, line_content).
    """
    matches = []
    rx = re.compile(pattern)
    for f in java_src.rglob("*.java"):
        for i, line in enumerate(
            f.read_text(encoding="utf-8", errors="ignore").splitlines(), 1
        ):
            if rx.search(line):
                matches.append((f, i, line.strip()))
    return matches


_JS_SKIP_DIRS = {"node_modules", ".git", "build", ".gradle", "android", ".idea", ".bundle", "__pycache__"}


def grep_js(
    root_dir: Path, pattern: str, extensions=(".js", ".jsx", ".ts", ".tsx")
) -> list:
    """
    Recursively grep all JS/TS files under root_dir for a regex pattern.
    Returns list of (file_path, line_number, line_content).
    Prunes large/irrelevant directories (node_modules, .git, etc.) at walk time.
    """
    import os
    matches = []
    rx = re.compile(pattern)
    ext_set = set(extensions)
    for dirpath, dirnames, filenames in os.walk(root_dir, topdown=True):
        dirnames[:] = [d for d in dirnames if d not in _JS_SKIP_DIRS]
        for fname in filenames:
            if any(fname.endswith(ext) for ext in ext_set):
                f = Path(dirpath) / fname
                for i, line in enumerate(
                    f.read_text(encoding="utf-8", errors="ignore").splitlines(), 1
                ):
                    if rx.search(line):
                        matches.append((f, i, line.strip()))
    return matches


# ── New helpers (test harness extension) ───────────────────────────────


def run_subprocess(args: list, cwd: Path, timeout: int = 600):
    """
    Run a subprocess and return (returncode, stdout, stderr).
    Caller decides PASS/FAIL/SKIP. Returns (-1, '', 'binary missing') on FileNotFoundError.
    """
    try:
        proc = subprocess.run(
            args, capture_output=True, text=True, cwd=str(cwd), timeout=timeout
        )
        return proc.returncode, proc.stdout, proc.stderr
    except FileNotFoundError:
        return -1, "", "binary missing"
    except subprocess.TimeoutExpired:
        return -2, "", f"timeout after {timeout}s"


def binary_available(name: str) -> bool:
    """shutil.which() check, normalized for Windows .exe / .cmd / .bat lookup."""
    return shutil.which(name) is not None


def find_react_methods(java_file: Path) -> list:
    """Return method names declared with @ReactMethod in a Java file."""
    text = java_file.read_text(encoding="utf-8", errors="ignore")
    return re.findall(
        r"@ReactMethod\s*(?:\([^)]*\))?\s*(?:public\s+)?(?:final\s+)?\s*\w[\w<>\[\] ]*\s+(\w+)\s*\(",
        text,
    )


def find_js_bridge_calls(root: Path, module: str) -> set:
    """Return method names called as NativeModules.<module>.<name>(...) anywhere in JS/TS."""
    pat = re.compile(rf"(?:NativeModules\.)?{re.escape(module)}\.(\w+)\s*\(")
    found = set()
    for hit in grep_js(root, pat.pattern):
        m = pat.search(hit[2])
        if m:
            found.add(m.group(1))
    return found


def walk_source_files(
    root: Path = None,
    extensions: tuple = (".java", ".kt", ".js", ".jsx", ".ts", ".tsx"),
    skip_dirs: set = None,
) -> Iterable[Path]:
    """
    Walk source files under root, yielding Path objects.
    Skips directories in skip_dirs (matched against any path component).
    """
    from _paths import PROJECT_ROOT, SCAN_EXCLUDE_DIRS

    if root is None:
        root = PROJECT_ROOT
    if skip_dirs is None:
        skip_dirs = SCAN_EXCLUDE_DIRS
    import os

    for dirpath, dirnames, filenames in os.walk(root, topdown=True):
        # Prune excluded directories
        dirnames[:] = [
            d
            for d in dirnames
            if d not in skip_dirs
            and not any(excl in os.path.join(dirpath, d) for excl in skip_dirs)
        ]
        for fname in filenames:
            if any(fname.endswith(ext) for ext in extensions):
                yield Path(dirpath) / fname


def parse_gradle_kv(gradle_text: str, key: str) -> Optional[str]:
    """Extract the value of a simple key=value or key value from Gradle build file."""
    # Match patterns like: versionCode 1, versionName "1.0", def foo = true
    m = re.search(
        rf'(?:def\s+)?{re.escape(key)}\s*[=\s]\s*["\']?([^"\'\s]+)', gradle_text
    )
    return m.group(1) if m else None


def parse_release_signing_config(gradle_text: str) -> Optional[str]:
    """Extract the signingConfig used by the release build type."""
    # Match: release { ... signingConfig signingConfigs.xxx ... }
    release_block = re.search(r"release\s*\{([^}]*)\}", gradle_text, re.DOTALL)
    if not release_block:
        return None
    m = re.search(r"signingConfig\s+signingConfigs\.(\w+)", release_block.group(1))
    return m.group(1) if m else None


def parse_versioncode(gradle_text: str) -> Optional[int]:
    """Extract versionCode integer from build.gradle."""
    m = re.search(r"versionCode\s+(\d+)", gradle_text)
    return int(m.group(1)) if m else None


def iter_components(manifest_root: ET.Element, tag: str = None):
    """
    Iterate <activity>, <service>, <receiver>, or <provider> elements.
    If tag is given, only that type. Returns (element, tag_name) tuples.
    """
    tags = [tag] if tag else ["activity", "service", "receiver", "provider"]
    for t in tags:
        for el in manifest_root.iter(t):
            yield el, t


def is_exported(element: ET.Element) -> bool:
    """Check if a manifest component has android:exported='true'."""
    return android_attr(element, "exported").lower() == "true"


def extract_log_tags(java_src: Path) -> set:
    """Extract TAG string values from Java files: private static final String TAG = 'X'."""
    tags = set()
    for f in java_src.rglob("*.java"):
        text = f.read_text(encoding="utf-8", errors="ignore")
        for m in re.finditer(
            r'(?:private|public|protected)?\s*static\s+final\s+String\s+TAG\s*=\s*"([^"]+)"',
            text,
        ):
            tags.add(m.group(1))
    return tags


def parse_manifest_uses_permissions(manifest_root: ET.Element) -> set:
    """Extract all <uses-permission android:name=...> values from manifest."""
    perms = set()
    for el in manifest_root.iter("uses-permission"):
        name = android_attr(el, "name")
        if name:
            perms.add(name)
    return perms


def parse_intent_filters(element: ET.Element) -> list:
    """Return list of intent-filter dicts with actions, categories, and data elements."""
    filters = []
    for ifilt in element.iter("intent-filter"):
        f = {
            "actions": [android_attr(a, "name") for a in ifilt.iter("action")],
            "categories": [android_attr(c, "name") for c in ifilt.iter("category")],
            "data": [],
        }
        for d in ifilt.iter("data"):
            f["data"].append(
                {
                    "scheme": android_attr(d, "scheme"),
                    "host": android_attr(d, "host"),
                    "path": android_attr(d, "path"),
                    "pathPrefix": android_attr(d, "pathPrefix"),
                }
            )
        filters.append(f)
    return filters
