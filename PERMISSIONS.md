# New Medicine — Permissions & Required Settings

Package: `com.moon.newmedicine` · minSdk 26 · targetSdk 34

## Declared permissions (AndroidManifest.xml)

| Permission | Protection level | Why it is needed |
|---|---|---|
| `android.permission.POST_NOTIFICATIONS` | Runtime (Android 13+) | Shows the "Time for Drop N. Have you taken it?" reminder with YES/NO actions. Without it, reminders are in-app only. |
| `android.permission.SCHEDULE_EXACT_ALARM` | Special app access (Android 12+) | `AlarmManager.setExactAndAllowWhileIdle()` fires each dose reminder at the exact due time, including in Doze. Without it, Android may delay reminders by minutes. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Normal | Re-schedules all pending dose alarms from the local database after a device reboot. |
| `android.permission.VIBRATE` | Normal | Vibration for reminders, honoring the in-app vibration toggle. |
| `android.permission.PACKAGE_USAGE_STATS` | Special app access (Settings → "Usage access") | Morning wake detection: reads when the phone was last used (via `UsageStatsManager`) so the app can auto-prompt "Drops start karein?" on first morning phone use. The data never leaves the device. **Optional** — without it the app falls back to a single prompt at the wake-window start. |
| `android.permission.INTERNET` | Normal (no runtime prompt) | **Purpose-limited:** used ONLY for the in-app update check (`version.json` fetch) and the update APK download. The check is silent on failure and every reminder, dose record and setting keeps working fully offline / in airplane mode. |
| `android.permission.REQUEST_INSTALL_PACKAGES` | Install-time | Lets the app launch the system package installer for its own self-updates. Required to call `PackageManager.canRequestPackageInstalls()` and fire the install intent. |
| `android.permission.FOREGROUND_SERVICE` | Normal (install-time, no prompt) | **v2.1:** lets the reminder-alarm service run in the foreground while a dose reminder is ringing, so Android does not kill the sound after a few seconds. The service runs only while a reminder rings (max 2 minutes) and stops itself. |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Normal (install-time, no prompt) | **v2.1:** declares that the foreground service above is of the "media playback" type — it exists solely to loop the reminder sound the user selected. |

**Deliberately NOT requested:** `ACCESS_NETWORK_STATE`, or any location/storage/contacts permission. Network code exists ONLY in the update module (`update/UpdateFetcher.kt` + `UpdateInstaller.kt`); the scheduling engine, database and alarms remain fully offline (verified: no `java.net` / `android.net` imports in the core module).

No advertising, analytics, or crash-reporting SDKs are bundled.

**v1.2 note:** the per-session "Stop session" feature adds **no new
permissions** — it reuses the existing database and `AlarmManager` cancellation
paths. The manifest permission set is unchanged from v1.1.

## Settings the user must enable (first-run guidance)

1. **Notifications** — Android 13+ asks at first launch. If denied, the app shows a banner on the main screen linking to the system notification settings. In-app YES/NO confirmation still works when the dose screen is open.
2. **Alarms & reminders (exact alarm)** — On Android 12+, the app checks `AlarmManager.canScheduleExactAlarms()`. If not allowed, a banner links to *Settings → Apps → New Medicine → Alarms & reminders*. Until allowed, reminders fall back to inexact timing and the app says so honestly instead of pretending exact timing.
3. **Battery optimization exemption (recommended)** — *Settings → Apps → New Medicine → Battery → Unrestricted*. On some OEM skins (notably Xiaomi, Oppo, Vivo, Samsung), aggressive battery savers can still delay or suppress exact alarms. The app cannot override this; the Settings screen explains it.
4. **Usage access (optional, recommended for wake detection)** — In the app: *Settings → Morning wake detection → Usage access settings*, then enable "New Medicine" in the system list. Without this grant, wake detection falls back to a single "Drops start karein?" prompt at the wake-window start (default 06:00) instead of detecting actual first phone use. The usage data is read on-device only and never sent anywhere.
5. **"Install unknown apps" (one-time, only when updating)** — The first time a self-update APK finishes downloading, Android may block the install. The app shows a guidance dialog with a button opening *Settings → Install unknown apps → New Medicine → Allow*. This is a standard Android step for apps installed outside the Play Store; the downloaded APK comes only from the project's own `version.json` `apkUrl`.

## Honest platform limitations

- **Force-stop**: if the user force-stops the app, Android cancels all its alarms and the app cannot reschedule them until it is opened again. This is an Android platform restriction affecting every reminder app.
- **Doze / battery savers**: `setExactAndAllowWhileIdle` still allows the system a small delivery window under extreme battery saver modes; an overdue dose is then clearly badged OVERDUE rather than silently shifted.
- **Reboot**: alarms are restored on `BOOT_COMPLETED` from the local database. If the device stays off past a dose time, that dose is marked overdue/missed on next launch — never silently rescheduled.
- **Manual clock changes / time-zone travel**: `TIME_SET` / `TIMEZONE_CHANGED` trigger a full reconcile of alarms against the database; day boundaries follow the device's local date.
