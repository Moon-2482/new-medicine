package com.moon.newmedicine.core

/**
 * Pure-Kotlin domain model for the New Medicine app.
 *
 * This module has ZERO Android and ZERO network dependencies on purpose:
 * every scheduling decision is a pure function of (saved state + clock),
 * which makes the timing logic unit-testable with a fake clock and guarantees
 * the app behaves identically in airplane mode.
 *
 * Times are epoch-millis (UTC). The [dayKey] is the local calendar date
 * (yyyy-MM-dd) of the session start in the device's time zone — this is what
 * the once-daily special drop is keyed on.
 */

/** Injectable time source. Production uses the device clock; tests use a fake. */
interface Clock {
    fun nowMillis(): Long
}

class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

enum class SessionType { MORNING, AFTERNOON, EVENING }

enum class DoseKind { REGULAR, SPECIAL }

enum class DoseStatus { PENDING, CONFIRMED, MISSED, CANCELLED }

enum class SessionStatus { ACTIVE, COMPLETED, CONFLICT, CANCELLED }

/**
 * All user-configurable dosing rules. These are the USER'S requested settings,
 * not medical advice — the app never invents or changes medical instructions.
 */
data class DoseSettings(
    /**
     * New Medicine: interval between consecutive drops once the previous
     * drop is confirmed — 10 minutes (the old Drops Reminder used 30).
     * Changeable in Settings (presets 5 / 7 / 10 / 15 min).
     */
    val doseIntervalMillis: Long = 10L * 60 * 1000,
    /** Retry delay after the user answers NO ("Not Yet"). */
    val retryIntervalMillis: Long = 5L * 60 * 1000,
    /** Daily cutoff as minutes-of-day in local time (default 20:00). */
    val cutoffMinutesOfDay: Int = 20 * 60,
    /** New Medicine: 4 drops in EVERY session (morning / afternoon / evening). */
    val morningDropCount: Int = 4,
    val afternoonDropCount: Int = 4,
    val eveningDropCount: Int = 4,
    /**
     * New Medicine: NO special drop — all 4 drops are REGULAR in every
     * session. Forced false at read time in the repository, so no saved
     * or imported value can ever re-enable one.
     */
    val specialDropEnabled: Boolean = false,
    /** 1-based position of the special drop inside the morning session. */
    val specialDropPosition: Int = 6,
    val afternoonStartMinutesOfDay: Int = 13 * 60 + 45,
    val eveningStartMinutesOfDay: Int = 17 * 60,
    val autoStartAfternoon: Boolean = true,
    val autoStartEvening: Boolean = true,
    /** How long after dueAt a pending dose is labelled OVERDUE. */
    val overdueGraceMillis: Long = 2L * 60 * 1000,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    /**
     * Morning wake-detection window (local minutes-of-day). When the user
     * starts using the phone inside this window and the morning session has
     * not started yet, the app pushes a "Drops start karein?" prompt.
     * Defaults: 06:00–11:00, checked every 15 minutes.
     */
    val wakeWindowStartMinutesOfDay: Int = 6 * 60,
    val wakeWindowEndMinutesOfDay: Int = 11 * 60,
    /** How often (minutes) the wake check runs inside the window. */
    val wakeCheckIntervalMinutes: Int = 15,
    /**
     * v2.0: missed-drop nagging — while a dose stays PENDING past its due
     * time, the reminder re-fires every [nagIntervalMillis] until the user
     * answers YES/NO/Skip (or the dose is otherwise resolved).
     */
    val nagEnabled: Boolean = true,
    /** v2.0: interval between nag reminders (default 10 minutes). */
    val nagIntervalMillis: Long = 10L * 60 * 1000,
    /**
     * v2.10: "Call ke baad" gap — when a dose reminder would fire during
     * an active phone call, the whole reminder (sound, vibration,
     * notification) is suppressed and re-fired this long after the
     * fire that found the call active. Presets: 30s / 1m / 2m / 5m
     * ([ScheduleEngine.CALL_DEFER_GAP_PRESETS_MILLIS]), default 1 min.
     */
    val callDeferGapMillis: Long = 60L * 1000,
)

