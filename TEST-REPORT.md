# New Medicine — TEST-REPORT.md

## §1 — v1.0 (versionCode 1, 2026-11-11): new app from Drops Reminder v2.11

**App:** New Medicine (`com.moon.newmedicine`), a separate drops-reminder
app for the user's new medicine. Base: a verbatim copy of Drops Reminder
v2.11 (`~/workspace/medicine-drops/`, versionCode 26 — that project was
NOT modified), reconfigured only where the new medicine differs.

### 1.1 Configuration (what changed vs the old app)

| Rule | Old app (v2.11) | New Medicine (v1.0) |
|---|---|---|
| Drops per session | morning 7 / afternoon 6 / evening 6 | **4 / 4 / 4, all REGULAR** |
| Gap between drops | 30 min (fixed) | **10 min, setting 5/7/10/15 min, default 10** |
| Special drop | #6, morning only | **none — `specialDropEnabled` forced false at read; Settings rows removed** |
| Display numbering | 6 → 7 remap (afternoon/evening) | **plain identity 1–4** (`displayNumberFor` simplified) |
| Afternoon auto-start default | 14:30 | **13:45** (his configured time) |
| Evening auto-start default | 17:00 | 17:00 (unchanged) |
| Morning wake window | 06:00–11:00 | 06:00–11:00 (unchanged) |
| Evening cutoff | 20:00 | 20:00 (unchanged) |
| Package / label / icon | `com.moon.medidrops`, teal icon | `com.moon.newmedicine`, **violet icon** |
| Self-update repo | `Moon-2482/drops-reminder` | **`Moon-2482/new-medicine`** |
| Version | 2.11 (vc 26) | **1.0 (vc 1)** |

Enforcement beyond defaults: `DropsRepository.getSettings()` forces
`morning/afternoon/eveningDropCount = 4` and `specialDropEnabled = false`
at read time, so no saved or imported value (e.g. a backup restored from
the old app) can ever change the regimen. The gap is sanitized at read
(`sanitizeDoseInterval`: off-preset → 10 min), mirroring the call-gap
sanitizer. The legacy one-time migrations (`migratedDropCounts`,
`migratedSpecialPosition`, `ensureSpecialPositionMigration`,
`ensureDropCountMigration`) are kept verbatim and are inert on a fresh
install (verified: `migratedDropCounts(null, null, null)` returns null);
they exist only so a hypothetical stale value is rewritten before the
read-time forcing applies.

The user's example, pinned by `NewMedicineConfigTest`: morning 8:00 YES
→ next due 8:10 → 8:20 → 8:30, session completes at drop 4.

