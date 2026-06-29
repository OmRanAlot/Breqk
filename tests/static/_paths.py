"""
_paths.py — Project-wide path constants for the static test harness.
All test files import from here so path changes only need updating in one place.
"""
from pathlib import Path

# Resolve project root: tests/static/_paths.py -> tests/static -> tests -> project root
PROJECT_ROOT   = Path(__file__).resolve().parent.parent.parent

ANDROID_SRC    = PROJECT_ROOT / "android" / "app" / "src" / "main"
JAVA_SRC       = ANDROID_SRC / "java" / "com" / "Break"
MANIFEST       = ANDROID_SRC / "AndroidManifest.xml"
STRINGS_XML    = ANDROID_SRC / "res" / "values" / "strings.xml"
RES_XML_DIR    = ANDROID_SRC / "res" / "xml"
RES_DIR        = ANDROID_SRC / "res"
BUILD_GRADLE   = PROJECT_ROOT / "android" / "app" / "build.gradle"
PACKAGE_JSON   = PROJECT_ROOT / "package.json"
TSCONFIG       = PROJECT_ROOT / "tsconfig.json"
COMPONENTS_DIR = PROJECT_ROOT / "components"
LOGGING_MD     = PROJECT_ROOT / "docs" / "LOGGING.md"
Break_PREFS    = JAVA_SRC / "prefs" / "BreakPrefs.java"
GRADLEW        = PROJECT_ROOT / "android" / "gradlew.bat"

# ── New path constants (test harness extension) ────────────────────────
RES_LAYOUT_DIR              = ANDROID_SRC / "res" / "layout"
NETWORK_SECURITY_CONFIG     = ANDROID_SRC / "res" / "xml" / "network_security_config.xml"
ACCESSIBILITY_REELS_CONFIG  = ANDROID_SRC / "res" / "xml" / "reels_intervention_service_config.xml"
WIDGET_INFO_XML             = ANDROID_SRC / "res" / "xml" / "widget_break_info.xml"
APP_TSX                     = PROJECT_ROOT / "App.tsx"
DEBUG_KEYSTORE              = PROJECT_ROOT / "android" / "app" / "debug.keystore"
PACKAGE_LOCK                = PROJECT_ROOT / "package-lock.json"
BRIDGE_DIR                  = JAVA_SRC / "bridge"
WIDGET_PREFS               = JAVA_SRC / "widget" / "WidgetPrefs.java"

SCAN_EXCLUDE_DIRS           = {
    "node_modules", ".git", "build", ".gradle",
    "android/.cxx", "android/build", "android/app/build",
    ".idea", ".bundle", "everything-claude-code",
}