/** Wake-detection window parameters, derived from [DoseSettings]. */
data class WakeWindow(
    val startMinutesOfDay: Int,
    val endMinutesOfDay: Int,
    val checkIntervalMillis: Long,
)

/** Outcome of one wake-check evaluation (used by the Android glue). */
enum class WakeCheckOutcome {
    /** A "Drops start karein?" prompt was shown (or refreshed). */
    PROMPTED,
    /** No prompt: outside window, stale usage, already prompted this tick, … */
    NO_PROMPT,
    /** Morning session already started — the wake chain is cancelled. */
    MORNING_ALREADY_STARTED,
    /** The wake window ended — the chain stops until tomorrow. */
    WINDOW_ENDED,
}

data class Dose(
    val id: Long = 0L,
    val sessionId: Long = 0L,
    /** 1-based number of the drop inside its session. */
    val dropNumber: Int = 1,
    val kind: DoseKind = DoseKind.REGULAR,
    /** Local date (yyyy-MM-dd) of the session start. */
    val dayKey: String = "",
    /** When this drop becomes due (epoch millis). Never mutated after insert. */
    val dueAt: Long = 0L,
    /** Actual confirmation timestamp (epoch millis), set exactly once. */
    val confirmedAt: Long? = null,
    val status: DoseStatus = DoseStatus.PENDING,
    /** How many times the user answered NO for this dose. */
    val retryCount: Int = 0,
    /** When the next NO-retry reminder must fire (epoch millis), if any. */
    val nextRetryAt: Long? = null,
) {
    /**
     * The instant the next reminder for THIS dose must fire, or null when this
     * dose needs no further reminders (confirmed / missed / no retry armed).
     */
    fun nextReminderAt(): Long? =
        if (status == DoseStatus.PENDING) (nextRetryAt ?: dueAt) else null
}

data class DoseSession(
    val id: Long = 0L,
    val type: SessionType = SessionType.MORNING,
    /** Local date (yyyy-MM-dd) of the session start. */
    val dayKey: String = "",
    val startedAt: Long = 0L,
    val status: SessionStatus = SessionStatus.ACTIVE,
)

/** One dose of a session plan, before it is persisted. */
data class PlannedDose(
    val dropNumber: Int,
    val kind: DoseKind,
    val dueAt: Long,
)

sealed interface SessionPlan {
    data class Ok(val doses: List<PlannedDose>) : SessionPlan
    data class Conflict(val reason: String) : SessionPlan
}

/** Result of confirming a dose with YES. */
data class ConfirmResult(
    val dose: Dose,
    /**
     * Due time for the NEXT drop, computed as confirmedAt + doseInterval.
     * Null when this was the last drop of the session, or when the dose was
     * already confirmed (idempotent double-tap).
     */
    val nextDueAt: Long?,
    /** True when the dose was already confirmed — the call changed nothing. */
    val unchanged: Boolean,
    /**
     * True when the tap came before the dose was due (outside the 60s
     * grace): the call was refused and changed absolutely nothing — no
     * status change, no timestamp, no next-dose scheduling, no alarm
     * changes. Guards against accidental early YES taps.
     */
    val notDueYet: Boolean = false,
)

/** Result of declining a dose with NO. */
data class DeclineResult(
    val dose: Dose,
    /**
     * True when the tap came before the dose was due (outside the 60s
     * grace): the call was refused and changed absolutely nothing.
     */
    val notDueYet: Boolean = false,
)

/**
 * v2.0: result of skipping a dose ("Skip" action). Shape mirrors
 * [ConfirmResult] so every caller (notification receiver, in-app UI)
 * handles the three outcomes the same way.
 */
