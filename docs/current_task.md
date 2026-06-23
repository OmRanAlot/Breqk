# Current Task

**Date Started:** 2026-06-23
**Status:** `[~] In progress — Codebase optimization: file-size reduction + redundancy removal`

## Goal

Go through the codebase to remove redundancy and improve clarity. No file over
~600 lines unless the logic is genuinely cohesive/non-reusable. Behavior must be
unchanged. Builds are intentionally skipped this session (per user); JS changes
are verified with app-scoped eslint only.

## Plan (phases)

- **Phase 0 (done):** Baseline build was green (exit 0). Jest is not a useful
  signal here (vendored everything-claude-code suites dominate). App-scoped lint
  baseline: 1 pre-existing error, ~20 warnings.
- **Phase 1 (in progress):** Extract styles/sub-components from the 6 large JS
  screens, pruning dead style keys along the way.
- **Phase 2:** Consolidate shared JS utils (icons, formatters, logging).
- **Phase 3:** Java extraction (AppUsageMonitor, ReelsInterventionService,
  BreakPrefs, VPNModule).
- **Phase 5:** Final verification + docs.

Borderline Java files (YouTubeDetector 648, ScreenTimeTracker 623,
SettingsModule 602) are intentionally left as-is (cohesive; 600 treated soft).

## Progress

- `components/Customize/customize.js`: 1445 → 614 lines. Extracted
  `customize.styles.js` (dead Modes/Schedule/AppCard style keys removed),
  `ScrollBudgetSection.js`, `InterceptMessageSection.js`, `DeletionInfoModal.js`.
- `components/Home/home.js`: 1190 → 655 lines. Extracted `home.styles.js`
  (dead footer/primary/secondary/caption keys removed), `HomeScrollBudgetCard.js`,
  `FreeBreakCard.js`.
- Created shared `components/common/format.js` (formatTime/formatCount/
  formatBudgetTime) — removed duplicate copies across screens.
- Deleted dead file `components/Home/homeStyle.js` (311 lines, no importers).

## Remaining Phase 1

- `components/AppDetail/AppDetail.js` (1016)
- `components/Permissions/PermissionsScreen.js` (878)
- `components/Modes/ModeEditorModal.js` (667)
- `components/Modes/ModesScreen.js` (623)
