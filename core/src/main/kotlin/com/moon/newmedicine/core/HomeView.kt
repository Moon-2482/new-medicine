package com.moon.newmedicine.core

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure presentation model for the v2.0 home screen.
 *
 * The 17:00 overlap incident showed the old home UI could be misread: it was
 * possible to see an "Afternoon" label while an evening drop was the one due.
 * This model makes three facts impossible to confuse:
 *   1. WHICH session is active (the hero names the session that owns the due drop),
 *   2. WHICH drop is due (huge "DROP n OF m" + countdown + YES/NO bound to that dose),
 *   3. WHAT happens next (the "next up" line + a timeline that names deferrals).
 *
 * Everything here is a pure function of (saved state + clock): no Android,
 * no I/O. The fragment supplies the repo data and binds the result.
 */
sealed interface HeroState {

    /**
     * The active session's current drop. [doseId] is what the hero
     * YES/NO/Skip buttons act on — always the dose shown, never another.
     */
    data class DueDrop(
        val doseId: Long,
        val dropNumber: Int,
        val total: Int,
        val sessionType: SessionType,
        val isSpecial: Boolean,
        val dueAtMillis: Long,
        val nextReminderAtMillis: Long?,
        val isOverdue: Boolean,
        val overdueByMin: Long,
        /** PENDING and due (60s grace) — YES/NO/Skip are enabled only then. */
        val canRespond: Boolean,
    ) : HeroState

    /** No ACTIVE session; the flags say which Start buttons may run. */
    data class NoActiveSession(
        val canStartMorning: Boolean,
        val canStartAfternoon: Boolean,
        val canStartEvening: Boolean,
    ) : HeroState

    /**
     * An auto start was deferred because another session was ACTIVE.
     * [blockingType] is the session that was running when the deferral was
     * recorded (null only if no other session row exists at all).
     *
     * v2.3: [startsAtMillis] is the planned start once the blocker has
     * cleared (blocker-clear + 30 min); null while the blocker is still
     * running and no start time is known yet.
     */
    data class DeferredStart(
        val waitingType: SessionType,
        val blockingType: SessionType?,
        val startsAtMillis: Long? = null,
    ) : HeroState

    /** Every session for today is done. */
    data object AllDone : HeroState
}

/** Visual status of one session row in the TODAY TIMELINE card. */
enum class TimelineStatus {
    DONE,
    IN_PROGRESS,
    UPCOMING,
    WAITING_DEFERRED,
    NOT_STARTED,
}

/** One row of the TODAY TIMELINE card: the session, its status, human text. */
data class TimelineRow(
    val type: SessionType,
    val status: TimelineStatus,
    val detail: String,
)

object HomeView {

    /**
     * Decides what the hero card shows.
     *
     * @param nextDose today's earliest-due PENDING dose (repo.nextDueDose).
     * @param session today's ACTIVE session (repo.currentSession), null if none.
     * @param sessions today's session rows (repo.getTodaySessions).
     * @param deferred true when a same-day deferred-start flag is set for the type.
     * @param doneOf true when the type's session is done (COMPLETED or taken == total).
     */
    fun heroState(
        nextDose: Dose?,
        session: DoseSession?,
        settings: DoseSettings,
        nowMillis: Long,
        sessions: List<DoseSession>,
        deferred: (SessionType) -> Boolean,
        doneOf: (SessionType) -> Boolean,
        deferredStartAtOf: (SessionType) -> Long? = { null },
    ): HeroState {
        if (session != null && nextDose != null && nextDose.status == DoseStatus.PENDING) {
            // The hero always names the session that OWNS the due dose —
            // that is what makes a misread impossible.
            val type = sessions.firstOrNull { it.id == nextDose.sessionId }?.type
                ?: session.type
            val total = ScheduleEngine.dropCountFor(type, settings)
            val overdue = ScheduleEngine.isOverdue(nextDose, nowMillis, settings)
            return HeroState.DueDrop(
                doseId = nextDose.id,
                // SEQUENCE number (logic space). Renderers map it through
                // ScheduleEngine.displayNumberFor before showing it (v2.2).
                dropNumber = nextDose.dropNumber,
                total = total,
                sessionType = type,
                isSpecial = nextDose.kind == DoseKind.SPECIAL,
                dueAtMillis = nextDose.dueAt,
                nextReminderAtMillis = nextDose.nextReminderAt(),
                isOverdue = overdue,
                overdueByMin = if (overdue) (nowMillis - nextDose.dueAt) / 60_000L else 0L,
                canRespond = ScheduleEngine.canConfirm(nextDose.dueAt, nowMillis),
            )
        }
        if (session != null) {
            // ACTIVE session but no pending dose: strict chaining inserts the
            // next drop in the same transaction as the YES, so this only
            // happens mid-commit. Never offer starts while anything is active.
            return HeroState.NoActiveSession(
                canStartMorning = false,
                canStartAfternoon = false,
                canStartEvening = false,
            )
        }
        // No ACTIVE session: an un-fired deferred auto start waits in the wings.
        for (type in listOf(SessionType.AFTERNOON, SessionType.EVENING, SessionType.MORNING)) {
            if (!deferred(type)) continue
            val row = representative(type, sessions)
            if (row == null || (row.status != SessionStatus.ACTIVE && row.status != SessionStatus.COMPLETED)) {
                return HeroState.DeferredStart(
                    waitingType = type,
                    blockingType = blockerType(type, sessions),
                    startsAtMillis = deferredStartAtOf(type),
                )
            }
        }
        return if (SessionType.values().all { doneOf(it) }) {
            HeroState.AllDone
        } else {
            HeroState.NoActiveSession(
                canStartMorning = !doneOf(SessionType.MORNING),
                canStartAfternoon = !doneOf(SessionType.AFTERNOON),
                canStartEvening = !doneOf(SessionType.EVENING),
            )
        }
    }

