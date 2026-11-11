package com.moon.newmedicine.repo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.moon.newmedicine.alarm.AlarmScheduler
import com.moon.newmedicine.alarm.WakeUsage
import com.moon.newmedicine.core.BackupJson
import com.moon.newmedicine.core.ConfirmResult
import com.moon.newmedicine.core.DeclineResult
import com.moon.newmedicine.core.Dose
import com.moon.newmedicine.core.DoseKind
import com.moon.newmedicine.core.DoseSession
import com.moon.newmedicine.core.DoseSettings
import com.moon.newmedicine.core.DoseStatus
import com.moon.newmedicine.core.Progress
import com.moon.newmedicine.core.ScheduleEngine
import com.moon.newmedicine.core.SessionPlan
import com.moon.newmedicine.core.SessionStatus
import com.moon.newmedicine.core.SessionType
import com.moon.newmedicine.core.SkipResult
import com.moon.newmedicine.core.SnoozeResult
import com.moon.newmedicine.core.WakeCheckOutcome
import com.moon.newmedicine.core.WakeDiagnostics
import com.moon.newmedicine.data.DropsDatabase
import com.moon.newmedicine.notify.NotificationHelper
import java.time.ZoneId

/**
 * The single place where [ScheduleEngine] meets the database and the alarms.
 *
 * Every method is synchronous blocking I/O — the UI layer runs them off the
 * main thread, and the alarm receivers run them on their own background
 * threads. All multi-row mutations run inside one SQLite transaction, and
 * alarms are only (re)scheduled after the transaction commits, so the
 * database is always the source of truth.
 */
class DropsRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    val db: DropsDatabase = DropsDatabase(context.applicationContext)
    val alarms: AlarmScheduler = AlarmScheduler(context.applicationContext)
    val notifications: NotificationHelper = NotificationHelper(context.applicationContext)

    companion object {
        const val KEY_LAST_DAY = "lastDayKey"
        const val KEY_WAKE_LAST_PROMPT_AT = "wakeLastPromptAt"
        const val KEY_WAKE_FALLBACK_DAY = "wakeFallbackPromptedDay"
        const val KEY_WAKE_LAST_OUTCOME = "wakeLastOutcome"
        const val KEY_WAKE_LAST_CHECK_AT = "wakeLastCheckAt"
        /** Fixed delay for the wake notification's "15 min baad" snooze. */
        const val WAKE_SNOOZE_MILLIS = 15L * 60 * 1000
        /** One-time repair flag: pre-v1.5 duplicate-row corruption has been cleaned. */
        const val KEY_REPAIR_V3 = "repair_v3_done"
        /**
         * One-time migration flag: the default special-drop position moved
         * from 8 to 6 (v1.8). Saved value 8 (the old default) is migrated to
         * 6; an explicitly chosen value is never touched.
         */
        const val KEY_SPECIAL_POS_V18 = "special_pos_v18_done"
        /**
         * One-time migration flag: the user's corrected schedule is morning
         * 7 / afternoon 6 / evening 6 (was 8 / 7 / 7, v1.9). Saved old
         * defaults migrate; explicitly chosen counts are never touched.
         */
        const val KEY_DROP_COUNT_V19 = "drop_count_v19_done"
        /**
         * v2.1: the user's chosen reminder-sound URI (Settings → "Reminder
         * sound"). Absent / empty = the bundled in-APK alarm tone. Stored
         * as a raw setting so it is independent of the DoseSettings backup
         * schema.
         */
        const val KEY_REMINDER_SOUND = "reminderSoundUri"
    }

    /**
     * v1.10: one-time-migration failure reporter. The migration's flag is
     * NOT set on failure, so the next launch retries it.
     */
    private fun logMigrationFailure(tag: String, t: Throwable) {
        Log.w("DropsRepo", "$tag failed; will retry on next launch", t)
    }

    sealed interface StartResult {
        data class Started(val session: DoseSession) : StartResult
        data class AlreadyActive(val session: DoseSession) : StartResult
        data class Conflict(val reason: String) : StartResult
        /**
         * v1.13: the auto start was deferred because another session is still
         * ACTIVE (single-active-session invariant). The repo already recorded
         * a deferred-start flag, cancelled this session's start alarm and
         * posted the deferred notification; the session will start
         * automatically once the blocking session finishes.
         */
        data class Deferred(val type: SessionType, val blocking: DoseSession) : StartResult
    }

    sealed interface CancelResult {
        /** Session stopped: [confirmedKept] confirmed drops untouched, [pendingCancelled] pending doses cancelled. */
        data class Cancelled(val confirmedKept: Int, val pendingCancelled: Int) : CancelResult
        /** The session was not ACTIVE (already cancelled / completed / conflict) — nothing changed. */
        data class NothingToDo(val session: DoseSession) : CancelResult
    }

    // ------------------------------------------------------------- settings

    fun getSettings(): DoseSettings {
        val raw = readAllSettings()
        val d = DoseSettings()
        fun lng(k: String, cur: Long): Long = raw[k]?.toLongOrNull() ?: cur
        fun int(k: String, cur: Int): Int = raw[k]?.toIntOrNull() ?: cur
        fun bool(k: String, cur: Boolean): Boolean = raw[k]?.toBooleanStrictOrNull() ?: cur
        return d.copy(
            // New Medicine: sanitized at read — the gap is a preset
            // (5/7/10/15 min); anything off-preset falls back to 10 min.
            doseIntervalMillis = ScheduleEngine.sanitizeDoseInterval(
                lng("doseIntervalMillis", d.doseIntervalMillis)
            ),
            retryIntervalMillis = lng("retryIntervalMillis", d.retryIntervalMillis),
            cutoffMinutesOfDay = int("cutoffMinutesOfDay", d.cutoffMinutesOfDay),
            // New Medicine: the regimen is fixed — 4 drops in EVERY
            // session. Forced here so no saved or imported value (e.g. a
            // backup from the old Drops Reminder app) can ever change it.
            morningDropCount = 4,
            afternoonDropCount = 4,
            eveningDropCount = 4,
            // New Medicine: NO special drop, ever — forced false here so
            // no saved or imported value can re-enable one.
            specialDropEnabled = false,
            specialDropPosition = int("specialDropPosition", d.specialDropPosition),
            afternoonStartMinutesOfDay = int("afternoonStartMinutesOfDay", d.afternoonStartMinutesOfDay),
            eveningStartMinutesOfDay = int("eveningStartMinutesOfDay", d.eveningStartMinutesOfDay),
            autoStartAfternoon = bool("autoStartAfternoon", d.autoStartAfternoon),
            autoStartEvening = bool("autoStartEvening", d.autoStartEvening),
            overdueGraceMillis = lng("overdueGraceMillis", d.overdueGraceMillis),
            soundEnabled = bool("soundEnabled", d.soundEnabled),
            vibrationEnabled = bool("vibrationEnabled", d.vibrationEnabled),
            wakeWindowStartMinutesOfDay = int("wakeWindowStartMinutesOfDay", d.wakeWindowStartMinutesOfDay),
            wakeWindowEndMinutesOfDay = int("wakeWindowEndMinutesOfDay", d.wakeWindowEndMinutesOfDay),
            wakeCheckIntervalMinutes = int("wakeCheckIntervalMinutes", d.wakeCheckIntervalMinutes),
            nagEnabled = bool("nagEnabled", d.nagEnabled),
            nagIntervalMillis = lng("nagIntervalMillis", d.nagIntervalMillis),
            // v2.10: sanitized at read — the KV table can hold any stale
            // value; an off-preset gap falls back to the 1-minute default.
            callDeferGapMillis = ScheduleEngine.sanitizeCallDeferGap(
                lng("callDeferGapMillis", d.callDeferGapMillis)
            ),
        )
    }

    fun saveSettings(s: DoseSettings) {
        db.inTransaction { w ->
            for ((k, v) in settingsToMap(s)) upsertSetting(w, k, v)
        }
    }

    /** Raw internal key/value access (lastDayKey, channelConfigHash, ...). */
    fun getRawSetting(key: String): String? {
        db.readableDatabase.rawQuery(
            "SELECT value FROM settings WHERE key = ?", arrayOf(key)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun setRawSetting(key: String, value: String) {
        db.inTransaction { w -> upsertSetting(w, key, value) }
    }

    /**
     * v2.10: drops every call-deferred flag. Called from reconcile
     * (boot / app start): the deferred re-fire alarms do not survive a
     * reboot, so a persisted flag would wrongly keep the "call ke baad"
     * note on the timeline. The next fire re-establishes the truth
     * (deferring again if the call is still on). The ALARMS are left
     * alone — only reboot kills them, and app-start reconcile must not
     * cancel a live deferred re-fire.
     */
    fun clearCallDeferFlags() {
        db.inTransaction { w ->
            w.delete("settings", "key LIKE 'call_deferred_%'", null)
        }
    }

    private fun settingsToMap(s: DoseSettings): Map<String, String> = mapOf(
        "doseIntervalMillis" to s.doseIntervalMillis.toString(),
        "retryIntervalMillis" to s.retryIntervalMillis.toString(),
        "cutoffMinutesOfDay" to s.cutoffMinutesOfDay.toString(),
        "morningDropCount" to s.morningDropCount.toString(),
        "afternoonDropCount" to s.afternoonDropCount.toString(),
        "eveningDropCount" to s.eveningDropCount.toString(),
        "specialDropEnabled" to s.specialDropEnabled.toString(),
        "specialDropPosition" to s.specialDropPosition.toString(),
        "afternoonStartMinutesOfDay" to s.afternoonStartMinutesOfDay.toString(),
        "eveningStartMinutesOfDay" to s.eveningStartMinutesOfDay.toString(),
        "autoStartAfternoon" to s.autoStartAfternoon.toString(),
        "autoStartEvening" to s.autoStartEvening.toString(),
        "overdueGraceMillis" to s.overdueGraceMillis.toString(),
        "soundEnabled" to s.soundEnabled.toString(),
        "vibrationEnabled" to s.vibrationEnabled.toString(),
        "wakeWindowStartMinutesOfDay" to s.wakeWindowStartMinutesOfDay.toString(),
        "wakeWindowEndMinutesOfDay" to s.wakeWindowEndMinutesOfDay.toString(),
        "wakeCheckIntervalMinutes" to s.wakeCheckIntervalMinutes.toString(),
        "nagEnabled" to s.nagEnabled.toString(),
        "nagIntervalMillis" to s.nagIntervalMillis.toString(),
        "callDeferGapMillis" to s.callDeferGapMillis.toString(),
    )

    private fun readAllSettings(): Map<String, String> {
        val out = HashMap<String, String>()
        db.readableDatabase.rawQuery("SELECT key, value FROM settings", null).use { c ->
            val ki = c.getColumnIndexOrThrow("key")
            val vi = c.getColumnIndexOrThrow("value")
            while (c.moveToNext()) out[c.getString(ki)] = c.getString(vi)
        }
        return out
    }

    private fun upsertSetting(w: SQLiteDatabase, key: String, value: String) {
        val cv = ContentValues(2).apply {
            put("key", key)
            put("value", value)
        }
        w.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ----------------------------------------------------------------- time

    fun todayKey(now: Long = System.currentTimeMillis()): String =
        ScheduleEngine.dayKey(now, ZoneId.systemDefault())

    // ------------------------------------------------- session start (plan)

    fun startMorningSession(now: Long): StartResult {
        val dayKey = todayKey(now)
        val specialTaken = ScheduleEngine.isSpecialTakenToday(getDosesForDay(dayKey), dayKey)
        return startSession(SessionType.MORNING, now, specialTaken, isAuto = false)
    }

    fun autoStartSession(type: SessionType, now: Long): StartResult {
        require(type != SessionType.MORNING) { "Morning sessions are started manually by the user" }
        // v2.3: when this call IS the firing of a deferred start (the flag
        // is in its ready phase and the ready time has been reached), the
        // deferral is consumed and the evening cutoff fit-check is bypassed
        // (Change 3a: a session that was made late by waiting must run in
        // full, even past the cutoff). A fresh auto start finds no ready
        // flag and keeps the exact v2.2 behavior, cutoff included.
        val dayKey = todayKey(now)
        val key = ScheduleEngine.deferredStartKey(type, dayKey)
        val readyAt = ScheduleEngine.deferredReadyAtFromFlag(getRawSetting(key))
        val viaDeferral = readyAt != null && now >= readyAt
        if (viaDeferral) {
            deleteRawSetting(key)
            notifications.cancelDeferredStartNotification()
        }
        // The special drop only ever lives in MORNING sessions, so for
        // AFTERNOON/EVENING it is effectively already taken.
        return startSession(
            type, now, specialAlreadyTaken = true, isAuto = true, ignoreCutoff = viaDeferral
        )
    }

    /**
     * v2.3: the planned start of a deferred session of [type] whose blocker
     * has already cleared (flag in the ready phase), or null when the
     * session is not deferred or still waiting for its blocker.
     */
    fun deferredReadyAt(type: SessionType, dayKey: String): Long? =
        ScheduleEngine.deferredReadyAtFromFlag(
            getRawSetting(ScheduleEngine.deferredStartKey(type, dayKey))
        )

    /**
     * v1.13: manual start for AFTERNOON/EVENING (the UI exposes Start buttons
     * for them, e.g. to start the evening session today after it was stopped
     * by hand). Goes through the SAME single-active-session guard as every
     * other start: if another session is ACTIVE the result is
     * [StartResult.AlreadyActive] — an overlap can never be created from the
     * UI. The evening-cutoff CONFLICT check still applies (a too-late manual
     * start records CONFLICT exactly like the auto path).
     *
     * A CANCELLED row for [type] never blocks: only ACTIVE rows do.
     */
    fun manualStartSession(type: SessionType, now: Long): StartResult {
        require(type != SessionType.MORNING) { "Use startMorningSession for morning" }
        val result = startSession(type, now, specialAlreadyTaken = true, isAuto = false)
        if (result is StartResult.Started) {
            // A manual start supersedes any pending deferral for this
            // type/day — drop the stale flag and its notification.
            deleteRawSetting(ScheduleEngine.deferredStartKey(type, todayKey(now)))
            notifications.cancelDeferredStartNotification()
        }
        return result
    }

    private class SpecialConflictException : RuntimeException()

    private fun startSession(
        type: SessionType,
        now: Long,
        specialAlreadyTaken: Boolean,
        isAuto: Boolean,
        ignoreCutoff: Boolean = false,
    ): StartResult {
        val zone = ZoneId.systemDefault()
        val dayKey = ScheduleEngine.dayKey(now, zone)
        // v1.13 — single-active-session invariant: a session must NEVER
        // start while another session is still ACTIVE. The next session
        // starts only after the current one finishes (COMPLETED/CANCELLED).
        val active = findAnyActiveSession(dayKey)
        if (active != null) {
            val blocking = ScheduleEngine.blockingSession(active, type)
            if (blocking == null) return StartResult.AlreadyActive(active)
            return if (isAuto) deferSessionStart(type, dayKey, now, blocking)
            else StartResult.AlreadyActive(blocking)
        }

        val settings = getSettings()
        // v2.3: ignoreCutoff is true only on the deferred-fire path (see
        // autoStartSession) — a deferred evening runs its full plan even
        // past the cutoff; every fresh start keeps the CONFLICT check.
        val plan = ScheduleEngine.planSession(
            type, dayKey, now, settings, specialAlreadyTaken, zone, ignoreCutoff
        )
        if (plan is SessionPlan.Conflict) {
            // Record the attempt so history shows why nothing was scheduled.
            insertSessionRow(db.writableDatabase, type, dayKey, now, SessionStatus.CONFLICT)
            return StartResult.Conflict(plan.reason)
        }
        val ok = plan as SessionPlan.Ok

        val sessionId = try {
            db.inTransaction { w ->
                val sid = try {
                    insertSessionRow(w, type, dayKey, now, SessionStatus.ACTIVE)
                } catch (e: SQLiteConstraintException) {
                    // v2.0 (A3): lost the double-start race — the partial
                    // unique index on (dayKey WHERE ACTIVE) fired because
                    // another thread's start committed between our
                    // findAnyActiveSession check and this insert. Nothing was
                    // written, so the transaction rolls back cleanly; report
                    // the live ACTIVE session instead of crashing. (The null
                    // branch is defensive — the index fired, so a row exists.)
                    return StartResult.AlreadyActive(
                        findAnyActiveSession(dayKey)
                            ?: return StartResult.Conflict("another session is already active")
                    )
                }
                try {
                    // STRICT CHAINING (v1.5): persist ONLY drop 1. The full
                    // plan above is still needed for validation (counts, the
                    // evening-cutoff check, the special-drop rule), but drops
                    // 2..N are created lazily — each YES inserts exactly one
                    // next row (dueAt = confirmedAt + interval). A drop can
                    // never exist before the previous one is taken, and
                    // duplicate dropNumbers are structurally impossible.
                    // (Pre-v1.5 this loop bulk-inserted the whole plan AND
                    // confirmDose inserted another row per YES — the
                    // duplication bug fixed by ensureDataRepaired().)
                    val first = ok.doses.first()
                    insertDoseRow(w, sid, first.dropNumber, first.kind, dayKey, first.dueAt)
                } catch (e: SQLiteConstraintException) {
                    // Race with another start: the partial unique index on the
                    // once-daily special drop fired. Roll everything back.
                    throw SpecialConflictException()
                }
                sid
            }
        } catch (e: SpecialConflictException) {
            return StartResult.Conflict("special already recorded today")
        }

        val session = getSession(sessionId) ?: error("session vanished right after insert")
        // Only after the commit: arm the alarm for the first (now-due) dose.
        getSessionDoses(sessionId).firstOrNull()?.let { alarms.scheduleDose(it) }
        return StartResult.Started(session)
    }

    /**
     * v1.13: the newest ACTIVE session of [dayKey], of any type. The
     * single-active-session invariant is enforced against this row.
     */
    private fun findAnyActiveSession(dayKey: String): DoseSession? {
        db.readableDatabase.rawQuery(
            "SELECT * FROM sessions WHERE dayKey = ? AND status = ? ORDER BY id DESC LIMIT 1",
            arrayOf(dayKey, SessionStatus.ACTIVE.name)
        ).use { c ->
            return if (c.moveToFirst()) db.sessionFromCursor(c) else null
        }
    }

    /**
     * v1.13: records a deferred auto start. The session's start alarm is
     * dropped (it already fired); a same-day flag remembers the intent and
     * [maybeStartDeferredSessions] fires it once nothing is ACTIVE anymore.
     * The user is told plainly what happened.
     *
     * v2.3: the flag is (re-)written in its WAITING phase
     * ([ScheduleEngine.deferredFlagValue] of null → "1") — if this start
     * was itself the firing of a ready deferral that got re-blocked, the
     * stale ready time is discarded and the 30-minute delay restarts from
     * the NEXT blocker-clear.
     */
    private fun deferSessionStart(
        type: SessionType,
        dayKey: String,
        now: Long,
        blocking: DoseSession,
    ): StartResult {
        setRawSetting(
            ScheduleEngine.deferredStartKey(type, dayKey),
            ScheduleEngine.deferredFlagValue(null),
        )
        alarms.cancelSessionStart(type, dayKey)
        notifications.showDeferredStartNotification(type, blocking.type)
        Log.i("DropsRepo", "deferred $type start (blocked by ${blocking.type} session ${blocking.id})")
        return StartResult.Deferred(type, blocking)
    }

    /**
     * Removes a raw settings key entirely (used for one-shot flags).
     * v2.10: made public — the receiver clears the call-deferred flag
     * when the dose actually rings.
     */
    fun deleteRawSetting(key: String) {
        db.inTransaction { w -> w.delete("settings", "key = ?", arrayOf(key)) }
    }

    /**
     * v1.13: fires deferred auto session starts. Called post-commit from
     * [confirmDose] (session COMPLETED), [skipDose] (session COMPLETED) and
     * [cancelSession] (CANCELLED), and from [reconcileAlarms] (process-death
     * edge).
     *
     * v2.3 TWO-PHASE (user order 2026-10-22): a deferred session no longer
     * starts the instant its blocker clears — it starts
     * [ScheduleEngine.DEFERRED_START_DELAY_MILLIS] (30 min) AFTER the clear.
     *  - WAITING phase (flag "1"): while another session is ACTIVE, nothing
     *    happens. The first call that finds nothing ACTIVE is the clear
     *    moment: the flag flips to its READY phase ("ready:<readyAt>"), a
     *    session-start alarm is armed for readyAt (floored at the planned
     *    start time, so a settings change can never pull it earlier than
     *    the user's own schedule), and the deferred notification is updated
     *    to state the planned start time.
     *  - READY phase: before readyAt the alarm is simply kept armed
     *    (reconcile safety net); at/after readyAt the start fires via
     *    [autoStartSession], which consumes the flag and bypasses the
     *    evening cutoff fit-check (deferred starts run in full — Change 3a).
     * If a NEW session became ACTIVE in the meantime (re-block), the start
     * attempt defers again through the normal guard and the flag returns
     * to the WAITING phase.
     */
    fun maybeStartDeferredSessions(now: Long) {
        val zone = ZoneId.systemDefault()
        val dayKey = ScheduleEngine.dayKey(now, zone)
        val settings = getSettings()
        for (type in listOf(SessionType.AFTERNOON, SessionType.EVENING)) {
            val key = ScheduleEngine.deferredStartKey(type, dayKey)
            val raw = getRawSetting(key) ?: continue
            // v2.0 (A14): a deferred start must not fire when its auto-start
            // was disabled after the deferral — drop the stale intent.
            val enabled = if (type == SessionType.AFTERNOON) settings.autoStartAfternoon
            else settings.autoStartEvening
            if (!enabled) {
                deleteRawSetting(key)
                alarms.cancelSessionStart(type, dayKey)
                val other = if (type == SessionType.AFTERNOON) SessionType.EVENING else SessionType.AFTERNOON
                if (getRawSetting(ScheduleEngine.deferredStartKey(other, dayKey)) == null) {
                    notifications.cancelDeferredStartNotification()
                }
                continue
            }
            val readyAt = ScheduleEngine.deferredReadyAtFromFlag(raw)
            if (readyAt == null) {
                // WAITING phase: the blocker must clear first.
                if (findAnyActiveSession(dayKey) != null) continue
                // The blocker just cleared (this call runs synchronously
                // after the completing commit, so `now` IS the clear time;
                // on the process-death reconcile path it is the first
                // moment the clear is observed — the delay runs from here).
                val planned = ScheduleEngine.plannedSessionStartMillis(type, dayKey, settings, zone)
                val ready = maxOf(ScheduleEngine.deferredReadyAt(now), planned)
                setRawSetting(key, ScheduleEngine.deferredFlagValue(ready))
                alarms.scheduleSessionStart(type, dayKey, ready)
                notifications.showDeferredStartNotification(
                    type, lastFinishedBlockerType(dayKey, type), ready
                )
                Log.i("DropsRepo", "deferred $type start planned at $ready (blocker cleared)")
                continue
            }
            // READY phase.
            if (findAnyActiveSession(dayKey) != null) {
                // Re-blocked before the ready time: the start attempt below
                // would just defer again — keep waiting quietly instead and
                // let the guard re-run when this blocker clears.
                continue
            }
            if (now < readyAt) {
                // Not yet: keep the fire alarm armed (it is the wake-up
                // for readyAt; reconcile may have rebuilt the alarm table).
                alarms.scheduleSessionStart(type, dayKey, readyAt)
                continue
            }
            // Fire time: autoStartSession consumes the flag + notification
            // and bypasses the cutoff fit-check for this deferred start.
            when (val r = autoStartSession(type, now)) {
                is StartResult.Conflict -> notifications.showConflictNotification(r.reason)
                is StartResult.Deferred -> {
                    // Re-blocked between the check and the start (the
                    // guard re-deferred and reset the flag to WAITING) —
                    // the intent is already recorded again; nothing to do.
                    Unit
                }
                is StartResult.Started, is StartResult.AlreadyActive -> { /* done */ }
            }
        }
    }

    /**
     * v2.3: the session type that most recently finished (COMPLETED or
     * CANCELLED) today, other than [waitingType] — the blocker whose clear
     * unblocked a deferred start. Used only for the deferred notification's
     * wording; null when nothing suitable exists.
     */
    private fun lastFinishedBlockerType(dayKey: String, waitingType: SessionType): SessionType? =
        getTodaySessions(dayKey)
            .filter {
                it.type != waitingType &&
                    (it.status == SessionStatus.COMPLETED || it.status == SessionStatus.CANCELLED)
            }
            .maxByOrNull { it.startedAt }?.type

    // ------------------------------------------------------------ stop session

    /**
     * Stops an ACTIVE session ("Session rok dein").
     *
     * In ONE transaction: the session row becomes CANCELLED and every still-
     * PENDING dose becomes CANCELLED (its retry arm cleared). CONFIRMED doses
     * are NEVER touched — they stay in history with their timestamps. After
     * the commit, each cancelled dose's alarm is dropped.
     *
     * Idempotent: cancelling a non-ACTIVE session is a no-op
     * ([CancelResult.NothingToDo]). A cancelled special drop is not CONFIRMED,
     * so [ScheduleEngine.isSpecialTakenToday] stays false and a later session
     * may still include the special drop.
     */
    fun cancelSession(sessionId: Long, nowMillis: Long): CancelResult {
        val cancelledDoseIds = ArrayList<Long>()
        val result = db.inTransaction { w ->
            val session = querySessionRow(w, sessionId)
                ?: throw IllegalArgumentException("unknown session")
            if (session.status != SessionStatus.ACTIVE) {
                return@inTransaction CancelResult.NothingToDo(session)
            }
            var confirmedKept = 0
            var pendingCancelled = 0
            w.rawQuery(
                "SELECT * FROM doses WHERE sessionId = ? ORDER BY id ASC",
                arrayOf(sessionId.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val d = db.doseFromCursor(c)
                    when (d.status) {
                        DoseStatus.PENDING -> {
                            writeDoseMutable(
                                w, d.copy(status = DoseStatus.CANCELLED, nextRetryAt = null)
                            )
                            cancelledDoseIds.add(d.id)
                            pendingCancelled++
                        }
                        DoseStatus.CONFIRMED -> confirmedKept++
                        else -> { /* MISSED stays as-is */ }
                    }
                }
            }
            updateSessionStatus(w, sessionId, SessionStatus.CANCELLED)
            CancelResult.Cancelled(confirmedKept, pendingCancelled)
        }
        // Only after the commit: these doses need no alarms and no
        // reminders anymore (A7: a stopped session's notification would
        // otherwise linger in the tray with working YES/NO buttons).
        for (id in cancelledDoseIds) {
            alarms.cancelDose(id)
            // v2.4: nor a snooze watchdog.
            alarms.cancelSnoozeWatchdog(id)
            // v2.10: nor a call-deferred re-fire (and its timeline note).
            alarms.cancelCallDeferred(id)
            deleteRawSetting(ScheduleEngine.callDeferKey(id))
            notifications.cancelDoseNotification(id)
        }
        // If the morning session was stopped, let the wake chain re-arm so a
        // same-day re-prompt can still fire inside the wake window (tomorrow
        // works via the dayKey in any case).
        if (result is CancelResult.Cancelled) {
            val session = getSession(sessionId)
            if (session?.type == SessionType.MORNING) rearmWakeChain(nowMillis)
            // v1.13: a stopped session may unblock a deferred auto start.
            maybeStartDeferredSessions(nowMillis)
        }
        return result
    }

    // ------------------------------------------------------------ confirm/NO

    fun confirmDose(doseId: Long, now: Long): ConfirmResult {
        val settings = getSettings()
        var newDoseId: Long? = null
        val result = db.inTransaction { w ->
            val dose = queryDoseRow(w, doseId) ?: throw IllegalArgumentException("unknown dose")
            if (dose.status != DoseStatus.PENDING) {
                // Idempotent: already handled (double tap / YES on two
                // surfaces) — the engine returns unchanged=true, no writes.
                return@inTransaction ScheduleEngine.confirmDose(dose, now, settings, isLastDrop = false)
            }
            if (!ScheduleEngine.canConfirm(dose.dueAt, now)) {
                // Accidental early tap: refuse with ZERO state change — no
                // status change, no timestamp, no next-dose scheduling, no
                // alarm changes. The engine is never invoked.
                return@inTransaction ConfirmResult(
                    dose = dose, nextDueAt = null, unchanged = true, notDueYet = true,
                )
            }
            val session = querySessionRow(w, dose.sessionId)
                ?: throw IllegalArgumentException("unknown session")
            val isLastDrop = dose.dropNumber >= ScheduleEngine.dropCountFor(session.type, settings)
            val res = ScheduleEngine.confirmDose(dose, now, settings, isLastDrop)
            writeDoseMutable(w, res.dose)
            if (res.nextDueAt != null) {
                val specialTaken =
                    ScheduleEngine.isSpecialTakenToday(queryDosesForDay(w, dose.dayKey), dose.dayKey)
                val kind = kindForDrop(session.type, dose.dropNumber + 1, settings, specialTaken)
                newDoseId = insertDoseRow(
                    w, dose.sessionId, dose.dropNumber + 1, kind, dose.dayKey, res.nextDueAt
                )
            }
            if (isLastDrop) updateSessionStatus(w, session.id, SessionStatus.COMPLETED)
            res
        }
        // After the commit: this dose needs no alarm anymore; the next drop
        // (if any) gets one. A refused early tap changed nothing, so no
        // alarm may be touched either.
        if (result.notDueYet) return result
        alarms.cancelDose(doseId)
        // v2.4: a confirmed dose needs no snooze watchdog either.
        alarms.cancelSnoozeWatchdog(doseId)
        // v2.10: nor a call-deferred re-fire (and its timeline note).
        alarms.cancelCallDeferred(doseId)
        deleteRawSetting(ScheduleEngine.callDeferKey(doseId))
        // A6: the repo now owns the reminder cleanup, so the in-app YES
        // (DoseFragment) clears the notification too — not just the
        // notification action. Kept next to alarms.cancelDose on purpose:
        // a refused early tap (notDueYet) still needs its reminder.
        notifications.cancelDoseNotification(doseId)
        newDoseId?.let { id -> getDose(id)?.let { alarms.scheduleDose(it) } }
        // v1.13: a completed session may unblock a deferred auto start.
        maybeStartDeferredSessions(now)
        return result
    }

    fun declineDose(doseId: Long, now: Long): DeclineResult {
        val settings = getSettings()
        val result = db.inTransaction { w ->
            val dose = queryDoseRow(w, doseId) ?: throw IllegalArgumentException("unknown dose")
            if (dose.status == DoseStatus.PENDING && !ScheduleEngine.canConfirm(dose.dueAt, now)) {
                // Accidental early tap: refuse with ZERO state change — no
                // retry-count bump, no retry re-arming, no alarm changes.
                return@inTransaction DeclineResult(dose, notDueYet = true)
            }
            // Throws IllegalArgumentException when not PENDING (unchanged
            // stale-dose behavior).
            val u = ScheduleEngine.declineDose(dose, now, settings)
            writeDoseMutable(w, u)
            // v2.3: a NO tap spends any snooze — the snooze (if one was
            // armed) has already fired and the NO-retry now owns this
            // dose's reminder slot. Drop the stale flag so nothing can
            // mistake the dose for still-snoozed. (Whichever of NO/snooze
            // is chosen LAST owns the slot; the other is superseded.)
            w.delete("settings", "key = ?", arrayOf(snoozeKey(doseId)))
            DeclineResult(u)
        }
        // Same alarm slot, new trigger time (FLAG_UPDATE_CURRENT overwrites).
        // A refused early tap keeps the existing alarm untouched.
        // A6: the repo now owns the "Still pending" refresh too, so the
        // in-app NO (DoseFragment) updates the notification — previously
        // only the notification action did, leaving a stale reminder.
        if (!result.notDueYet) {
            alarms.scheduleDose(result.dose)
            // v2.4: the NO-retry owns this dose's reminders now — a
            // pending snooze watchdog (armed before the snooze expired)
            // must not add an extra ring on top of the retry cadence.
            alarms.cancelSnoozeWatchdog(doseId)
            // v2.10: the NO-retry likewise supersedes a call-deferred
            // re-fire (and clears its timeline note).
            alarms.cancelCallDeferred(doseId)
            deleteRawSetting(ScheduleEngine.callDeferKey(doseId))
            notifications.updateAfterNo(result.dose, getSession(result.dose.sessionId))
        }
        return result
    }

    /**
     * v2.0: "Skip" for [doseId] at [now] — marks the drop MISSED and moves
     * the chain to the next drop (dueAt = skip time + doseInterval), or
     * completes the session when this was the last drop. All in one
     * transaction, mirroring [confirmDose]'s structure.
     *
     * - Unknown dose id → throws IllegalArgumentException (same stale
     *   handling as [declineDose]: the receiver converts it to a
     *   notification cancel).
     * - Non-PENDING dose → [SkipResult.unchanged] = true, zero writes.
     * - Tap before the dose is due (outside the 60s grace) →
     *   [SkipResult.notDueYet] = true, ZERO state change.
     */
    fun skipDose(doseId: Long, now: Long): SkipResult {
        val settings = getSettings()
        var newDoseId: Long? = null
        val result = db.inTransaction { w ->
            val dose = queryDoseRow(w, doseId) ?: throw IllegalArgumentException("unknown dose")
            if (dose.status != DoseStatus.PENDING) {
                // Idempotent: already handled (stale action, double tap) —
                // nothing is written.
                return@inTransaction SkipResult(dose, nextDueAt = null, unchanged = true)
            }
            if (!ScheduleEngine.canConfirm(dose.dueAt, now)) {
                // Accidental early tap: refuse with ZERO state change — no
                // status change, no timestamp, no next-dose scheduling, no
                // alarm changes.
                return@inTransaction SkipResult(
                    dose = dose, nextDueAt = null, unchanged = false, notDueYet = true,
                )
            }
            val session = querySessionRow(w, dose.sessionId)
                ?: throw IllegalArgumentException("unknown session")
            val isLastDrop = dose.dropNumber >= ScheduleEngine.dropCountFor(session.type, settings)
            val skipped = dose.copy(status = DoseStatus.MISSED)
            writeDoseMutable(w, skipped)
            var nextDueAt: Long? = null
            if (!isLastDrop) {
                val specialTaken =
                    ScheduleEngine.isSpecialTakenToday(queryDosesForDay(w, dose.dayKey), dose.dayKey)
                val kind = kindForDrop(session.type, dose.dropNumber + 1, settings, specialTaken)
                nextDueAt = now + settings.doseIntervalMillis
                newDoseId = insertDoseRow(
                    w, dose.sessionId, dose.dropNumber + 1, kind, dose.dayKey, nextDueAt
                )
            } else {
                updateSessionStatus(w, session.id, SessionStatus.COMPLETED)
            }
            SkipResult(skipped, nextDueAt, unchanged = false)
        }
        // A refused early tap changed nothing: no alarm or notification may
        // be touched. A stale tap on a non-pending dose only clears the
        // notification (its alarm slot — including any armed nag — is
        // dropped as well).
        if (result.notDueYet) return result
        notifications.cancelDoseNotification(doseId)
        if (result.unchanged) return result
        alarms.cancelDose(doseId)
        // v2.4: a skipped dose needs no snooze watchdog either.
        alarms.cancelSnoozeWatchdog(doseId)
        // v2.10: nor a call-deferred re-fire (and its timeline note).
        alarms.cancelCallDeferred(doseId)
        deleteRawSetting(ScheduleEngine.callDeferKey(doseId))
        newDoseId?.let { id -> getDose(id)?.let { alarms.scheduleDose(it) } }
        // v2.0: a completed session may unblock a deferred auto start.
        if (result.nextDueAt == null) maybeStartDeferredSessions(now)
        return result
    }

    /**
     * v2.3: KV key holding a dose's snooze-until timestamp (epoch millis as
     * a string). Follows the `nag_next_<doseId>` pattern — deliberately NOT
     * a doses-table column (no schema change).
     */
    private fun snoozeKey(doseId: Long): String = ScheduleEngine.snoozeKey(doseId)

    /** v2.3: when [doseId]'s snooze ends (epoch millis), or null if not snoozed. */
    fun getSnoozeUntil(doseId: Long): Long? =
        getRawSetting(snoozeKey(doseId))?.toLongOrNull()

    /**
     * v2.3: "Snooze" for [doseId] at [now] — push this drop's reminder out
     * by [durationMillis] (one of [ScheduleEngine.SNOOZE_DURATIONS_MILLIS])
     * WITHOUT taking, missing or otherwise resolving the drop:
     *
     * - Unknown dose id → throws IllegalArgumentException (same stale
     *   handling as [declineDose]).
     * - Non-PENDING dose → [SnoozeResult.unchanged] = true, zero writes.
     * - Tap before the dose is due (outside the 60s grace) →
     *   [SnoozeResult.notDueYet] = true, ZERO state change — same
     *   accidental-tap guard as YES/NO/Skip.
     * - Otherwise, in ONE transaction: the snooze flag
     *   `snooze_until_<doseId>` = now + duration is stored, the pending nag
     *   trigger is dropped (the snooze supersedes it), and any armed
     *   NO-retry is cleared (retryCount is kept as history) — whichever of
     *   NO/snooze is chosen LAST owns the dose's reminder slot.
     * After the commit the dose's exact alarm is re-armed for the snooze
     * expiry (same slot — overwrites, never duplicates) and the tray
     * reminder is replaced with the silent "Snoozed" notification. The
     * chain does NOT advance: the next drop still comes only after YES.
     * Repeat snoozing is allowed — each re-fire can be snoozed again.
     */
    fun snoozeDose(doseId: Long, durationMillis: Long, now: Long): SnoozeResult {
        require(durationMillis in ScheduleEngine.SNOOZE_DURATIONS_MILLIS) {
            "snooze duration must be one of the fixed presets"
        }
        val result = db.inTransaction { w ->
            val dose = queryDoseRow(w, doseId) ?: throw IllegalArgumentException("unknown dose")
            if (dose.status != DoseStatus.PENDING) {
                // Idempotent: already handled (stale action, double tap) —
                // nothing is written.
                return@inTransaction SnoozeResult(dose, snoozedUntil = null, unchanged = true)
            }
            if (!ScheduleEngine.canConfirm(dose.dueAt, now)) {
                // Accidental early tap: refuse with ZERO state change.
                return@inTransaction SnoozeResult(
                    dose = dose, snoozedUntil = null, unchanged = false, notDueYet = true,
                )
            }
            val until = now + durationMillis
            upsertSetting(w, snoozeKey(doseId), until.toString())
            // The snooze supersedes the pending nag trigger: during the
            // snooze window the dose is untouchable, and after expiry the
            // nag chain re-arms from the reminder fire itself.
            w.delete("settings", "key = ?", arrayOf(nagKey(doseId)))
            // v2.10: the snooze likewise supersedes a call-deferred
            // re-fire — the snooze alarm (same dose slot, armed
            // post-commit) owns this dose's next reminder now.
            w.delete("settings", "key = ?", arrayOf(ScheduleEngine.callDeferKey(doseId)))
            if (dose.nextRetryAt != null) {
                // A pending NO-retry is superseded too: the snooze alarm
                // (same slot, armed post-commit) replaces it.
                writeDoseMutable(w, dose.copy(nextRetryAt = null))
            }
            SnoozeResult(dose, snoozedUntil = until, unchanged = false)
        }
        // A refused early tap / stale tap changed nothing: no alarm or
        // notification may be touched.
        if (result.notDueYet || result.unchanged) return result
        val dose = getDose(doseId) ?: return result
        alarms.scheduleDoseAt(dose, result.snoozedUntil!!)
        // v2.4: arm the snooze watchdog on its own slot — the expiry's
        // independent second delivery chance (see
        // ScheduleEngine.SNOOZE_WATCHDOG_DELAY_MILLIS). Re-snoozing
        // overwrites it with the new window's watchdog, same slot.
        alarms.scheduleSnoozeWatchdog(dose, ScheduleEngine.snoozeWatchdogAt(result.snoozedUntil))
        // v2.10: the snooze alarm above supersedes any call-deferred
        // re-fire armed before the snooze was tapped.
        alarms.cancelCallDeferred(doseId)
        notifications.showSnoozed(dose, getSession(dose.sessionId), result.snoozedUntil)
        return result
    }

    // ------------------------------------------------- morning wake detection

    /**
     * True when a MORNING session row (started and not cancelled, not a failed
     * attempt) exists for [dayKey]. A CANCELLED session does not count, so the
     * wake chain may re-prompt after the user stops a session.
     */
    fun hasMorningSessionToday(dayKey: String): Boolean =
        getTodaySessions(dayKey).any {
            it.type == SessionType.MORNING &&
                (it.status == SessionStatus.ACTIVE || it.status == SessionStatus.COMPLETED)
        }

    /** True when [now] falls inside the configured morning wake window. */
    fun isWakeWindowActive(now: Long): Boolean =
        ScheduleEngine.isInWakeWindow(now, getSettings(), ZoneId.systemDefault())

    /**
     * One wake-detection evaluation, shared by the alarm chain tick, the
     * "15 min baad" snooze and the best-effort USER_PRESENT path.
     *
     * Pure decision logic lives in [ScheduleEngine.decideWakePrompt]
     * (unit-tested); this only wires it to usage stats, persisted prompt
     * state, notifications and the alarm chain. Because every branch re-reads
     * the database, a stale or duplicated tick can only re-evaluate current
     * state — never double-prompt (the notification id is stable, so a
     * re-prompt replaces in place).
     *
     * Every evaluation records its outcome + time so Settings → "Wake
     * status" can show what actually happened (the v1.2 "no notification
     * and no idea why" report).
     */
    fun runWakeCheck(now: Long, force: Boolean): WakeCheckOutcome {
        ensureDayRollover(now)
        val zone = ZoneId.systemDefault()
        val dayKey = ScheduleEngine.dayKey(now, zone)
        val settings = getSettings()

        if (hasMorningSessionToday(dayKey)) {
            // v2.5: retire today's chain but roll it forward to
            // tomorrow's window start — never leave it dead (see
            // stopWakeChainForToday).
            stopWakeChainForToday(now)
            notifications.cancelWakePrompt()
            return recordWakeCheck(WakeCheckOutcome.MORNING_ALREADY_STARTED, now)
        }

        // An explicit "15 min baad" request is honored even if it lands just
        // outside the window — the user asked to be reminded. (The pure
        // decideWakePrompt mirrors this: force bypasses the window check.)
        if (!ScheduleEngine.isInWakeWindow(now, settings, zone) && !force) {
            // Window ended (or degenerate settings): stop ticking; the next
            // check is scheduled for tomorrow's window start so the chain is
            // self-sustaining even if the process stays alive overnight.
            val next = ScheduleEngine.nextWakeCheckAt(now, settings, zone)
            alarms.scheduleWakeCheck(next, ScheduleEngine.dayKey(next, zone), force = false)
            notifications.cancelWakePrompt()
            return recordWakeCheck(WakeCheckOutcome.WINDOW_ENDED, now)
        }

        val window = ScheduleEngine.wakeWindow(settings)
        val hasAccess = WakeUsage.hasUsageAccess(appContext)
        val lastUsed = if (hasAccess) {
            WakeUsage.lastDeviceUsedAt(appContext, ScheduleEngine.startOfDayMillis(dayKey, zone))
        } else {
            null
        }
        val lastPromptAt = getRawSetting(KEY_WAKE_LAST_PROMPT_AT)?.toLongOrNull()
        val promptedDay = getRawSetting(KEY_WAKE_FALLBACK_DAY)
        val prompt = ScheduleEngine.decideWakePrompt(
            now, window, force, hasAccess, lastUsed,
            morningStartedToday = false, lastPromptAt, promptedDay, dayKey, zone,
        )
        if (prompt && (!hasAccess || lastUsed == null)) {
            // The once-per-day fallback was the decider (no usable usage
            // signal): consume it so the next tick does not re-prompt.
            setRawSetting(KEY_WAKE_FALLBACK_DAY, dayKey)
        }

        // Keep the chain alive: next tick inside the window, otherwise the
        // chain stops and resumes at tomorrow's window start.
        val next = ScheduleEngine.nextWakeCheckAt(now, settings, zone)
        alarms.scheduleWakeCheck(next, ScheduleEngine.dayKey(next, zone), force = false)

        if (prompt) {
            notifications.showWakePrompt()
            setRawSetting(KEY_WAKE_LAST_PROMPT_AT, now.toString())
            return recordWakeCheck(WakeCheckOutcome.PROMPTED, now)
        }
        return recordWakeCheck(WakeCheckOutcome.NO_PROMPT, now)
    }

    /** Persists the wake evaluation outcome for the diagnostics row. */
    private fun recordWakeCheck(outcome: WakeCheckOutcome, now: Long): WakeCheckOutcome {
        setRawSetting(KEY_WAKE_LAST_OUTCOME, outcome.name)
        setRawSetting(KEY_WAKE_LAST_CHECK_AT, now.toString())
        return outcome
    }

    /**
     * Read-only wake-health snapshot for Settings → "Wake status". Used to
     * debug "the morning notification never arrived" reports with facts
     * instead of guesses.
     */
    fun wakeDiagnostics(): WakeDiagnostics = WakeDiagnostics(
        usageAccessGranted = try {
            WakeUsage.hasUsageAccess(appContext)
        } catch (e: Exception) {
            false
        },
        exactAlarmAllowed = try {
            alarms.canScheduleExact()
        } catch (e: Exception) {
            false
        },
        notificationsEnabled = try {
            notifications.areNotificationsEnabled()
        } catch (e: Exception) {
            false
        },
        chainArmed = try {
            alarms.isWakeChainArmed()
        } catch (e: Exception) {
            false
        },
        nextCheckAtMillis = try {
            alarms.nextScheduledCheckAt()
        } catch (e: Exception) {
            null
        },
        lastCheckOutcome = getRawSetting(KEY_WAKE_LAST_OUTCOME),
        lastCheckAtMillis = getRawSetting(KEY_WAKE_LAST_CHECK_AT)?.toLongOrNull(),
    )

    /**
     * v2.5: retires today's wake chain WITHOUT killing it: any pending
     * tick/snooze is dropped, and the chain's single slot is re-armed
     * for tomorrow's window start. Used wherever "the morning session
     * has started" used to cancel the chain outright (the 2026-10-23
     * failure: one correct ALREADY_STARTED outcome on 2026-10-22 left
     * no check armed for the next day at all).
     */
    private fun stopWakeChainForToday(now: Long) {
        alarms.cancelWakeChain()
        val zone = ZoneId.systemDefault()
        val next = ScheduleEngine.nextWakeChainAfterMorningStarted(now, getSettings(), zone)
        alarms.scheduleWakeCheck(next, ScheduleEngine.dayKey(next, zone), force = false)
    }

    /**
     * (Re-)arms the morning wake chain from current state. Called on app
     * start, boot, and wake-settings changes. Idempotent: the chain lives in
     * one stable alarm slot, so re-arming just replaces the pending check.
     */
    fun rearmWakeChain(now: Long) {
        val zone = ZoneId.systemDefault()
        val dayKey = ScheduleEngine.dayKey(now, zone)
        val settings = getSettings()
        if (hasMorningSessionToday(dayKey)) {
            // v2.5: today's morning is done — the chain's only remaining
            // job is tomorrow's first check. Arming it here (instead of
            // a bare cancel) is what makes the chain self-sustaining
            // across days without any external re-arm trigger.
            stopWakeChainForToday(now)
            return
        }
        val next = if (ScheduleEngine.isInWakeWindow(now, settings, zone)) {
            // Inside the window (boot / app start / settings change): check
            // soon instead of waiting a full interval, so a just-woken user
            // is not missed.
            now + 60_000L
        } else {
            ScheduleEngine.nextWakeCheckAt(now, settings, zone)
        }
        alarms.scheduleWakeCheck(next, ScheduleEngine.dayKey(next, zone), force = false)
    }

    /** The wake notification's "15 min baad" action: one single re-check in 15 minutes. */
    fun snoozeWakeCheck(now: Long) {
        val triggerAt = now + WAKE_SNOOZE_MILLIS
        val zone = ZoneId.systemDefault()
        alarms.scheduleWakeCheck(triggerAt, ScheduleEngine.dayKey(triggerAt, zone), force = true)
    }

    /**
     * Stops today's wake chain and clears the wake prompt (the morning
     * session was just started from the prompt). v2.5: the chain rolls
     * forward to tomorrow's window start instead of dying — see
     * [stopWakeChainForToday].
     */
    fun cancelWakeChain(now: Long) {
        stopWakeChainForToday(now)
        notifications.cancelWakePrompt()
    }

    // ---------------------------------------------------------------- reads

    fun getDose(doseId: Long): Dose? = queryDoseRow(db.readableDatabase, doseId)

    fun getSession(id: Long): DoseSession? = querySessionRow(db.readableDatabase, id)

    fun getSessionDoses(sessionId: Long): List<Dose> {
        db.readableDatabase.rawQuery(
            "SELECT * FROM doses WHERE sessionId = ? ORDER BY dropNumber ASC, id ASC",
            arrayOf(sessionId.toString())
        ).use { c ->
            val out = ArrayList<Dose>(c.count)
            while (c.moveToNext()) out.add(db.doseFromCursor(c))
            return out
        }
    }

    fun getTodaySessions(dayKey: String): List<DoseSession> {
        db.readableDatabase.rawQuery(
            "SELECT * FROM sessions WHERE dayKey = ? ORDER BY startedAt ASC, id ASC",
            arrayOf(dayKey)
        ).use { c ->
            val out = ArrayList<DoseSession>(c.count)
            while (c.moveToNext()) out.add(db.sessionFromCursor(c))
            return out
        }
    }

    fun getDosesForDay(dayKey: String): List<Dose> = queryDosesForDay(db.readableDatabase, dayKey)

    /**
     * Session counters under strict chaining: only confirmed + chain-head
     * rows exist, so the planned drop count is the total (not the row
     * count). taken = CONFIRMED count, upcoming = planned minus accounted.
     */
    fun sessionProgress(sessionId: Long, now: Long): Progress {
        val session = getSession(sessionId)
            ?: return Progress(taken = 0, pending = 0, missed = 0, upcoming = 0, total = 0)
        val planned = ScheduleEngine.dropCountFor(session.type, getSettings())
        return ScheduleEngine.sessionProgress(getSessionDoses(sessionId), now, planned)
    }

    /**
     * Day counters: planned total = sum of the started sessions' drop
     * counts (CONFLICT rows are failed attempts with no plan behind them).
     */
    fun dayProgress(dayKey: String, now: Long): Progress {
        val settings = getSettings()
        val planned = getTodaySessions(dayKey)
            .filter { it.status != SessionStatus.CONFLICT }
            .sumOf { ScheduleEngine.dropCountFor(it.type, settings) }
        return ScheduleEngine.sessionProgress(getDosesForDay(dayKey), now, planned)
    }

    /** Today's earliest-due PENDING dose, or null. */
    fun nextDueDose(dayKey: String, now: Long): Dose? {
        var best: Dose? = null
        var bestAt = Long.MAX_VALUE
        for (d in getDosesForDay(dayKey)) {
            if (d.status != DoseStatus.PENDING) continue
            val at = d.nextReminderAt() ?: continue
            if (at < bestAt) {
                bestAt = at
                best = d
            }
        }
        return best
    }

    /** Today's ACTIVE session, preferring the one holding the next due dose. */
    fun currentSession(dayKey: String, now: Long): DoseSession? {
        val active = getTodaySessions(dayKey).filter { it.status == SessionStatus.ACTIVE }
        if (active.isEmpty()) return null
        val next = nextDueDose(dayKey, now)
        return if (next != null) {
            active.firstOrNull { it.id == next.sessionId } ?: active.first()
        } else {
            active.first()
        }
    }

    fun getHistoryDays(limit: Int = 60): List<String> {
        db.readableDatabase.rawQuery(
            "SELECT DISTINCT dayKey FROM sessions ORDER BY dayKey DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            val out = ArrayList<String>(c.count)
            while (c.moveToNext()) out.add(c.getString(0))
            return out
        }
    }

    // --------------------------------------------------------------- alarms

    /**
     * One-time repair for databases corrupted by the pre-v1.5 double-write
     * bug (`startSession` pre-inserted the whole plan AND `confirmDose`
     * inserted another row for drop N+1 on every YES, with no unique
     * constraint on (sessionId, dropNumber) — e.g. "Taken 6 • Upcoming 8"
     * on an 8-drop session, where each YES could hit a different duplicate
     * row and idempotency never engaged).
     *
     * The row-selection decision is pure ([ScheduleEngine.planDoseRepair],
     * unit-tested); this only executes the deletions in one transaction and
     * drops the deleted rows' alarms. Only rows are deleted, never inserted,
     * so the once-daily special partial unique index cannot be violated.
     * Guarded by [KEY_REPAIR_V3]: runs once, then never again (duplication
     * is structurally impossible under strict chaining).
     *
     * Called at the top of [reconcileAlarms], so it runs on app start, boot,
     * package replacement (app update) and clock changes — before any alarm
     * is (re)scheduled from the cleaned state.
     */
    fun ensureDataRepaired() {
        if (getRawSetting(KEY_REPAIR_V3) == "done") return
        val deletedDoseIds = ArrayList<Long>()
        db.inTransaction { w ->
            w.rawQuery(
                "SELECT * FROM sessions WHERE status = ? ORDER BY id ASC",
                arrayOf(SessionStatus.ACTIVE.name)
            ).use { sc ->
                while (sc.moveToNext()) {
                    val session = db.sessionFromCursor(sc)
                    val doses = ArrayList<Dose>()
                    w.rawQuery(
                        "SELECT * FROM doses WHERE sessionId = ? ORDER BY id ASC",
                        arrayOf(session.id.toString())
                    ).use { dc ->
                        while (dc.moveToNext()) doses.add(db.doseFromCursor(dc))
                    }
                    val plan = ScheduleEngine.planDoseRepair(doses)
                    for (id in plan.deleteIds) {
                        w.delete("doses", "id = ?", arrayOf(id.toString()))
                        deletedDoseIds.add(id)
                    }
                }
            }
        }
        for (id in deletedDoseIds) alarms.cancelDose(id)
        setRawSetting(KEY_REPAIR_V3, "done")
    }

    /**
     * One-time v1.8 migration: the default special-drop position moved from 8
     * to 6 (user order).
     *
     * - If the flag is set → no-op.
     * - If the SAVED `specialDropPosition` is 8 (the old default) → set to 6.
     *   Any other saved value (the user explicitly picked it in Settings) is
     *   left untouched. A fresh install has no saved value and already
     *   defaults to 6.
     * - In-flight fix: if an ACTIVE MORNING session exists today, no live
     *   SPECIAL dose exists today (PENDING or CONFIRMED — mirrors the partial
     *   unique index), and a PENDING REGULAR dose sits at dropNumber == 6 in
     *   that session, its kind is flipped to SPECIAL. Same row id, same
     *   dueAt — no alarm changes needed (alarms are keyed by dose id and the
     *   receiver reads the row fresh). The index cannot be violated because
     *   the live-special check ran first.
     * - If drop 6 is already CONFIRMED (or no pending #6 exists), no special
     *   is invented elsewhere: under the new order today's special is then
     *   honestly missed.
     *
     * Guarded by [KEY_SPECIAL_POS_V18]. Called at the top of
     * [reconcileAlarms] right after [ensureDataRepaired], so it runs on app
     * start, boot, package replacement and clock changes.
     */
    fun ensureSpecialPositionMigration(now: Long = System.currentTimeMillis()) {
        if (getRawSetting(KEY_SPECIAL_POS_V18) == "done") return
        val zone = ZoneId.systemDefault()
        val today = ScheduleEngine.dayKey(now, zone)

        // 1. Migrate the saved setting 8 -> 6 (old default only; an
        // explicitly chosen value is never touched).
        ScheduleEngine.migratedSpecialPosition(
            getRawSetting("specialDropPosition")?.toIntOrNull()
        )?.let { newPos ->
            saveSettings(getSettings().copy(specialDropPosition = newPos))
        }

        // 2. In-flight flip for today's active morning session.
        // (Skipped entirely when the user disabled the special drop.)
        val session = findSession(SessionType.MORNING, today, SessionStatus.ACTIVE)
        if (session != null && getSettings().specialDropEnabled) {
            val todayDoses = getDosesForDay(today)
            if (!ScheduleEngine.isSpecialLiveToday(todayDoses, today)) {
                ScheduleEngine.flipTargetForSpecialPosition(
                    getSessionDoses(session.id), session.id
                )?.let { target ->
                    db.inTransaction { w ->
                        val cv = ContentValues().apply { put("kind", DoseKind.SPECIAL.name) }
                        w.update("doses", cv, "id = ?", arrayOf(target.id.toString()))
                    }
                }
            }
        }
        setRawSetting(KEY_SPECIAL_POS_V18, "done")
    }

    /**
     * v1.9: the user's corrected schedule is morning 7 / afternoon 6 /
     * evening 6 (was 8 / 7 / 7). One-time migration of the old defaults;
     * explicitly chosen counts are never touched.
     *
     * Mid-session safety: the morning session creates drops on demand
     * (strict chaining, v1.5), and `confirmDose` computes `isLastDrop`
     * from the LIVE settings — confirming #7 now completes the session
     * instead of inventing #8, so no orphan rows and no crash are
     * possible. If 7 are somehow already taken, the session simply
     * completes. Afternoon/evening haven't started today, so they start
     * fresh with 6.
     *
     * Guarded by [KEY_DROP_COUNT_V19]. Called at the top of
     * [reconcileAlarms] next to [ensureSpecialPositionMigration], so it
     * runs on app start, boot, package replacement and clock changes.
     */
    fun ensureDropCountMigration() {
        if (getRawSetting(KEY_DROP_COUNT_V19) == "done") return
        ScheduleEngine.migratedDropCounts(
            getRawSetting("morningDropCount")?.toIntOrNull(),
            getRawSetting("afternoonDropCount")?.toIntOrNull(),
            getRawSetting("eveningDropCount")?.toIntOrNull(),
        )?.let { (m, a, e) ->
            val s = getSettings()
            saveSettings(s.copy(morningDropCount = m, afternoonDropCount = a, eveningDropCount = e))
        }
        setRawSetting(KEY_DROP_COUNT_V19, "done")
    }

    /**
     * Recomputes the desired alarm set from the database (the source of
     * truth) and applies the diff. Called on app start, boot, time/timezone
     * changes and after a backup import.
     */
    fun reconcileAlarms(now: Long) {
        // v1.10: each one-time migration runs in isolation — a throwing
        // migration is logged and swallowed, so it can never block the
        // others. All migrations are idempotent + flag-guarded, so a failed
        // one simply retries on the next launch.
        ScheduleEngine.runMigrationSafely("ensureDataRepaired", ::logMigrationFailure) {
            ensureDataRepaired()
        }
        ScheduleEngine.runMigrationSafely("ensureSpecialPositionMigration", ::logMigrationFailure) {
            // One-time v1.8 migration: special-drop default position 8 -> 6,
            // plus the in-flight flip for today's active morning session.
            ensureSpecialPositionMigration(now)
        }
        ScheduleEngine.runMigrationSafely("ensureDropCountMigration", ::logMigrationFailure) {
            // One-time v1.9 migration: session sizes 8/7/7 -> 7/6/6 (the
            // user's corrected schedule). Safe mid-session: chaining creates
            // drops on demand and isLastDrop reads the live settings.
            ensureDropCountMigration()
        }
        // v2.10: a persisted "call ke baad" flag without its alarm is a
        // lie — the deferred re-fire alarms do not survive a reboot, so
        // the flags are dropped on every reconcile (boot / app start /
        // clock change) and re-established by the next actual deferral.
        // The live ALARMS are not touched: an app-start reconcile must
        // never cancel a deferred re-fire that is still legitimately
        // armed.
        clearCallDeferFlags()
        val zone = ZoneId.systemDefault()
        val today = ScheduleEngine.dayKey(now, zone)
        val yesterday = ScheduleEngine.dayKey(
            ScheduleEngine.startOfDayMillis(today, zone) - 1, zone
        )
        val settings = getSettings()

        val relevant = queryPendingRelevant(today, yesterday)

        // Evening reminders are never silently pushed beyond the cutoff:
        // persist any newly-MISSED rows before reconciling. The cutoff is an
        // EVENING-session rule only (user spec) — morning/afternoon doses are
        // never auto-marked MISSED by this pass, at any hour.
        //
        // v2.3 (Change 3b): doses of an ACTIVE evening session are exempt —
        // a running session's drops are never marked MISSED merely for
        // being past the cutoff (including drops pushed past it by
        // snoozing); reminders keep firing until the session completes.
        // Only stale evening doses outside an ACTIVE session remain
        // candidates, exactly as before.
        val cutoffMillis = ScheduleEngine.minutesOfDayToMillis(today, settings.cutoffMinutesOfDay, zone)
        val sessionById: Map<Long, DoseSession?> =
            relevant.map { it.sessionId }.distinct().associateWith { sid -> getSession(sid) }
        val cutoffCandidates = ScheduleEngine.eveningCutoffCandidates(
            relevant.filter { it.dayKey == today },
            sessionTypeOf = { sid -> sessionById[sid]?.type },
            sessionStatusOf = { sid -> sessionById[sid]?.status },
        )
        val afterCutoff = ScheduleEngine.applyCutoff(cutoffCandidates, cutoffMillis)
        db.inTransaction { w ->
            for (d in afterCutoff) {
                if (d.status == DoseStatus.MISSED) writeDoseMutable(w, d)
            }
        }
        val byId = afterCutoff.associateBy { it.id }
        val effective = relevant.map { byId[it.id] ?: it }

        // v2.3: live snooze windows for today's PENDING doses. A snoozed
        // dose is untouchable below: no catch-up fire, no nag (re-)arm —
        // its only reminder is the snooze alarm, re-armed at the end.
        val snoozeUntilById: Map<Long, Long> = effective
            .filter { it.status == DoseStatus.PENDING }
            .mapNotNull { d -> getRawSetting(snoozeKey(d.id))?.toLongOrNull()?.let { d.id to it } }
            .toMap()

        val result = ScheduleEngine.reconcile(effective, now)
        for (id in result.cancelDoseIds) {
            // A9: a non-pending dose must not keep a stale reminder behind
            // either — cancel its notification next to its alarm.
            alarms.cancelDose(id)
            // v2.4: nor a stale snooze watchdog.
            alarms.cancelSnoozeWatchdog(id)
            notifications.cancelDoseNotification(id)
        }
        for (spec in result.schedule) {
            getDose(spec.doseId)?.let { alarms.scheduleDose(it) }
        }

        // Overdue catch-up: PENDING doses whose reminder time already passed
        // beyond tolerance were deliberately skipped by ScheduleEngine.reconcile
        // (never re-armed with a shifted interval). But when the alarm itself
        // was lost AFTER the due time — late update-install, slow
        // reboot/reconcile, Doze delay — no reminder ever fired at all, and
        // the dose would sit OVERDUE in silence until the user opens the app.
        // Fire ONE immediate reminder per overdue episode. The KV flag is the
        // anti-nag guard: repeated reconciles (app opens, boots) must not
        // re-notify. Catch-up doses are exactly the ones `reconcile` skipped,
        // so a dose is never double-scheduled here.
        for (d in effective) {
            // v2.3: the snooze-aware overload — inside a snooze window no
            // catch-up may fire (the snooze alarm owns the dose).
            if (ScheduleEngine.isCatchupDue(d, now, snoozeUntil = snoozeUntilById[d.id]) &&
                getRawSetting(catchupKey(d.id)) != "1"
            ) {
                // v2.0 nag interplay: a pending nag must never double-notify.
                val nagNext = getRawSetting(nagKey(d.id))?.toLongOrNull()
                if (nagNext != null && nagNext > now) continue
                alarms.scheduleDoseAt(d, now)
                if (settings.nagEnabled && now >= d.dueAt + settings.nagIntervalMillis) {
                    // The nag pass below subsumes catch-up for this dose:
                    // record the nag trigger, NOT the catch-up flag, so the
                    // nag chain (not a one-shot) owns the dose from here.
                    setRawSetting(
                        nagKey(d.id),
                        ScheduleEngine.nextNagAt(d.dueAt, now, settings.nagIntervalMillis).toString()
                    )
                } else {
                    setRawSetting(catchupKey(d.id), "1")
                }
            }
        }

        // v2.0 nag safety net: re-arm nags lost to process death / Doze. Only
        // PENDING doses past their first nag point qualify, and only when no
        // live nag trigger exists (missing, or older than a minute = the
        // alarm never fired and went stale). Fires immediately, then the
        // receiver re-arms the chain on the anchored cadence.
        if (settings.nagEnabled) {
            for (d in effective) {
                if (d.status != DoseStatus.PENDING) continue
                // v2.3: a snoozed dose never gets a nag (re-)arm — its
                // snooze alarm is re-armed by the pass below instead.
                if (ScheduleEngine.isSnoozed(snoozeUntilById[d.id], now)) continue
                if (now < d.dueAt + settings.nagIntervalMillis) continue
                val nagNext = getRawSetting(nagKey(d.id))?.toLongOrNull()
                if (nagNext == null || nagNext < now - 60_000L) {
                    alarms.scheduleDoseAt(d, now)
                    setRawSetting(
                        nagKey(d.id),
                        ScheduleEngine.nextNagAt(d.dueAt, now, settings.nagIntervalMillis).toString()
                    )
                }
            }
        }

        // v2.3 snooze safety net: a snoozed dose's alarm lives on the dose's
        // own slot, which a reboot wipes — re-arm it for the snooze expiry
        // so the snoozed reminder survives restarts exactly like every
        // other alarm. Same slot (overwrites), so this is idempotent.
        // v2.4: the snooze watchdog is re-armed alongside it (its own
        // slot) whenever its fire time is still ahead — including the
        // reboot-straddling-expiry case (until <= now < watchdog time),
        // where the primary fire may have been lost with the reboot and
        // the watchdog is the rescue.
        for (d in effective) {
            if (d.status != DoseStatus.PENDING) continue
            val until = snoozeUntilById[d.id] ?: continue
            if (until > now) alarms.scheduleDoseAt(d, until)
            val watchdogAt = ScheduleEngine.snoozeWatchdogAt(until)
            if (watchdogAt > now) alarms.scheduleSnoozeWatchdog(d, watchdogAt)
        }

        rearmSessionStarts(today, now)
        // v1.13: process-death edge — a deferred start whose unblock moment
        // was missed (e.g. the completing YES happened while the process was
        // dead) fires here. Cheap: two KV reads when nothing is deferred.
        maybeStartDeferredSessions(now)

        // v2.5: wake-chain self-healing. Reconcile runs on every process
        // start and receiver-driven rebuild, so it is the one pass that
        // can guarantee the morning wake chain is alive. Before v2.5 a
        // chain cancelled by a (correct) MORNING_ALREADY_STARTED outcome
        // stayed dead until an external re-arm happened to land — on
        // 2026-10-23 none did, and the wake prompt never came. Validate
        // the alarm table against reality (never the recorded outcome):
        // a dead or stale chain is re-armed from current state.
        try {
            val chainArmed = alarms.isWakeChainArmed()
            val nextCheck = alarms.nextScheduledCheckAt()
            if (ScheduleEngine.wakeChainNeedsRearm(chainArmed, nextCheck, now)) {
                rearmWakeChain(now)
            }
        } catch (t: Throwable) {
            Log.w("DropsRepo", "wake-chain validation in reconcile failed", t)
        }
    }

    /**
     * Anti-nag flag for the overdue catch-up reminder: one flag per dose row,
     * set when the catch-up alarm fires, cleared when the dose leaves PENDING.
     */
    private fun catchupKey(doseId: Long): String = "overdue_catchup_$doseId"

    /**
     * v2.0: next scheduled nag trigger (epoch millis) for a dose, stored as
     * a string. Set when a nag is armed, cleared when the dose leaves
     * PENDING (see [writeDoseMutable]). A present future value means a nag
     * alarm is already pending — never double-notify.
     */
    private fun nagKey(doseId: Long): String = "nag_next_$doseId"

    /**
     * v2.0: arms (or re-arms) the missed-drop nag for [dose]. Same
     * PendingIntent slot as the dose reminder ([AlarmScheduler.scheduleDoseAt]),
     * so re-arming overwrites and can never duplicate. No-op when nagging is
     * disabled. The trigger is anchored to the dose's dueAt
     * ([ScheduleEngine.nextNagAt]), so repeated nags never drift.
     */
    fun armNag(dose: Dose, now: Long) {
        val settings = getSettings()
        if (!settings.nagEnabled) return
        // v2.3: never arm a nag inside a snooze window — the snoozed dose
        // is untouchable until its snooze alarm fires (AlarmReceiver also
        // early-returns for snoozed doses; this is defense in depth for
        // the other armNag callers).
        if (ScheduleEngine.isSnoozed(getSnoozeUntil(dose.id), now)) return
        val next = ScheduleEngine.nextNagAt(dose.dueAt, now, settings.nagIntervalMillis)
        alarms.scheduleDoseAt(dose, next)
        setRawSetting(nagKey(dose.id), next.toString())
    }

    /**
     * v2.0: drops every pending nag trigger. Called when the nag settings
     * change — the stale triggers were computed from the old interval/flag
     * and are recomputed on the next reconcile.
     */
    fun clearNagTriggers() {
        db.inTransaction { w ->
            w.delete("settings", "key LIKE 'nag_next_%'", null)
        }
    }

    /**
     * Re-arms the auto session-start alarms from the CURRENT settings.
     * v2.11: the decision of WHAT to arm moved to the pure
     * [ScheduleEngine.sessionStartRearmPlan] (unit-tested) — this only
     * executes the plan. Public since v2.11: the Settings screen calls it
     * right after a session-start time (or auto-start toggle) changes, so
     * the new time takes effect immediately instead of waiting for the
     * next app start / midnight rollover (the 2026-10-29 disconnect: the
     * button showed the new time but the alarm kept the old one).
     * Idempotent: every slot is stable + FLAG_UPDATE_CURRENT.
     */
    fun rearmSessionStarts(dayKey: String, now: Long) {
        val zone = ZoneId.systemDefault()
        val plan = ScheduleEngine.sessionStartRearmPlan(
            dayKey, now, getSettings(), zone,
            hasSessionRow = { type, dk -> hasSessionRow(type, dk) },
        )
        for ((type, dk) in plan.cancel) alarms.cancelSessionStart(type, dk)
        for (arm in plan.schedule) alarms.scheduleSessionStart(arm.type, arm.dayKey, arm.triggerAtMillis)
    }

    private fun queryPendingRelevant(today: String, yesterday: String): List<Dose> {
        db.readableDatabase.rawQuery(
            "SELECT * FROM doses WHERE status = ? AND (dayKey = ? OR dayKey = ? OR nextRetryAt IS NOT NULL)",
            arrayOf(DoseStatus.PENDING.name, today, yesterday)
        ).use { c ->
            val out = ArrayList<Dose>(c.count)
            while (c.moveToNext()) out.add(db.doseFromCursor(c))
            return out
        }
    }

    // ------------------------------------------------------- day lifecycle

    /**
     * Rolls the day over when the device date changed since the last run:
     * finalizes the old day (still-PENDING doses become MISSED), records the
     * new day, arms today's auto session starts, and immediately starts any
     * auto session whose planned time already passed.
     */
    fun ensureDayRollover(now: Long) {
        val zone = ZoneId.systemDefault()
        val today = ScheduleEngine.dayKey(now, zone)
        val last = getRawSetting(KEY_LAST_DAY)
        if (last == null) {
            // First run: nothing to finalize, just record the day and arm starts.
            setRawSetting(KEY_LAST_DAY, today)
            rearmSessionStarts(today, now)
            return
        }
        if (last == today) return

        finalizeDay(last)
        // Cover multi-day gaps (device was off for days): finalize any other
        // stale day that still has pending doses.
        db.readableDatabase.rawQuery(
            "SELECT DISTINCT dayKey FROM doses WHERE status = ? AND dayKey != ?",
            arrayOf(DoseStatus.PENDING.name, today)
        ).use { c ->
            val stale = ArrayList<String>()
            while (c.moveToNext()) stale.add(c.getString(0))
            for (day in stale) finalizeDay(day)
        }

        setRawSetting(KEY_LAST_DAY, today)

        val settings = getSettings()
        for (type in listOf(SessionType.AFTERNOON, SessionType.EVENING)) {
            if (hasSessionRow(type, today)) continue
            val enabled = if (type == SessionType.AFTERNOON) settings.autoStartAfternoon
            else settings.autoStartEvening
            if (!enabled) continue
            val startAt = ScheduleEngine.plannedSessionStartMillis(type, today, settings, zone)
            if (startAt <= now) {
                // Planned start already passed (e.g. device was off): start now.
                // v1.13: autoStartSession (not bare startSession) so the
                // single-active-session guard defers cleanly instead of
                // overlapping when an earlier session is still ACTIVE.
                // A11: a conflict is no longer silent — the user is told why
                // nothing was scheduled.
                try {
                    when (val r = autoStartSession(type, now)) {
                        is StartResult.Conflict -> notifications.showConflictNotification(r.reason)
                        is StartResult.Started,
                        is StartResult.AlreadyActive,
                        is StartResult.Deferred -> { /* nothing to surface */ }
                    }
                } catch (e: Exception) {
                    // Leave it for the next trigger.
                }
            }
        }
        rearmSessionStarts(today, now)
    }

    /** Marks every still-PENDING dose of [dayKey] as MISSED and drops alarms. */
    fun finalizeDay(dayKey: String) {
        val doses = getDosesForDay(dayKey)
        val finalized = ScheduleEngine.finalizeDay(doses, dayKey)
        val newlyMissed = ArrayList<Long>()
        db.inTransaction { w ->
            for ((orig, upd) in doses.zip(finalized)) {
                if (upd.status != orig.status) {
                    writeDoseMutable(w, upd)
                    newlyMissed.add(upd.id)
                }
            }
            // v1.13: deferred starts are day-scoped — a stale deferral must
            // never leak into a new day.
            for (type in listOf(SessionType.AFTERNOON, SessionType.EVENING)) {
                w.delete("settings", "key = ?", arrayOf(ScheduleEngine.deferredStartKey(type, dayKey)))
            }
        }
        for (id in newlyMissed) {
            // A8: the finalized dose's reminder must not linger in the tray.
            alarms.cancelDose(id)
            // v2.4: nor its snooze watchdog.
            alarms.cancelSnoozeWatchdog(id)
            notifications.cancelDoseNotification(id)
        }
        notifications.cancelDeferredStartNotification()
    }

    // ---------------------------------------------------------- backup I/O

    fun exportBackup(): String =
        BackupJson.exportBackup(getAllSessions(), getAllDoses(), getSettings())

    fun importBackup(json: String): BackupJson.ImportResult {
        val result = BackupJson.importBackup(json)
        if (result is BackupJson.ImportResult.Error) return result
        val data = (result as BackupJson.ImportResult.Ok).data
        val now = System.currentTimeMillis()
        // A12: finalize stale days of the CURRENT data before it is replaced
        // (stale PENDING doses become MISSED with their alarms and
        // notifications dropped; lastDayKey pinned to today) — without
        // ensureDayRollover's auto-start side effects, which would act on
        // rows about to be wiped.
        finalizeStaleDays(now)
        // A3(4): a legacy backup may carry two ACTIVE rows for one day —
        // dedupe exactly like the v2->v3 upgrade repair (keep the newest
        // ACTIVE per dayKey) so the new partial unique index can never fire
        // on an import.
        val (sessions, doses) = dedupeImportSessions(data.sessions, data.doses)
        db.inTransaction { w ->
            w.delete("doses", null, null)
            w.delete("sessions", null, null)
            for (s in sessions) insertSessionRowWithId(w, s)
            for (d in doses) insertDoseRowWithId(w, d)
            for ((k, v) in settingsToMap(data.settings)) upsertSetting(w, k, v)
            // A12: stale per-dose flags must not suppress notifications for
            // the imported rows — imported ids can collide with the old rows'
            // flags. Every flag references a wiped row, so drop them all.
            w.delete("settings", "key LIKE 'overdue_catchup_%'", null)
            w.delete("settings", "key LIKE 'nag_next_%'", null)
            // v2.3: same A12 hygiene for snooze flags — a stale
            // snooze_until_<id> would make an imported dose untouchable.
            w.delete("settings", "key LIKE 'snooze_until_%'", null)
            // Explicit ids were inserted: move the AUTOINCREMENT counters past
            // them so future inserts never collide with imported ids.
            w.execSQL(
                "UPDATE sqlite_sequence SET seq = COALESCE((SELECT MAX(id) FROM sessions), 0) WHERE name = 'sessions'"
            )
            w.execSQL(
                "UPDATE sqlite_sequence SET seq = COALESCE((SELECT MAX(id) FROM doses), 0) WHERE name = 'doses'"
            )
        }
        reconcileAlarms(now)
        return result
    }

    /**
     * A12: finalizes stale days without the auto-start side effects of a
     * full [ensureDayRollover] — used by [importBackup], whose current rows
     * are about to be replaced anyway.
     */
    private fun finalizeStaleDays(now: Long) {
        val zone = ZoneId.systemDefault()
        val today = ScheduleEngine.dayKey(now, zone)
        val last = getRawSetting(KEY_LAST_DAY)
        if (last == null) {
            setRawSetting(KEY_LAST_DAY, today)
            return
        }
        if (last == today) return
        finalizeDay(last)
        db.readableDatabase.rawQuery(
            "SELECT DISTINCT dayKey FROM doses WHERE status = ? AND dayKey != ?",
            arrayOf(DoseStatus.PENDING.name, today)
        ).use { c ->
            val stale = ArrayList<String>()
            while (c.moveToNext()) stale.add(c.getString(0))
            for (day in stale) finalizeDay(day)
        }
        setRawSetting(KEY_LAST_DAY, today)
    }

    /**
     * A3(4): dedupes imported sessions the same way the v2->v3 upgrade
     * repair does — per dayKey the newest ACTIVE row (max id) is kept, the
     * rest are demoted to CANCELLED and their PENDING doses are cancelled —
     * so the partial unique index on (dayKey WHERE ACTIVE) can never fire
     * on an import. Pure: the caller inserts the returned rows.
     */
    private fun dedupeImportSessions(
        sessions: List<DoseSession>, doses: List<Dose>
    ): Pair<List<DoseSession>, List<Dose>> {
        val demoted = HashSet<Long>()
        val outSessions = ArrayList<DoseSession>(sessions.size)
        sessions.groupBy { it.dayKey }.forEach { (_, group) ->
            val keep = group.filter { it.status == SessionStatus.ACTIVE }.maxByOrNull { it.id }
            for (s in group) {
                if (s.status == SessionStatus.ACTIVE && keep != null && s.id != keep.id) {
                    demoted.add(s.id)
                    outSessions.add(s.copy(status = SessionStatus.CANCELLED))
                } else {
                    outSessions.add(s)
                }
            }
        }
        if (demoted.isEmpty()) return Pair(sessions, doses)
        val outDoses = doses.map { d ->
            if (d.sessionId in demoted && d.status == DoseStatus.PENDING)
                d.copy(status = DoseStatus.CANCELLED, nextRetryAt = null)
            else d
        }
        return Pair(outSessions, outDoses)
    }

    /** Deletes all dose/session history (settings are kept). Caller confirms first. */
    fun clearHistory() {
        // Capture alarm ids before the rows disappear so no orphan rings.
        val pendingIds = db.readableDatabase.rawQuery(
            "SELECT id FROM doses WHERE status = ?", arrayOf(DoseStatus.PENDING.name)
        ).use { c ->
            val out = ArrayList<Long>(c.count)
            while (c.moveToNext()) out.add(c.getLong(0))
            out
        }
        db.inTransaction { w ->
            w.delete("doses", null, null)
            w.delete("sessions", null, null)
            // v2.3: the wiped doses' snooze flags must not linger either —
            // the day restarts cleanly.
            w.delete("settings", "key LIKE 'snooze_until_%'", null)
            // v1.13: a full wipe also drops today's deferred starts — the day
            // restarts cleanly via the re-armed start alarms instead.
            val dayKey = todayKey(System.currentTimeMillis())
            for (type in listOf(SessionType.AFTERNOON, SessionType.EVENING)) {
                w.delete("settings", "key = ?", arrayOf(ScheduleEngine.deferredStartKey(type, dayKey)))
            }
        }
        for (id in pendingIds) {
            alarms.cancelDose(id)
            // v2.4: nor a snooze watchdog.
            alarms.cancelSnoozeWatchdog(id)
        }
        notifications.cancelDeferredStartNotification()
        // Session-start alarms for today are re-armed by the next reconcile.
    }

    fun close() {
        db.close()
    }

    // ------------------------------------------------------- private helpers

    private fun findSession(type: SessionType, dayKey: String, status: SessionStatus? = null): DoseSession? {
        val sql = if (status == null)
            "SELECT * FROM sessions WHERE type = ? AND dayKey = ? ORDER BY id DESC LIMIT 1"
        else
            "SELECT * FROM sessions WHERE type = ? AND dayKey = ? AND status = ? ORDER BY id DESC LIMIT 1"
        val args = if (status == null) arrayOf(type.name, dayKey)
        else arrayOf(type.name, dayKey, status.name)
        db.readableDatabase.rawQuery(sql, args).use { c ->
            return if (c.moveToFirst()) db.sessionFromCursor(c) else null
        }
    }

    private fun hasSessionRow(type: SessionType, dayKey: String): Boolean =
        findSession(type, dayKey) != null

    private fun queryDoseRow(w: SQLiteDatabase, id: Long): Dose? {
        w.rawQuery("SELECT * FROM doses WHERE id = ?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) db.doseFromCursor(c) else null
        }
    }

    private fun querySessionRow(w: SQLiteDatabase, id: Long): DoseSession? {
        w.rawQuery("SELECT * FROM sessions WHERE id = ?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) db.sessionFromCursor(c) else null
        }
    }

    private fun queryDosesForDay(w: SQLiteDatabase, dayKey: String): List<Dose> {
        w.rawQuery(
            "SELECT * FROM doses WHERE dayKey = ? ORDER BY dueAt ASC, id ASC",
            arrayOf(dayKey)
        ).use { c ->
            val out = ArrayList<Dose>(c.count)
            while (c.moveToNext()) out.add(db.doseFromCursor(c))
            return out
        }
    }

    private fun insertSessionRow(
        w: SQLiteDatabase, type: SessionType, dayKey: String, startedAt: Long, status: SessionStatus
    ): Long {
        val cv = ContentValues().apply {
            put("type", type.name)
            put("dayKey", dayKey)
            put("startedAt", startedAt)
            put("status", status.name)
        }
        return w.insertOrThrow("sessions", null, cv)
    }

    private fun insertDoseRow(
        w: SQLiteDatabase, sessionId: Long, dropNumber: Int, kind: DoseKind,
        dayKey: String, dueAt: Long
    ): Long {
        val cv = ContentValues().apply {
            put("sessionId", sessionId)
            put("dropNumber", dropNumber)
            put("kind", kind.name)
            put("dayKey", dayKey)
            put("dueAt", dueAt)
            putNull("confirmedAt")
            put("status", DoseStatus.PENDING.name)
            put("retryCount", 0)
            putNull("nextRetryAt")
        }
        return w.insertOrThrow("doses", null, cv)
    }

    private fun insertSessionRowWithId(w: SQLiteDatabase, s: DoseSession) {
        val cv = ContentValues().apply {
            put("id", s.id)
            put("type", s.type.name)
            put("dayKey", s.dayKey)
            put("startedAt", s.startedAt)
            put("status", s.status.name)
        }
        w.insertOrThrow("sessions", null, cv)
    }

    private fun insertDoseRowWithId(w: SQLiteDatabase, d: Dose) {
        val cv = ContentValues().apply {
            put("id", d.id)
            put("sessionId", d.sessionId)
            put("dropNumber", d.dropNumber)
            put("kind", d.kind.name)
            put("dayKey", d.dayKey)
            put("dueAt", d.dueAt)
            if (d.confirmedAt == null) putNull("confirmedAt") else put("confirmedAt", d.confirmedAt)
            put("status", d.status.name)
            put("retryCount", d.retryCount)
            if (d.nextRetryAt == null) putNull("nextRetryAt") else put("nextRetryAt", d.nextRetryAt)
        }
        w.insertOrThrow("doses", null, cv)
    }

    private fun writeDoseMutable(w: SQLiteDatabase, d: Dose) {
        val cv = ContentValues().apply {
            put("status", d.status.name)
            if (d.confirmedAt == null) putNull("confirmedAt") else put("confirmedAt", d.confirmedAt)
            put("retryCount", d.retryCount)
            if (d.nextRetryAt == null) putNull("nextRetryAt") else put("nextRetryAt", d.nextRetryAt)
        }
        w.update("doses", cv, "id = ?", arrayOf(d.id.toString()))
        if (d.status != DoseStatus.PENDING) {
            // The overdue episode ended (CONFIRMED / MISSED / CANCELLED): the
            // catch-up anti-nag flag AND the v2.0 nag trigger for this dose
            // row must not linger. (A re-created chained row gets a fresh id
            // anyway; this is hygiene for the same row.) Decline (NO) keeps
            // PENDING, so its flags correctly stay.
            w.delete("settings", "key = ?", arrayOf(catchupKey(d.id)))
            w.delete("settings", "key = ?", arrayOf(nagKey(d.id)))
            // v2.3: same hygiene for the snooze flag — a resolved dose is
            // never snoozed. This one delete covers confirm, skip,
            // session-stop (cancelSession), cutoff and finalizeDay, which
            // all resolve doses through this writer.
            w.delete("settings", "key = ?", arrayOf(snoozeKey(d.id)))
        }
    }

    private fun updateSessionStatus(w: SQLiteDatabase, sessionId: Long, status: SessionStatus) {
        val cv = ContentValues().apply { put("status", status.name) }
        w.update("sessions", cv, "id = ?", arrayOf(sessionId.toString()))
    }

    private fun getAllSessions(): List<DoseSession> {
        db.readableDatabase.rawQuery("SELECT * FROM sessions ORDER BY id ASC", null).use { c ->
            val out = ArrayList<DoseSession>(c.count)
            while (c.moveToNext()) out.add(db.sessionFromCursor(c))
            return out
        }
    }

    private fun getAllDoses(): List<Dose> {
        db.readableDatabase.rawQuery("SELECT * FROM doses ORDER BY id ASC", null).use { c ->
            val out = ArrayList<Dose>(c.count)
            while (c.moveToNext()) out.add(db.doseFromCursor(c))
            return out
        }
    }

    /**
     * Kind for the Nth drop of a session. Delegates to
     * [ScheduleEngine.kindForDrop], the single source of truth shared with
     * [ScheduleEngine.planSession] — the rule is identical at session start
     * (drop 1) and on every chained YES (drop N+1).
     */
    private fun kindForDrop(
        type: SessionType, dropNumber: Int, settings: DoseSettings, specialTaken: Boolean
    ): DoseKind = ScheduleEngine.kindForDrop(type, dropNumber, settings, specialTaken)
}
