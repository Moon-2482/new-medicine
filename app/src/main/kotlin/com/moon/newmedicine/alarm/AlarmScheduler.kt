package com.moon.newmedicine.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.core.Dose
import com.moon.newmedicine.core.ScheduleEngine
import com.moon.newmedicine.core.SessionType

/**
 * Owns every AlarmManager interaction: one exact alarm per dose (stable
 * request code from [ScheduleEngine.alarmRequestCode], so retries overwrite
 * the same slot and duplicates are structurally impossible) plus the
 * auto session-start alarms for afternoon/evening.
 *
 * Framework APIs only; no AndroidX.
 */
class AlarmScheduler(private val context: Context) {

    companion object {
        const val ACTION_ALARM = "com.moon.newmedicine.ALARM"
        const val ACTION_SESSION_START = "com.moon.newmedicine.SESSION_START"
        /**
         * v2.10: the call-deferred re-fire. Same receiver
         * ([AlarmReceiver]) as the dose alarm itself, so the deferred
         * fire runs the identical fire path: it re-checks the call
         * state, rings normally when the call is over, and re-arms
         * itself at now + gap when the call is still on.
         */
        const val ACTION_CALL_DEFERRED = "com.moon.newmedicine.CALL_DEFERRED_FIRE"
        const val ACTION_YES = "com.moon.newmedicine.ACTION_YES"
        const val ACTION_NO = "com.moon.newmedicine.ACTION_NO"
        /** v2.0: "Skip" action on a dose reminder notification. */
        const val ACTION_SKIP = "com.moon.newmedicine.ACTION_SKIP"
        /**
         * v2.3: "Snooze" — routed via DoseActionReceiver only when the
         * intent arrives as a broadcast; the reminder's own Snooze button
         * opens the chooser activity directly (see NotificationHelper).
         * Kept as a first-class action so every entry point shares one
         * handling path.
         */
        const val ACTION_SNOOZE = "com.moon.newmedicine.ACTION_SNOOZE"

        /**
         * v2.0: PendingIntent for the notification's "Skip" action. Distinct
         * slot per dose (which=3), mirroring the YES (1) / NO (2) action
         * slots in [NotificationHelper] — the three actions can never
         * collide.
         */
        fun skipIntent(context: Context, doseId: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                ScheduleEngine.alarmRequestCode(doseId) + 3,
                Intent(context, DoseActionReceiver::class.java)
                    .setAction(ACTION_SKIP)
                    .putExtra(EXTRA_DOSE_ID, doseId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        const val ACTION_WAKE_CHECK = "com.moon.newmedicine.WAKE_CHECK"
        const val ACTION_WAKE_START = "com.moon.newmedicine.WAKE_START"
        const val ACTION_WAKE_SNOOZE = "com.moon.newmedicine.WAKE_SNOOZE"

        const val EXTRA_DOSE_ID = "doseId"
        const val EXTRA_SESSION_TYPE = "sessionType"
        const val EXTRA_DAY_KEY = "dayKey"
        const val EXTRA_FORCE_WAKE_CHECK = "forceWakeCheck"

        /**
         * Stable alarm slots for the morning wake-detection chain. Exactly one
         * slot per purpose on purpose: re-scheduling (FLAG_UPDATE_CURRENT)
         * overwrites the previous alarm, so duplicate wake checks are
         * structurally impossible.
         */
        private const val WAKE_CHAIN_REQUEST_CODE = 0x6A4E01
        private const val WAKE_SNOOZE_REQUEST_CODE = 0x6A4E02

        private const val WAKE_PREFS = "wake_chain"
        private const val KEY_WAKE_NEXT_CHECK_AT = "wakeNextCheckAt"
    }

    private fun alarmManager(): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** True when the app is allowed to schedule exact alarms. */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) alarmManager().canScheduleExactAlarms() else true

    /** Opens the system "Alarms & reminders" screen for this app (API 31+). */
    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Arms the exact alarm for [dose]'s next reminder. No-op when the dose
     * needs no reminder (confirmed/missed). On SecurityException (exact alarms
     * revoked) the user gets a notification explaining what to do instead of
     * a silent missed dose.
     */
    fun scheduleDose(dose: Dose) {
        val triggerAt = dose.nextReminderAt() ?: return
        scheduleDoseAt(dose, triggerAt)
    }

    /**
     * Arms the exact alarm for [dose] at an EXPLICIT trigger time (the
     * overdue catch-up reminder fires immediately, not at the dose's
     * original reminder time). Same PendingIntent identity as
     * [scheduleDose], so it overwrites rather than duplicates. Same
     * SecurityException → conflict-notification behavior.
     */
    fun scheduleDoseAt(dose: Dose, triggerAtMillis: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            ScheduleEngine.alarmRequestCode(dose.id),
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_ALARM)
                .putExtra(EXTRA_DOSE_ID, dose.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (e: SecurityException) {
            (context.applicationContext as? DropsApp)?.notificationHelper
                ?.showConflictNotification(
                    "Exact alarms are not allowed for New Medicine. Open the app and " +
                        "enable \"Alarms & reminders\" so dose reminders fire on time."
                )
        }
    }

    /** Cancels the alarm for [doseId] (the same PendingIntent identity). */
    fun cancelDose(doseId: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            ScheduleEngine.alarmRequestCode(doseId),
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager().cancel(pi)
        pi.cancel()
    }

    /**
     * v2.4: arms the snooze watchdog for [dose] — the snooze expiry's
     * independent second delivery chance, on its OWN request-code slot
     * ([ScheduleEngine.snoozeWatchdogRequestCode]) so it never disturbs
     * the dose's own slot. The intent is identical to the dose alarm's
     * (same receiver, same action, same extra), so a watchdog fire runs
     * the exact same receiver path as a dose fire: if the primary expiry
     * fire rang and the dose was answered, the watchdog fire is a stale
     * no-op; if the primary fire was lost, the watchdog rings.
     */
    fun scheduleSnoozeWatchdog(dose: Dose, triggerAtMillis: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            ScheduleEngine.snoozeWatchdogRequestCode(dose.id),
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_ALARM)
                .putExtra(EXTRA_DOSE_ID, dose.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (e: SecurityException) {
            // The dose's own snooze alarm is armed independently; a
            // refused watchdog only loses the redundancy, never the
            // primary reminder — and the reconcile nets re-arm both.
        }
    }

    /** v2.4: cancels [doseId]'s snooze watchdog (same identity as [scheduleSnoozeWatchdog]). */
    fun cancelSnoozeWatchdog(doseId: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            ScheduleEngine.snoozeWatchdogRequestCode(doseId),
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager().cancel(pi)
        pi.cancel()
    }

    /**
     * v2.10: arms the call-deferred re-fire for [doseId] at an EXPLICIT
     * trigger time — the "call ke baad" gap after a fire that found a
     * call active, or after a call started mid-ring. Own request-code
     * slot ([ScheduleEngine.callDeferredRequestCode]) so it never
     * disturbs the dose's own alarm slot; same PendingIntent identity
     * on re-arm, so a re-deferral overwrites rather than duplicates.
     * On SecurityException (exact alarms revoked) the user gets the
     * conflict notification instead of a silently lost re-fire.
     */
    fun scheduleCallDeferred(doseId: Long, triggerAtMillis: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            ScheduleEngine.callDeferredRequestCode(doseId),
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_CALL_DEFERRED)
                .putExtra(EXTRA_DOSE_ID, doseId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (e: SecurityException) {
            (context.applicationContext as? DropsApp)?.notificationHelper
                ?.showConflictNotification(
                    "Exact alarms are not allowed for New Medicine. Open the app and " +
                        "enable \"Alarms & reminders\" so dose reminders fire on time."
                )
        }
    }

    /** v2.10: cancels [doseId]'s call-deferred re-fire (same identity as [scheduleCallDeferred]). */
    fun cancelCallDeferred(doseId: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            ScheduleEngine.callDeferredRequestCode(doseId),
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_CALL_DEFERRED),
            PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager().cancel(pi)
        pi.cancel()
    }