data class SkipResult(
    val dose: Dose,
    /**
     * Due time for the NEXT drop, computed as skip time + doseInterval.
     * Null when this was the last drop of the session (the session was
     * completed), when the dose was not PENDING (idempotent no-op), or
     * when the tap came before the dose was due.
     */
    val nextDueAt: Long?,
    /** True when the dose was not PENDING — the call changed nothing. */
    val unchanged: Boolean,
    /**
     * True when the tap came before the dose was due (outside the 60s
     * grace): the call was refused and changed absolutely nothing — no
     * status change, no timestamp, no next-dose scheduling, no alarm
     * changes. Same guard as [ConfirmResult.notDueYet].
     */
    val notDueYet: Boolean = false,
)

/**
 * v2.3: result of snoozing a dose ("Snooze" action). Shape mirrors
 * [SkipResult] so every caller (notification receiver, in-app UI, the
 * snooze chooser) handles the outcomes the same way.
 */
data class SnoozeResult(
    val dose: Dose,
    /**
     * When the snoozed reminder fires (epoch millis): now + the chosen
     * duration. Null when the dose was not PENDING or the tap was refused.
     */
    val snoozedUntil: Long?,
    /** True when the dose was not PENDING — the call changed nothing. */
    val unchanged: Boolean,
    /**
     * True when the tap came before the dose was due (outside the 60s
     * grace): the call was refused and changed absolutely nothing — same
     * guard as [ConfirmResult.notDueYet].
     */
    val notDueYet: Boolean = false,
)

/**
 * v2.4: what a dose-reminder alarm fire must do, decided purely by
 * [ScheduleEngine.doseFireDecision] (extracted from AlarmReceiver so the
 * whole fire path is unit-testable — in v2.3 the branches lived inline
 * in the receiver and no test could reach them).
 */
enum class FireDecision {
    /** Dose no longer PENDING: stale alarm — stop any ring, clear the tray entry. */
    STALE,

    /** Fire landed inside a snooze window: stay silent, re-arm for the expiry. */
    SNOOZED,

    /** Fire for a dose whose ring is already sounding: do not restart it (just re-arm the nag). */
    ALREADY_RINGING,

    /** Ring the full reminder (foreground-service ring, plain notification as fallback). */
    RING,
}

/**
 * v2.6: how a dose-reminder ring must behave for the phone's current
 * ringer mode, decided purely by [ScheduleEngine.ringBehaviorFor]. The
 * reminder sound deliberately plays on the ALARM stream, which bypasses
 * the ringer mode entirely — without this decision the ring would sound
 * at full volume even with the phone on vibrate or silent (the user's
 * order: on vibrate, only vibration; on silent, neither).
 */
enum class RingBehavior {
    /** Ringer NORMAL: loop the selected sound, exactly as before v2.6. */
    SOUND_AND_VIBRATE,

    /** Ringer VIBRATE: no sound at all — vibrate in a repeating loop instead. */
    VIBRATE_ONLY,

    /** Ringer SILENT: visual only — the notification posts, nothing sounds or buzzes. */
    VISUAL_ONLY,
}

/**
 * v2.8: how far a ring start in `ReminderAlarmService` had progressed
 * when something in it failed. The foreground post is the dividing
 * line: before it, nothing of the reminder is visible yet; after it,
 * the reminder itself is up and must never be torn down by a later
 * failure (see [ScheduleEngine.ringStartFallback]).
 */
enum class RingStartPhase {
    /** Teardown / placeholder / `startForeground` not yet completed. */
    BEFORE_FOREGROUND,

    /** Foreground notification posted; alert, content swap, cap, triggers follow. */
    AFTER_FOREGROUND,
}

/**
 * v2.9: what user-visible output a dose-reminder alarm fire must
 * produce, decided purely by [ScheduleEngine.doseFireOutput]. The
 * contract the 2026-10-24 snooze-expiry failure pinned down: a snooze
 * expiry (primary or watchdog) must produce the FULL ring exactly
 * like a fresh dose fire — and a failed ring start must yield the
 * fallback note, never silence.
 */