    /**
     * The three TODAY TIMELINE rows, always in Morning/Afternoon/Evening order.
     *
     * @param progressOf per-type progress for the representative session row
     *   (null when the type has no session row yet).
     * @param currentDropOf the 1-based number of the type's current pending
     *   drop (null when unknown) — used for "In progress — Drop 3 of 6".
     */
    fun timelineRows(
        sessions: List<DoseSession>,
        settings: DoseSettings,
        nowMillis: Long,
        deferred: (SessionType) -> Boolean,
        progressOf: (SessionType) -> Progress?,
        currentDropOf: (SessionType) -> Int?,
        deferredStartAtOf: (SessionType) -> Long? = { null },
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        // v2.10: render-time only — true when the type's current pending
        // dose had its reminder call-deferred. Appends the "call ke baad"
        // note; changes nothing else.
        callDeferredOf: (SessionType) -> Boolean = { false },
    ): List<TimelineRow> = SessionType.values().map { type ->
        val row = representative(type, sessions)
        val p = progressOf(type)
        val done = row?.status == SessionStatus.COMPLETED ||
            (p != null && p.total > 0 && p.taken >= p.total)
        val (status, detail) = when {
            row?.status == SessionStatus.ACTIVE -> {
                val cur = currentDropOf(type)
                val d = when {
                    cur != null && p != null && p.total > 0 ->
                        // v2.2: show the DISPLAY number (afternoon/evening
                        // sequence 6 renders as 7 — "6" is always special).
                        "In progress — Drop ${ScheduleEngine.displayNumberFor(type, cur)} of ${p.total}"
                    p != null && p.total > 0 ->
                        "In progress — ${p.taken} of ${p.total} taken"
                    else -> "In progress"
                } + if (callDeferredOf(type)) " — 📞 Call ke baad" else ""
                TimelineStatus.IN_PROGRESS to d
            }
            done -> TimelineStatus.DONE to
                if (p != null && p.total > 0) "✓ Done — ${p.taken}/${p.total} taken"
                else "✓ Done"
            deferred(type) -> {
                // The deferral is NAMED: the user sees exactly what it waits for.
                // v2.3: once the blocker has cleared, the planned start
                // (clear + 30 min) is known and shown.
                val blocker = blockerType(type, sessions)
                val startAt = deferredStartAtOf(type)
                val d = when {
                    startAt != null && blocker != null ->
                        "Waiting — starts ${formatEpochTime(startAt, zone)} " +
                            "(30 min after ${sessionName(blocker)} finishes)"
                    startAt != null ->
                        "Waiting — starts ${formatEpochTime(startAt, zone)}"
                    blocker != null ->
                        "Waiting — starts after ${sessionName(blocker)} finishes"
                    else ->
                        "Waiting — starts when the current session finishes"
                }
                TimelineStatus.WAITING_DEFERRED to d
            }
            row != null -> TimelineStatus.NOT_STARTED to
                if (p != null && p.total > 0 && p.taken > 0) {
                    "Stopped — ${p.taken} of ${p.total} taken, can restart"
                } else {
                    "Not started"
                }
            type == SessionType.MORNING || !autoStartEnabled(type, settings) ->
                TimelineStatus.NOT_STARTED to "Not started"
            else -> TimelineStatus.UPCOMING to
                "Upcoming — starts ${formatMinutesOfDay(autoStartMinutes(type, settings))}"
        }
        TimelineRow(type = type, status = status, detail = detail)
    }