    /** Arms the auto-start alarm for an afternoon/evening session. */
    fun scheduleSessionStart(type: SessionType, dayKey: String, triggerAt: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            ScheduleEngine.sessionStartRequestCode(type, dayKey),
            Intent(context, SessionStartReceiver::class.java)
                .setAction(ACTION_SESSION_START)
                .putExtra(EXTRA_SESSION_TYPE, type.name)
                .putExtra(EXTRA_DAY_KEY, dayKey),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            // The day-rollover path retries when the device wakes; the user
            // can also start the session from the app.
        }
    }

    fun cancelSessionStart(type: SessionType, dayKey: String) {
        val pi = PendingIntent.getBroadcast(
            context,
            ScheduleEngine.sessionStartRequestCode(type, dayKey),
            Intent(context, SessionStartReceiver::class.java).setAction(ACTION_SESSION_START),
            PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager().cancel(pi)
        pi.cancel()
    }

    /**
     * Arms one wake-detection check. [force]=false is the regular chain tick
     * (every [DoseSettings.wakeCheckIntervalMinutes] inside the window);
     * [force]=true is the one-off "15 min baad" snooze. The two live in
     * separate stable slots so the snooze never clobbers the chain.
     *
     * The scheduled trigger is persisted so Settings → "Wake status" can
     * show it. On SecurityException (exact alarms revoked) the user gets a
     * notification explaining what to do — a silently dead wake chain is
     * the worst possible failure here.
     */
    fun scheduleWakeCheck(triggerAt: Long, dayKey: String, force: Boolean) {
        val code = if (force) WAKE_SNOOZE_REQUEST_CODE else WAKE_CHAIN_REQUEST_CODE
        val pi = PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, WakeCheckReceiver::class.java)
                .setAction(ACTION_WAKE_CHECK)
                .putExtra(EXTRA_DAY_KEY, dayKey)
                .putExtra(EXTRA_FORCE_WAKE_CHECK, force),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            if (!force) {
                context.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_WAKE_NEXT_CHECK_AT, triggerAt).apply()
            }
        } catch (e: SecurityException) {
            (context.applicationContext as? DropsApp)?.notificationHelper
                ?.showConflictNotification(
                    "Subah ki wake detection nahi chal sakti: system settings me " +
                        "New Medicine ke liye \"Alarms & reminders\" allow karein."
                )
        }
    }

    /** Cancels both the wake chain and any pending wake snooze. */
    fun cancelWakeChain() {
        for (code in intArrayOf(WAKE_CHAIN_REQUEST_CODE, WAKE_SNOOZE_REQUEST_CODE)) {
            val pi = PendingIntent.getBroadcast(
                context,
                code,
                Intent(context, WakeCheckReceiver::class.java).setAction(ACTION_WAKE_CHECK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager().cancel(pi)
            pi.cancel()
        }
        context.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_WAKE_NEXT_CHECK_AT).apply()
    }

    /**
     * True when a wake-check alarm is currently registered with the OS
     * (FLAG_NO_CREATE: reports without creating anything).
     */
    fun isWakeChainArmed(): Boolean {
        val pi = PendingIntent.getBroadcast(
            context,
            WAKE_CHAIN_REQUEST_CODE,
            Intent(context, WakeCheckReceiver::class.java).setAction(ACTION_WAKE_CHECK),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        return pi != null
    }

    /** Last triggerAt persisted by [scheduleWakeCheck], or null if none. */
    fun nextScheduledCheckAt(): Long? {
        val v = context.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_WAKE_NEXT_CHECK_AT, -1L)
        return if (v > 0) v else null
    }
}