enum class FireOutput {
    /** Full ring: foreground-service ring (mode-aware alert) + YES/NO/Snooze/Skip actions. */
    FULL_RING,

    /** Ring start failed (refused / died): the plain dose reminder on the mode-aware channel. */
    FALLBACK_NOTE,

    /** Fire inside a snooze window: stay silent, re-arm for the expiry. */
    SILENT_REARM,

    /** Stale dose: stop any ring, clear the tray entry. */
    CANCEL_TRAY,

    /** Ring already sounding: do not restart it (the nag re-arm still runs). */
    KEEP_RINGING,
}

/**
 * v2.8: what the ring service does when a ring start fails (decided
 * purely by [ScheduleEngine.ringStartFallback]). The ring is sacred:
 * a reminder must always produce user-visible output, and an add-on
 * failure must never kill a live ring.
 */
enum class RingStartFallback {
    /** Post the plain dose reminder instead, then stop the service. */
    POST_PLAIN_REMINDER,

    /** Stop the service with no post (the Settings test ring — the user is watching the app). */
    STOP_QUIETLY,

    /** The ring is already up: log the failure, re-arm the cap, keep ringing. */
    CONTINUE_RING,
}

data class Progress(
    val taken: Int,
    val pending: Int,
    val missed: Int,
    val upcoming: Int,
    val total: Int,
) {
    val percent: Int get() = if (total == 0) 0 else (taken * 100) / total
}

/** One alarm the OS should have scheduled. */
data class AlarmSpec(
    val doseId: Long,
    val triggerAt: Long,
    val requestCode: Int,
    val alarmKey: String,
)

data class ReconcileResult(
    /** Alarms that must exist (schedule or re-schedule these). */
    val schedule: List<AlarmSpec>,
    /** Dose ids whose alarms must be cancelled (no longer pending). */
    val cancelDoseIds: List<Long>,
)

/**
 * Read-only snapshot of the morning wake-detection health, assembled by the
 * repository for the Settings "Wake status" diagnostics row. Every field is
 * plain data so the row is trivially testable and explainable.
 */
data class WakeDiagnostics(
    /** Usage access (PACKAGE_USAGE_STATS) granted in system Settings. */
    val usageAccessGranted: Boolean,
    /** SCHEDULE_EXACT_ALARM allowed — without this the wake chain cannot tick. */
    val exactAlarmAllowed: Boolean,
    /** POST_NOTIFICATIONS enabled — without this prompts are invisible. */
    val notificationsEnabled: Boolean,
    /** A wake-check PendingIntent is currently registered with AlarmManager. */
    val chainArmed: Boolean,
    /** Epoch millis of the next scheduled wake check, or null if none. */
    val nextCheckAtMillis: Long?,
    /** Outcome of the most recent wake evaluation, or null if never ran. */
    val lastCheckOutcome: String?,
    /** Epoch millis of the most recent wake evaluation, or null. */
    val lastCheckAtMillis: Long?,
) {
    /**
     * One-line-per-fact human-readable rendering (Roman Urdu labels, matching
     * the app's language). Pure function of the snapshot + zone.
     */
    fun format(zone: java.time.ZoneId): String {
        fun yesNo(v: Boolean) = if (v) "haan" else "NAHI"
        fun time(ms: Long?): String = ms?.let {
            java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(it), zone
            ).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        } ?: "—"
        return buildString {
            appendLine("Usage access: ${yesNo(usageAccessGranted)}")
            appendLine("Exact alarms: ${yesNo(exactAlarmAllowed)}")
            appendLine("Notifications: ${yesNo(notificationsEnabled)}")
            appendLine("Wake chain armed: ${yesNo(chainArmed)}")
            appendLine("Next check: ${time(nextCheckAtMillis)}")
            append("Last check: ${(lastCheckOutcome ?: "—")} ${time(lastCheckAtMillis)}".trim())
        }
    }
}