**Open point (user's own words, unresolved):** he once said "8:40" for
the 4th drop. Built as a UNIFORM 10-minute gap (4th at 8:30). If he
confirms the 4th drop is 20 minutes after the 3rd, that is a one-line
change (a per-position gap), not a redesign.

### 1.2 Carry-over checklist — every old-app fix verified present

Verified by code inspection (symbol present in the new tree) AND by the
passing suite (the same tests that pinned each fix in the old app run
here, with only the config expectations changed):

| # | Fix / setting (old-app version) | Evidence in new build | Status |
|---|---|---|---|
| 1 | Strict chaining, next = confirmedAt + gap; YES/NO only when due; per-session Stop; History shows resolved only | `ScheduleEngine.confirmDose`, `ChainingTest` (8), `ConfirmGuardTest` (17) | PASS |
| 2 | No overlapping sessions + deferred start (blocker-clear + 30 min; user-stopped never auto-resurrects) | `blockingSession`, `deferredStartKey`, `OverlapGuardTest` (7) | PASS |
| 3 | Snooze 10/20/30/60 + watchdog (+2 min) + snooze-expiry FULL ring; YES during snooze works | `SNOOZE_DURATIONS_MILLIS`, `SNOOZE_WATCHDOG_DELAY_MILLIS`, `SnoozeWatchdogTest` (10), `SnoozeExpiryRingTest` (12) | PASS |
| 4 | Nag every 10 min until answered (configurable 5–60, toggleable) | `nextNagAt`, `NagSkipTest` (13) | PASS |
| 5 | Ringer modes (NORMAL sound+vibrate, VIBRATE vibration only, SILENT visual only) | `ringBehaviorFor`, `RingBehaviorTest` (6) | PASS |
| 6 | Volume/power press during ring stops ONLY sound | `shouldSilenceSound`, `SilenceTriggerTest` (11) | PASS |
| 7 | Phase-guarded ring start (never fails silently — fallback notification) | `ringStartFallback`, `RingStartFallbackTest` (8) | PASS |
| 8 | Ring-start retry (5 s, once) on refusal | `ringRetryAllowed`, `SnoozeExpiryRingTest` (12) | PASS |
| 9 | Call suppression: nothing during a call (cellular + WhatsApp), reminder after call + gap (30 s/1 m/2 m/5 m, default 1 m); NO new permission (`AudioManager.getMode()`) | `isCallActive`, `CallDeferTest` (19); permissions = 9, no `READ_PHONE_STATE` | PASS |
| 10 | Wake chain roll-forward + self-healing (morning prompt never dies silently) | `nextWakeChainAfterMorningStarted`, `wakeChainNeedsRearm`, `WakeChainSelfHealTest` (10), `DecideWakePromptTest` (12) | PASS |
| 11 | Session-time setting re-arms alarms immediately (incl. auto-start ON/OFF) | `rearmSessionStarts` (public), `SettingsFragment.updateSettings` calls it, `SessionStartTimeTest` (13) | PASS |
| 12 | Exact alarms + reboot recovery; offline backup/restore; 9 permissions | `BootReceiver` (+ `TIME_SET` handling, `AuditRegressionTest` green), `BackupJson` round-trip tests, `aapt dump permissions` = 9 (identical set to old app) | PASS |

### 1.3 Tests executed (real runs, 2026-11-11)

- **Unit: OK (348 tests)** — `./run_tests.sh`, kotlinc 2.0.20 + JUnitCore
  4.13.2, fake clock. 31 classes named explicitly in the runner; executed
  == 348 `@Test` annotations (verified by count). Baseline was 346 in the
  old app; the delta is −5 (DisplayNumberTest 14 → 9 after the remap was
  removed) + 7 new `NewMedicineConfigTest` = 348.
- **DB: 203/203 PASS** — `tests/db_*.py` against real SQLite:
  27 + 19 + 19 + 15 + 17 + 41 + 29 + 23 + 13. Unchanged suite (the SQL
  choreography did not change); all pass unmodified.
- First unit run: 34 failures, ALL in tests encoding the old config
  (7/6/6 counts, 30-min gap, special drop) — updated to the new config;
  one failure was a test hardcoding the old package path for
  `BootReceiver.kt` (rename artifact, fixed). No production-logic failure
  was found by the suite; the only production change made after the first
  run was the read-time forcing of counts/special (see §1.1).

### 1.4 APK verification (executed)

- `aapt dump badging`: package `com.moon.newmedicine`, versionCode **1**,
  versionName **"1.0"**, sdk 26 / target 34.
- `apksigner verify` exit 0, same cert SHA-256
  `82f55aa23336c940e35c4fe718dc85c588766bc892663fdc1fd43bcdd9eca0dd`
  (copied `debug.keystore` from the old app).
- `aapt dump permissions`: **9 permissions**, identical set to the old app.
- Shipped DEX contains the new symbols (`sanitizeDoseInterval`,
  `DOSE_INTERVAL_PRESETS`, `isCallActive`, `sessionStartRearmPlan`,
  `displayNumberFor`).
- APK: 923,206 bytes at `~/workspace/new-medicine/NewMedicine-debug.apk`.
- The old app was rebuilt after the copy to confirm it still builds
  identically (vc 26, 927,302 bytes, BUILD OK); the `d8` "malformed
  kotlin.Metadata" R8 info traces appear identically in the old build —
  pre-existing noise, not an error.

### 1.5 Honest limitations

- No real-device testing is possible in the build env: alarm delivery,
  Doze, reboot restore, notification actions, wake detection, the updater
  download-install, and ring-service behavior are implemented per platform
  docs + unit/DB-tested but NOT runtime-verified on hardware. The first
  live morning (wake prompt), first live snooze expiry, first live call
  during a reminder, and first live session-time change on v1.0 are the
  real confirmations.
- The in-app updater has never been proven end-to-end on a device for
  either app — ship a direct link alongside every release.
- The legacy migrations (§1.1) are inert on fresh installs; a backup
  imported from the old app would have its counts forced to 4/4/4 at read
  (by design) but its dose HISTORY rows would import as-is.
