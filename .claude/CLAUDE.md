# CLAUDE.md

**Breqk** is a React Native Android app that blocks distracting apps via a mandatory delay overlay and monitors Instagram Reels/YouTube Shorts via accessibility service.

## Quick Start

```bash
npm start              # Metro bundler (terminal 1)
npm run android        # Build & deploy (terminal 2)
npm run lint           # Lint
npm test               # Tests
```

## Architecture

**UI Layer** (React Native) → **Bridge** (VPNModule, SettingsModule) → **Native** (AppUsageMonitor, ReelsInterventionService) → **System APIs** (UsageStatsManager, AccessibilityService, SharedPreferences)

### Entry Points
1. **AccessibilityPermissionActivity** — Gates app until ReelsInterventionService is enabled
2. **MainActivity** → **App.tsx** — Checks permissions, routes to PermissionsScreen or Home
3. **MyVpnService** — Foreground service, owns AppUsageMonitor instance for background monitoring

### Core Native Modules (`android/app/src/main/java/com/breqk/`)

| Module | Purpose |
|--------|---------|
| **VPNModule** | JS bridge: permissions, start/stop monitoring, emits `onAppDetected`/`onBlockedAppOpened` |
| **AppUsageMonitor** | 1s polling loop via UsageStatsManager, shows overlay when blocked app detected |
| **MyVpnService** | Foreground service persistence; syncs blocked apps via SharedPreferences intent dispatch |
| **ReelsInterventionService** | AccessibilityService monitoring Reels/Shorts scrolls; shows intervention popup |
| **BreqkPrefs** | Centralized SharedPreferences helper + per-app policy resolution |
| **ModeManager** | Mode lifecycle (Study Mode, Bedtime, custom) with schedule support via AlarmManager |

### React Native Components (`components/`)

Home, Customize, PermissionsScreen, BlockerInterstitial, Browser, Progress, TopBar, VPNSwitch

### Key SharedPreferences Keys

`blocked_apps`, `monitoring_enabled`, `delay_message`, `delay_time_seconds`, `scroll_threshold`, `scroll_allowance_minutes`, `scroll_window_minutes`, `app_policies` (JSON), `breqk_modes` (JSON), `active_mode`

### Critical Gotchas

- **Dual Monitor Sync** — VPNModule and MyVpnService both own AppUsageMonitor. Update both via SharedPreferences + intent when changing blocked apps.
- **Overlay Permission Guard** — AppUsageMonitor silently exits if SYSTEM_ALERT_WINDOW not granted.
- **Reels False Positives** — isFullScreenReelsViewPager() requires element ≥90% width, ≥70% height, <200px from top.
- **-1 Sentinel** — Native layer returns -1 for unavailable metrics; useDigitalWellbeing.js converts to null.

## Task Tracking

See `docs/TASKS.md` — single source of truth for bugs, features, and architecture notes.
- **Before starting:** read `docs/TASKS.md` to avoid duplicate work
- **After completing:** update checkbox and date in `docs/TASKS.md`

## Rules

- Comment and document everything
- Reuse existing code; create abstractions only if used 3+ times
- Add logging at every step; see `docs/LOGGING.md` for tags and filter commands
- Keep code future-proof and human-readable

<!-- code-review-graph MCP tools -->
## MCP Tools: code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the codebase.** The graph is faster, cheaper (fewer tokens), and gives
you structural context (callers, dependents, test coverage) that file
scanning cannot.

### When to use graph tools FIRST

- **Exploring code**: `semantic_search_nodes` or `query_graph` instead of Grep
- **Understanding impact**: `get_impact_radius` instead of manually tracing imports
- **Code review**: `detect_changes` + `get_review_context` instead of reading entire files
- **Finding relationships**: `query_graph` with callers_of/callees_of/imports_of/tests_for
- **Architecture questions**: `get_architecture_overview` + `list_communities`

Fall back to Grep/Glob/Read **only** when the graph doesn't cover what you need.

### Key Tools

| Tool | Use when |
|------|----------|
| `detect_changes` | Reviewing code changes — gives risk-scored analysis |
| `get_review_context` | Need source snippets for review — token-efficient |
| `get_impact_radius` | Understanding blast radius of a change |
| `get_affected_flows` | Finding which execution paths are impacted |
| `query_graph` | Tracing callers, callees, imports, tests, dependencies |
| `semantic_search_nodes` | Finding functions/classes by name or keyword |
| `get_architecture_overview` | Understanding high-level codebase structure |
| `refactor_tool` | Planning renames, finding dead code |

### Workflow

1. The graph auto-updates on file changes (via hooks).
2. Use `detect_changes` for code review.
3. Use `get_affected_flows` to understand impact.
4. Use `query_graph` pattern="tests_for" to check coverage.