    /**
     * The "what happens next" line under the hero card. Plain words, no
     * guesswork: the next drop's time is only known once the current drop
     * is confirmed (strict chaining), so the line says exactly that.
     */
    fun nextUpLine(
        hero: HeroState,
        settings: DoseSettings,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String = when (hero) {
        is HeroState.DueDrop -> {
            val intervalMin = settings.doseIntervalMillis / 60_000L
            if (hero.dropNumber >= hero.total) {
                "Last drop of this session — after YES the session completes"
            } else {
                // v2.2: hero.dropNumber stays the SEQUENCE number; the line
                // renders the DISPLAY number of the next drop (afternoon
                // sequence 5 is followed by "Drop 7", never "Drop 6").
                "Next: Drop ${ScheduleEngine.displayNumberFor(hero.sessionType, hero.dropNumber + 1)} of ${hero.total} — " +
                    "due $intervalMin min after you tap YES"
            }
        }
        is HeroState.NoActiveSession ->
            if (hero.canStartMorning || hero.canStartAfternoon || hero.canStartEvening) {
                "Tap a Start button below to begin the next session"
            } else {
                "Finishing up — the chain is completing"
            }
        is HeroState.DeferredStart -> {
            val waiting = sessionName(hero.waitingType)
            val blocking = hero.blockingType?.let { sessionName(it) }
            val startAt = hero.startsAtMillis
            when {
                // v2.3: the blocker has cleared — the start time is known
                // (clear + 30 min) and is stated exactly.
                startAt != null && blocking != null ->
                    "$waiting starts automatically at ${formatEpochTime(startAt, zone)} " +
                        "— 30 min after $blocking finishes"
                startAt != null ->
                    "$waiting starts automatically at ${formatEpochTime(startAt, zone)}"
                blocking != null ->
                    "$waiting starts automatically after $blocking finishes"
                else ->
                    "$waiting starts automatically when the current session finishes"
            }
        }
        HeroState.AllDone -> "All sessions complete for today ✓"
    }

    /**
     * Representative row for a type: ACTIVE first (the 17:00 lesson — the
     * ACTIVE row is the truth), then COMPLETED, then the latest of the rest.
     * CONFLICT rows are failed start attempts with no plan behind them.
     */
    private fun representative(
        type: SessionType,
        sessions: List<DoseSession>,
    ): DoseSession? =
        sessions.firstOrNull { it.type == type && it.status == SessionStatus.ACTIVE }
            ?: sessions.firstOrNull { it.type == type && it.status == SessionStatus.COMPLETED }
            ?: sessions.filter { it.type == type && it.status != SessionStatus.CONFLICT }
                .maxByOrNull { it.startedAt }

    /** The session most likely blocking a deferred start: the latest-started other row. */
    private fun blockerType(waitingType: SessionType, sessions: List<DoseSession>): SessionType? =
        sessions.filter { it.type != waitingType && it.status != SessionStatus.CONFLICT }
            .maxByOrNull { it.startedAt }?.type

    private fun sessionName(type: SessionType): String = when (type) {
        SessionType.MORNING -> "Morning"
        SessionType.AFTERNOON -> "Afternoon"
        SessionType.EVENING -> "Evening"
    }

    private fun autoStartEnabled(type: SessionType, settings: DoseSettings): Boolean = when (type) {
        SessionType.MORNING -> false
        SessionType.AFTERNOON -> settings.autoStartAfternoon
        SessionType.EVENING -> settings.autoStartEvening
    }

    private fun autoStartMinutes(type: SessionType, settings: DoseSettings): Int = when (type) {
        SessionType.MORNING -> 0
        SessionType.AFTERNOON -> settings.afternoonStartMinutesOfDay
        SessionType.EVENING -> settings.eveningStartMinutesOfDay
    }

    private fun formatMinutesOfDay(minutes: Int): String {
        val t = LocalTime.MIN.plusMinutes(minutes.coerceIn(0, 24 * 60 - 1).toLong())
        return t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    }

    /** v2.3: "6:10 PM" rendering of an epoch-millis instant in [zone]. */
    private fun formatEpochTime(epochMillis: Long, zone: java.time.ZoneId): String =
        java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), zone)
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
}
