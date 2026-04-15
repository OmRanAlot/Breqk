# ADR: Modular Refactoring of ReelsInterventionService

**Date:** 2026-04-15  
**Status:** Accepted  
**Branch:** `optimization`

## Context

`ReelsInterventionService.java` had grown to ~1,900 lines, mixing detection, budget tracking, SharedPreferences I/O, WindowManager overlay management, and IPC intents all in a single class. The hot-path `onAccessibilityEvent` / `handleReelsScrollEvent` executed ~6 SharedPreferences reads per scroll event, ~30 `Log.d` string allocations (even in release builds), and maintained state through a dozen disconnected boolean flags with no formal state machine. `YOUTUBE_SHORTS_VIEW_IDS` was duplicated verbatim in `ContentFilter.java` (tracked as B1). This combination of performance liability, fragility, and illegibility made the file the highest-risk component in the codebase.

## Decision

We split the service into a package of single-responsibility collaborators under `com.breqk.reels`:

- **`ShortFormIds`** — single source of truth for view ID arrays and full-screen thresholds (closes B1)
- **`FullScreenCheck`** — static utility replacing duplicated `isFullScreen*()` helpers
- **`FrameworkClassFilter`** — consolidates overlay-package detection and launcher-package caching
- **`InstagramDetector` / `YouTubeDetector`** — encapsulate the Tier 0–3 detection ladders and tree-dump diagnostics
- **`BudgetState`** — in-memory mirror of the 5 `scroll_*` SharedPreferences keys; flushes every ≤4 ticks (≤4 s) to stay within `AppUsageMonitor`'s 5 s staleness window
- **`BudgetHeartbeat`** — owns the 1 s `Runnable` and accumulation math
- **`InterventionOverlay`** — owns `WindowManager` attach/detach, button wiring, and showing state
- **`ReelsStateMachine`** — collapses `wasInReelsLayout`, `inGracePeriod`, and `shortsCurrentlyDetected` into one object; emits `[GRACE]` and `[SHORTS_ACTIVE]` log transitions
- **`logVerbose()` helper in the service shell** — gates all per-scroll `Log.d` calls behind `BuildConfig.DEBUG` (closes B9-equivalent for this service)

The external contract — all SharedPreferences key names, all log tags, and the behavior seen by `AppUsageMonitor`, `VPNModule`, and `ContentFilter` — is byte-for-byte identical to the pre-refactor code. No behavior was changed.

## Consequences

- `ReelsInterventionService.java` is now ~950 lines (down from ~1,900); each collaborator is 50–150 lines.
- `BudgetState` and `ReelsStateMachine` are pure-Java classes, making them unit-testable for the first time (tests are a recommended follow-up, not in scope here).
- Zero `Log.d` allocations in release builds during scroll sessions (was ~30 per event).
- SharedPreferences reads on the hot path reduced from ~6 per scroll to 0 (in-memory, periodic flush).
- A single edit to `ShortFormIds` propagates to both `ReelsInterventionService` and `ContentFilter` automatically.
