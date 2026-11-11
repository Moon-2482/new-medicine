# New Medicine — Build Instructions

App: **New Medicine** 1.0 · package `com.moon.newmedicine` · minSdk 26 · targetSdk 34 · Kotlin

A separate drops-reminder app for the user's new medicine, built from the
Drops Reminder v2.11 codebase (`~/workspace/medicine-drops/`, untouched).
Differences from the old app: **4 drops per session** (all sessions, all
REGULAR — no special drop), **10-minute gap** between drops (setting:
presets 5 / 7 / 10 / 15 min, default 10), session defaults afternoon
13:45 / evening 17:00, violet launcher icon, own GitHub repo
`Moon-2482/new-medicine` + own `version.json` for self-update.

## One-command build

```bash
cd ~/workspace/new-medicine
./build.sh
```

Output: `~/workspace/new-medicine/NewMedicine-debug.apk` (debug-signed, installable).

## What the script does

The Gradle daemon cannot start in this sandbox, so `build.sh` drives the
toolchain directly. Every input is local; no network is used at any step.

| Step | Tool | Input → Output |
|---|---|---|
| 1. `aapt2 compile` | build-tools 34.0.0 | `app/src/main/res` → `out/app/res.zip` |
| 2. `aapt2 link` | build-tools 34.0.0 | manifest + res → unsigned APK + `R.java` |
| 3. `kotlinc` | Kotlin 2.0.20 (cached compiler) | core + app + `R.java` → `.class` files (JVM 1.8 bytecode) |
| 4. `d8` | build-tools 34.0.0 | classes + kotlin-stdlib → `classes.dex` (`--min-api 26`) |
| 5. `zip` | — | `classes.dex` added to the APK |
| 6. `zipalign -f 4` | build-tools 34.0.0 | aligned APK |
| 7. `apksigner sign` | build-tools 34.0.0 | debug key → `NewMedicine-debug.apk` |
| 8. verify | `apksigner verify`, `aapt2 dump badging` | signature + package metadata printed |

Fixed paths used by the script (edit at the top of `build.sh` if your layout differs):

- Android SDK: `~/workspace/.android-sdk` (build-tools 34.0.0, platform android-34)
- JDK for keytool: `~/workspace/.jdk`
- Kotlin compiler classpath: `.kotlinc-cp.txt` (jars resolved from `~/.gradle/caches`)
- Debug keystore: `./debug.keystore` (copied from the old app — SAME cert
  SHA-256 `82f55aa2…ca0dd`; **debug only**)

## Unit tests (scheduling engine)

```bash
cd ~/workspace/new-medicine
./run_tests.sh
```

The runner compiles core main + core test with kotlinc 2.0.20 and runs
EVERY test class explicitly via JUnitCore (31 classes — a class missing
from the list is silently skipped, the v1.9 lesson). Expected:
`OK (348 tests)`.

## Database tests (SQLite guarantees)

```bash
cd ~/workspace/new-medicine/tests
for f in db_*.py; do python3 "$f"; done
```

Expected: 27 + 19 + 19 + 15 + 17 + 41 + 29 + 23 + 13 = **203/203 PASS**
(real SQLite; the SQL choreography is unchanged from the old app).

## Project layout

```
new-medicine/
  build.sh / run_tests.sh     # the build / the test runner
  BUILD.md / TEST-REPORT.md / PERMISSIONS.md
  debug.keystore            # local debug signing key (do not ship)
  NewMedicine-debug.apk     # final deliverable (copied here by build.sh)
  core/src/main/kotlin/com/moon/newmedicine/core/
    Model.kt                # domain model (no Android, no network)
    ScheduleEngine.kt       # pure scheduling logic + wake detection (unit-tested)
    BackupJson.kt           # offline backup export/import (unit-tested)
  core/src/test/kotlin/...  # 348 JUnit tests with FakeClock
  app/src/main/
    AndroidManifest.xml     # 9 permissions (same set as the old app)
    kotlin/com/moon/newmedicine/
      DropsApp.kt           # Application: rollback-safe startup reconcile + wake re-arm
      data/DropsDatabase.kt # SQLiteOpenHelper + transactions
      repo/DropsRepository.kt # engine+DB+alarms glue (all writes transactional)
      alarm/                # AlarmScheduler, AlarmReceiver, DoseActionReceiver,
                            # SessionStartReceiver, BootReceiver,
                            # WakeCheckReceiver, WakeActionReceiver, WakeUsage
      notify/NotificationHelper.kt  # dose reminders + "Drops start karein?" prompt
      ui/                   # MainActivity + Main/Dose/History/Settings fragments
      util/TimeFormat.kt
    res/                    # layouts, drawables, values (light Material theme)
```

## Dependency policy

The app ships **zero external libraries**: framework `android.*` APIs +
Kotlin stdlib only (stdlib is dexed into the APK). No AndroidX, no Room, no
network stack. This is deliberate: fewer moving parts, deterministic offline
builds, and nothing to phone home.

## Known build noise (pre-existing, also in the old app)

`d8` prints `Info: Class ... has malformed kotlin.Metadata` stack traces
for several classes. These are R8 info-level messages, not errors: the dex
builds, the APK verifies, and the old app's build shows the identical
output. Do not "fix" them by changing the build.
