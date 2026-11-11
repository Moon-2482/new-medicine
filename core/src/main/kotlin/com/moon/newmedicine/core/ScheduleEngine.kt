package com.moon.newmedicine.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure scheduling engine. Every function is a pure function of its inputs —
 * no Android classes, no I/O, no network — so the whole dosing state machine
 * is verifiable with a fake [Clock].
 *
 * Timing-integrity rules enforced here:
 *  - Each next drop is due at (confirmation time + doseInterval): computed from
 *    the SAVED confirmation timestamp, never from a chain of timers, so there
 *    is no cumulative drift.
 *  - Answering NO never starts the next drop's countdown; it only arms a
 *    retry (retryInterval) for the SAME dose.
 *  - Dismissing a notification changes nothing.
 *  - Intervals are NEVER shortened and doses NEVER skipped automatically to
 *    fit the cutoff; an evening plan that cannot fit returns
 *    [SessionPlan.Conflict]. The cutoff applies to EVENING sessions only.
 *  - The special drop exists at most once per [Dose.dayKey] and only inside a
 *    MORNING session.
 */
object ScheduleEngine {

    private val DAY_KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** Local calendar date of [epochMillis] as "yyyy-MM-dd". */
    fun dayKey(epochMillis: Long, zone: ZoneId): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone).format(DAY_KEY_FORMAT)

    /** Epoch millis of local midnight starting the given [dayKey]. */
    fun startOfDayMillis(dayKey: String, zone: ZoneId): Long =
        LocalDate.parse(dayKey, DAY_KEY_FORMAT).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Epoch millis of a local minutes-of-day on the given [dayKey]. */
    fun minutesOfDayToMillis(dayKey: String, minutesOfDay: Int, zone: ZoneId): Long =
        startOfDayMillis(dayKey, zone) + minutesOfDay * 60L * 1000L

    /** Minutes-of-day (local) of [epochMillis]. */
    fun minutesOfDay(epochMillis: Long, zone: ZoneId): Int {
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        return zdt.hour * 60 + zdt.minute
    }

    fun dropCountFor(type: SessionType, settings: DoseSettings): Int = when (type) {
        SessionType.MORNING -> settings.morningDropCount
        SessionType.AFTERNOON -> settings.afternoonDropCount
        SessionType.EVENING -> settings.eveningDropCount
    }

    /**
     * New Medicine: the number SHOWN to the user for the drop at sequence
     * position [sequenceNumber] (1-based) of a [type] session.
     *
     * Plain identity — there is no special drop in this app, so no
     * remapping is needed or wanted (the old Drops Reminder remapped the
     * afternoon/evening last drop 6 → 7 so "6" could only ever mean its
     * special drop; that rule is gone with the special drop itself).
     *
     * DISPLAY ONLY — the single source of truth for every user-visible
     * drop number. Stored doses keep the sequence number in
     * [Dose.dropNumber]; chaining, [kindForDrop], counts, progress and
     * alarm request codes all keep using the sequence number. Never feed
     * a display number back into logic.
     */
    fun displayNumberFor(type: SessionType, sequenceNumber: Int): Int = sequenceNumber

    /**
     * Latest local start time (minutes-of-day) that still lets a session of
     * [dropCount] drops finish by [cutoffMinutesOfDay].
     */
    fun latestStartMinutesOfDay(dropCount: Int, doseIntervalMillis: Long, cutoffMinutesOfDay: Int): Int {
        val spanMinutes = ((dropCount - 1) * doseIntervalMillis) / (60L * 1000L)
        return (cutoffMinutesOfDay - spanMinutes).toInt()
    }

    /**
     * Builds the dose plan for a session starting at [startAtMillis].
     *
     * The special once-daily drop is included only when:
     *  - the session is MORNING,
     *  - [DoseSettings.specialDropEnabled] is true,
     *  - [specialAlreadyTakenToday] is false.
     *
     * Returns [SessionPlan.Conflict] — with a human-readable reason — when an
     * EVENING session's last drop would fall after the daily cutoff. The
     * cutoff check applies to EVENING sessions only (user spec): MORNING and
     * AFTERNOON sessions may start at any hour. The caller must surface a
     * conflict and ask the user to adjust; the engine never shortens
     * intervals.
     *
     * STRICT CHAINING (v1.5): the plan is still computed in full — validation
     * needs every drop (counts, the evening-cutoff check, the special-drop
     * rule) — but the repository persists ONLY the first dose. Each YES
     * inserts exactly one next row (dueAt = confirmedAt + interval), so a
     * drop never exists before the previous one is taken and duplicate
     * dropNumbers are structurally impossible.
     */
    /**
     * Single-active-session invariant (v1.13): a session of [type] may start
     * only when no OTHER session is ACTIVE. Returns the blocking session, or
     * null when the start may proceed (no active session, or the active one
     * is the same type — the latter keeps the existing AlreadyActive path).
     *
     * Pure so the rule is unit-testable; the repository applies it with the
     * database's current ACTIVE row.
     */
    fun blockingSession(active: DoseSession?, type: SessionType): DoseSession? =
        active?.takeIf { it.type != type }

    /**
     * KV key for a deferred auto session start: set when a session's start
     * alarm fired while another session was still ACTIVE. Scoped to the day
     * so a stale deferral can never leak into a new day.
     *
     * v2.3 value format (see [deferredFlagValue]): "1" while the blocker is
     * still ACTIVE (waiting, no start time known yet); "ready:<millis>"
     * once the blocker has cleared — the deferred session then starts at
     * that instant (blocker-clear + [DEFERRED_START_DELAY_MILLIS]).
     */
    fun deferredStartKey(type: SessionType, dayKey: String): String =
        "deferred_start_${type.name}_$dayKey"

    /**
     * v2.3: a deferred session starts this long after the blocking session
     * clears (user order 2026-10-22) — never the instant the blocker ends.
     */
    const val DEFERRED_START_DELAY_MILLIS: Long = 30L * 60 * 1000

    /** v2.3: when a deferred session may start, given the blocker cleared at [clearedAtMillis]. */
    fun deferredReadyAt(clearedAtMillis: Long): Long =
        clearedAtMillis + DEFERRED_START_DELAY_MILLIS

    /**
     * v2.3: encodes a deferred-start flag value. [readyAtMillis] == null →
     * the waiting phase ("1": blocker still ACTIVE, start time unknown);
     * non-null → the ready phase ("ready:<millis>").
     */
    fun deferredFlagValue(readyAtMillis: Long?): String =
        readyAtMillis?.let { "ready:$it" } ?: "1"

    /**
     * v2.3: the ready-at instant encoded in a deferred-start flag value, or
     * null when the flag is absent, still in the waiting phase ("1"), or
     * malformed.
     */
    fun deferredReadyAtFromFlag(raw: String?): Long? =
        raw?.takeIf { it.startsWith("ready:") }?.removePrefix("ready:")?.toLongOrNull()

    // ------------------------------------------------------------- snooze

    /**
     * v2.3: the fixed snooze durations the user can pick (10 / 20 / 30 / 60
     * minutes) — user-confirmed presets, no free-form entry.
     */
    val SNOOZE_DURATIONS_MILLIS: List<Long> =
        listOf(10L, 20L, 30L, 60L).map { it * 60_000L }

    /**
     * v2.3: KV key holding a dose's snooze-until timestamp (epoch millis,
     * stored as a string). Follows the `nag_next_<doseId>` flag pattern —
     * deliberately NOT a doses-table column (no schema change).
     */
    fun snoozeKey(doseId: Long): String = "snooze_until_$doseId"

    /**
     * v2.3: true while [nowMillis] is inside a snooze window. A snoozed
     * dose is UNTOUCHABLE for reminders: no nag fires, no catch-up fires —
     * the snooze alarm (armed for [snoozeUntil] on the dose's own slot) is
     * the only reminder that may sound, exactly at expiry.
     */
    fun isSnoozed(snoozeUntil: Long?, nowMillis: Long): Boolean =
        snoozeUntil != null && nowMillis < snoozeUntil

    /**
     * v2.4: what a dose-reminder alarm fire must do. This is the exact
     * branch order AlarmReceiver has always used (status → snooze →
     * ring), extracted so the full sequence — due → snooze → expiry —
     * is unit-testable (in v2.3 the branches lived inline in the
     * receiver, beyond the reach of any test). [alreadyRingingForDose]
     * is the one new input: a fire that lands while this dose's ring is
     * still sounding (the snooze watchdog firing right at the 2-minute
     * ring cap) must not restart the ring — the nag re-arm still runs.
     */
    fun doseFireDecision(
        status: DoseStatus,
        snoozeUntil: Long?,
        nowMillis: Long,
        alreadyRingingForDose: Boolean,
    ): FireDecision = when {
        status != DoseStatus.PENDING -> FireDecision.STALE
        isSnoozed(snoozeUntil, nowMillis) -> FireDecision.SNOOZED
        alreadyRingingForDose -> FireDecision.ALREADY_RINGING
        else -> FireDecision.RING
    }

    // ------------------------------------------- snooze-expiry ring (v2.9)

    /**
     * v2.9: what user-visible output a fire must produce, from its
     * [FireDecision] plus whether the ring actually started. This is
     * the contract the 2026-10-24 snooze-expiry failure pinned down
     * (live report: a 30-minute snooze expired into a small quiet
     * notification, no audible ring): a snooze expiry — primary alarm
     * or watchdog, both route through the identical receiver path —
     * must produce the FULL ring exactly like a fresh dose fire, and
     * a failed ring start must yield the fallback note, never
     * silence. The mapping is total: no decision + start outcome
     * combination falls through to nothing.
     */
    fun doseFireOutput(decision: FireDecision, ringStarted: Boolean): FireOutput =
        when (decision) {
            FireDecision.RING ->
                if (ringStarted) FireOutput.FULL_RING else FireOutput.FALLBACK_NOTE
            FireDecision.SNOOZED -> FireOutput.SILENT_REARM
            FireDecision.STALE -> FireOutput.CANCEL_TRAY
            FireDecision.ALREADY_RINGING -> FireOutput.KEEP_RINGING
        }

    /**
     * v2.9: pause before the single bounded ring-start retry
     * ([ringRetryAllowed]). A refused foreground-service start at a
     * fire (cold process / OEM background block — the failure class
     * behind the 2026-10-24 quiet expiry) is often transient: the
     * exact-alarm exemption window / transient block clears within
     * seconds. Five seconds is long enough to matter and short
     * enough to be harmless on the failure path only — the success
     * path never sleeps.
     */
    const val RING_RETRY_DELAY_MILLIS: Long = 5_000L

    /**
     * v2.9: at most ONE ring-start retry after a refusal. The retry
     * is bounded so a systematically refused start (an OEM that
     * blocks background foreground services outright) degrades to
     * the fallback note after one attempt instead of looping the
     * receiver — the dose is never invisible, and never retried
     * forever.
     */
    fun ringRetryAllowed(retriesUsed: Int): Boolean = retriesUsed < 1

    /**
     * v2.6: maps the phone's ringer mode to how a dose-reminder ring
     * must behave. The mode arrives as a plain Int (the core stays
     * Android-free): the values are AudioManager's — SILENT = 0,
     * VIBRATE = 1, NORMAL = 2. Decided ONCE at ring start; the next
     * nag / re-fire re-decides, so flipping the phone to vibrate mid-way
     * takes effect at the next reminder at the latest.
     *
     * Unknown / future values fall back to the FULL ring: a ring that
     * sounds when it arguably shouldn't costs the user one tap, while a
     * reminder that never alerts can cost a missed dose.
     */
    fun ringBehaviorFor(ringerMode: Int): RingBehavior = when (ringerMode) {
        0 -> RingBehavior.VISUAL_ONLY // AudioManager.RINGER_MODE_SILENT
        1 -> RingBehavior.VIBRATE_ONLY // AudioManager.RINGER_MODE_VIBRATE
        else -> RingBehavior.SOUND_AND_VIBRATE // RINGER_MODE_NORMAL (2) + anything unknown
    }

    // ---------------------------------------- hardware silence (v2.7)

    /**
     * v2.7: the volume streams the hardware-silence watcher compares.
     * AudioManager values as plain Ints (core stays Android-free):
     * STREAM_ALARM = 4, STREAM_RING = 2, STREAM_MUSIC = 3,
     * STREAM_NOTIFICATION = 5. The reminder ring is silenced by ANY
     * hardware volume press, whichever stream the press lands on, so
     * every stream a volume key can plausibly drive outside a voice
     * call is watched. (Inside a call the keys drive STREAM_VOICE_CALL,
     * which does not surface through the settings observer — the power
     * button is the reliable mid-call trigger; see TEST-REPORT §29.)
     */
    val SILENCE_WATCHED_VOLUME_STREAMS: List<Int> = listOf(4, 2, 3, 5)

    /**
     * v2.7: true when any watched stream's volume in [current] differs
     * from the [snapshot] taken at ring start. Unwatched streams are
     * ignored entirely; a watched stream missing from [snapshot] cannot
     * prove a change; a watched stream missing from [current] counts
     * as changed (its re-read failed, so it can no longer be proven
     * unchanged). The service's own playback never changes a stream
     * volume, so any detected change is the user's hardware press.
     */
    fun volumeChangedSince(snapshot: Map<Int, Int>, current: Map<Int, Int>): Boolean =
        SILENCE_WATCHED_VOLUME_STREAMS.any { stream ->
            val before = snapshot[stream]
            before != null && current[stream] != before
        }

    /**
     * v2.7: the per-episode silence decision. A hardware trigger
     * (screen off/on, watched volume change) silences the ring's sound
     * only while the sound is actually playing and only once per
     * episode: in VIBRATE_ONLY / VISUAL_ONLY episodes (no player) and
     * after an earlier silence in the same episode, every trigger is a
     * harmless no-op. Silencing never answers the dose — vibration and
     * the notification carry on, and the next nag / re-fire rings
     * audibly again.
     */
    fun shouldSilenceSound(soundPlaying: Boolean, alreadySilenced: Boolean): Boolean =
        soundPlaying && !alreadySilenced

    // ------------------------------------------- ring-start guard (v2.8)

    /**
     * v2.8: the failure policy for a ring start in the foreground
     * service (live failure 2026-10-23, TEST-REPORT §30). Before v2.8,
     * `onStartCommand` / `startRinging` carried NO try/catch, and the
     * receiver-side fail-loud catch only covers a REFUSED service
     * start — so any failure inside the service after the start was
     * accepted (system FGS enforcement, process death, an uncaught
     * throw) produced zero user-visible output, silently. The policy:
     * a failure [RingStartPhase.BEFORE_FOREGROUND] on a dose falls
     * back to the plain reminder — a dose is never invisible; the same
     * failure on the Settings test ring just stops (the user is
     * looking at the app; no dose is at stake); a failure
     * [RingStartPhase.AFTER_FOREGROUND] never tears the ring down —
     * the foreground notification is already the reminder, so the
     * episode simply continues, bounded by its cap. The hardware-
     * silence triggers are not part of this decision at all: they
     * register after the ring is established, fully isolated, so their
     * failure cannot reach this function.
     */
    fun ringStartFallback(phase: RingStartPhase, isTest: Boolean): RingStartFallback =
        when {
            phase == RingStartPhase.AFTER_FOREGROUND -> RingStartFallback.CONTINUE_RING
            isTest -> RingStartFallback.STOP_QUIETLY
            else -> RingStartFallback.POST_PLAIN_REMINDER
        }

    // ------------------------------------------- call deferral (v2.10)

    /**
     * v2.10: user order 2026-10-29 — during a phone call the dose
     * reminder must produce NOTHING (no sound, no vibration, no
     * notification; it drowned out his calls), and fire after a
     * configurable gap once the call is over. Call detection uses
     * AudioManager.getMode(), which needs NO permission (unlike
     * TelephonyManager/READ_PHONE_STATE) — and it covers cellular
     * calls, WhatsApp/VoIP calls, and the ringing-but-unanswered
     * state alike. The mode arrives as a plain Int (core stays
     * Android-free): AudioManager.MODE_IN_CALL = 2, MODE_IN_COMMUNICATION
     * = 3 (VoIP), MODE_RINGTONE = 1 (ringing), MODE_NORMAL = 0.
     *
     * A ringing-but-unanswered phone counts as call-active on
     * purpose: a dose reminder bursting in over the ringtone is the
     * same clash the user complained about.
     */
    const val AUDIO_MODE_NORMAL = 0
    const val AUDIO_MODE_RINGTONE = 1
    const val AUDIO_MODE_IN_CALL = 2
    const val AUDIO_MODE_IN_COMMUNICATION = 3

    /** v2.10: true when [audioMode] means a call is active or incoming. */
    fun isCallActive(audioMode: Int): Boolean =
        audioMode == AUDIO_MODE_IN_CALL ||
            audioMode == AUDIO_MODE_IN_COMMUNICATION ||
            audioMode == AUDIO_MODE_RINGTONE

    /** v2.10: the "Call ke baad" gap presets (Settings cycles through these). */
    val CALL_DEFER_GAP_PRESETS_MILLIS: LongArray = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)

    /** v2.10: default gap — 1 minute, per the user's order. */
    const val DEFAULT_CALL_DEFER_GAP_MILLIS: Long = 60_000L

    /**
     * New Medicine: the gap between drops (Settings cycles through
     * these): 5 / 7 / 10 / 15 minutes, default 10 (the old Drops
     * Reminder used a fixed 30-minute gap).
     */
    val DOSE_INTERVAL_PRESETS_MILLIS: LongArray =
        longArrayOf(5L * 60_000L, 7L * 60_000L, 10L * 60_000L, 15L * 60_000L)

    /** New Medicine: default gap — 10 minutes. */
    const val DEFAULT_DOSE_INTERVAL_MILLIS: Long = 10L * 60_000L

    /**
     * New Medicine: hardens a stored gap into one of the presets, mirroring
     * [sanitizeCallDeferGap]. The Settings UI only ever writes presets, but
     * the value lives in the KV settings table where any stale/foreign
     * value could sit — anything off-preset falls back to the 10-minute
     * default (fail toward the prescribed schedule).
     */
    fun sanitizeDoseInterval(millis: Long): Long =
        if (millis in DOSE_INTERVAL_PRESETS_MILLIS) millis else DEFAULT_DOSE_INTERVAL_MILLIS

    /**
     * v2.10: hardens a stored gap into one of the presets. The Settings
     * UI only ever writes presets, but the value lives in the KV
     * settings table where any stale/foreign value could sit — a 0 or
     * negative gap would arm alarms in the past and spin the
     * deferral loop, so anything off-preset falls back to the 1-minute
     * default (fail toward alerting, as everywhere in this engine).
     */
    fun sanitizeCallDeferGap(millis: Long): Long =
        if (millis in CALL_DEFER_GAP_PRESETS_MILLIS) millis else DEFAULT_CALL_DEFER_GAP_MILLIS

    /**
     * v2.10: when a reminder fire finds a call active at [nowMillis],
     * the deferred re-fire is armed at [nowMillis] + [gapMillis]. The
     * gap is re-sanitized here as well: this is the alarm actually
     * handed to AlarmManager, so it must be a sane positive value even
     * if a caller passes a raw setting.
     */
    fun nextCallRecheckAt(nowMillis: Long, gapMillis: Long): Long =
        nowMillis + sanitizeCallDeferGap(gapMillis)

    /** v2.10: KV flag marking a dose whose reminder was call-deferred (render-time note). */
    fun callDeferKey(doseId: Long): String = "call_deferred_$doseId"

    /**
     * v2.10: AlarmManager request code for a dose's call-deferred
     * re-fire. Its own slot (never aliases the dose alarm
     * [alarmRequestCode] or the snooze watchdog
     * [snoozeWatchdogRequestCode]): the deferred fire runs the
     * identical receiver path as a dose fire, and re-checks the call
     * state itself — still in call → re-armed at now + gap,
     * self-correcting with no persistent listener.
     */
    fun callDeferredRequestCode(doseId: Long): Int = (0xCA11E00L + doseId).toInt()

    /** v2.10: how often the mid-ring poll checks the call state. */
    const val CALL_POLL_MILLIS: Long = 2_000L

    // ------------------------------------------- snooze watchdog (v2.4)

    /**
     * v2.4: how long after a snooze expiry its watchdog alarm fires. The
     * watchdog is the snooze expiry's second delivery chance: v2.3 gave
     * the expiry exactly one (the dose's one-shot slot, with the nag
     * flag deleted at snooze time), so a single lost/refused fire left
     * the dose in silence with nothing armed (live failure 2026-10-22,
     * TEST-REPORT §26). 2 minutes matches the ring cap: a healthy
     * primary ring has just ended (or is ending — the receiver's
     * ALREADY_RINGING decision covers the overlap) when it fires.
     */
    const val SNOOZE_WATCHDOG_DELAY_MILLIS: Long = 2L * 60 * 1000

    /** v2.4: when the watchdog for a snooze ending at [snoozeUntilMillis] fires. */
    fun snoozeWatchdogAt(snoozeUntilMillis: Long): Long =
        snoozeUntilMillis + SNOOZE_WATCHDOG_DELAY_MILLIS

    /**
     * v2.4: AlarmManager request code for a dose's snooze watchdog. The
     * watchdog PendingIntent deliberately shares (broadcast,
     * AlarmReceiver, ACTION_ALARM) with the dose alarm itself — the
     * watchdog fire must run the identical receiver path — so its code
     * must NEVER alias a dose alarm code ([alarmRequestCode], same
     * identity triple; extras are not part of PendingIntent identity).
     * The 0x100000 gap makes aliasing impossible for any real dose id
     * (it would need a dose id above one million).
     */
    fun snoozeWatchdogRequestCode(doseId: Long): Int = (0xE05E00L + doseId).toInt()

    fun planSession(
        type: SessionType,
        dayKey: String,
        startAtMillis: Long,
        settings: DoseSettings,
        specialAlreadyTakenToday: Boolean,
        zone: ZoneId,
        ignoreCutoff: Boolean = false,
    ): SessionPlan {
        val count = dropCountFor(type, settings)
        require(count >= 1) { "dropCount must be >= 1" }

        // The daily cutoff is an EVENING-session rule only (user spec):
        // MORNING and AFTERNOON sessions may start at any hour, so they never
        // hit the Conflict path — the user must be able to start them at night.
        //
        // v2.3: [ignoreCutoff] is set ONLY for a deferred evening start (one
        // that was already due but had to wait for the previous session —
        // user order 2026-10-22: "late ki wajah se cutoff aa jaye to koi
        // masla nahi, reminders dete rehna"). A deferred start runs the full
        // plan even past the cutoff. A FRESH start (auto or manual, nothing
        // deferred) passes false and keeps the exact v2.2 CONFLICT behavior.
        if (type == SessionType.EVENING && !ignoreCutoff) {
            val cutoffMillis = minutesOfDayToMillis(dayKey, settings.cutoffMinutesOfDay, zone)
            val lastDueAt = startAtMillis + (count - 1) * settings.doseIntervalMillis
            if (lastDueAt > cutoffMillis) {
                val fmt = DateTimeFormatter.ofPattern("h:mm a")
                val endLocal = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastDueAt), zone).format(fmt)
                val cutoffLocal = ZonedDateTime.ofInstant(Instant.ofEpochMilli(cutoffMillis), zone).format(fmt)
                val latest = latestStartMinutesOfDay(count, settings.doseIntervalMillis, settings.cutoffMinutesOfDay)
                val latestStr = "%02d:%02d".format(latest / 60, latest % 60)
                return SessionPlan.Conflict(
                    "This ${type.name.lowercase()} session would end at $endLocal, which is past " +
                        "your $cutoffLocal cutoff. The app will not shorten the " +
                        "${settings.doseIntervalMillis / 60000}-minute interval or skip drops to " +
                        "fit. Please start earlier (by $latestStr) or adjust the cutoff in Settings."
                )
            }
        }

        val doses = (1..count).map { n ->
            PlannedDose(
                dropNumber = n,
                kind = kindForDrop(type, n, settings, specialAlreadyTakenToday),
                dueAt = startAtMillis + (n - 1) * settings.doseIntervalMillis,
            )
        }
        return SessionPlan.Ok(doses)
    }

    /**
     * Kind for the Nth drop of a session — the single source of truth for the
     * special-drop rule, shared by session start (drop 1) and the chained
     * insert on every YES (drop N+1). Mirrors [planSession]'s rule: the
     * special drop lives only in MORNING sessions, only when enabled, only
     * when not already taken today, and only at
     * [DoseSettings.specialDropPosition].
     */
    fun kindForDrop(
        type: SessionType,
        dropNumber: Int,
        settings: DoseSettings,
        specialTaken: Boolean,
    ): DoseKind {
        val count = dropCountFor(type, settings)
        return if (type == SessionType.MORNING &&
            settings.specialDropEnabled &&
            !specialTaken &&
            settings.specialDropPosition in 1..count &&
            dropNumber == settings.specialDropPosition
        ) DoseKind.SPECIAL else DoseKind.REGULAR
    }

    /**
     * Applies YES ("Taken") for [dose] at [nowMillis].
     *
     * Idempotent: if the dose is not PENDING (already confirmed — e.g. a
     * double tap, or YES pressed both on the notification and in the app),
     * the dose is returned unchanged with [ConfirmResult.unchanged] = true and
     * no next dose is scheduled, so no duplicate completion record and no
     * duplicate alarm can ever be created.
     */
    fun confirmDose(
        dose: Dose,
        nowMillis: Long,
        settings: DoseSettings,
        isLastDrop: Boolean,
    ): ConfirmResult {
        if (dose.status != DoseStatus.PENDING) {
            return ConfirmResult(dose = dose, nextDueAt = null, unchanged = true)
        }
        val updated = dose.copy(status = DoseStatus.CONFIRMED, confirmedAt = nowMillis)
        // No drift: the next drop is anchored to the SAVED confirmation time.
        val nextDueAt = if (isLastDrop) null else nowMillis + settings.doseIntervalMillis
        return ConfirmResult(dose = updated, nextDueAt = nextDueAt, unchanged = false)
    }

    /**
     * Applies NO ("Not Yet") for [dose] at [nowMillis].
     *
     * The dose stays PENDING, the NO-retry counter grows, and the next
     * reminder for the SAME dose is armed at now + retryInterval. The next
     * drop's 30-minute countdown is NOT started — the caller must not schedule
     * anything for drop N+1 while drop N is pending.
     */
    fun declineDose(dose: Dose, nowMillis: Long, settings: DoseSettings): Dose {
        require(dose.status == DoseStatus.PENDING) { "Only a pending dose can be declined" }
        return dose.copy(
            retryCount = dose.retryCount + 1,
            nextRetryAt = nowMillis + settings.retryIntervalMillis,
        )
    }

    /** Dismissing/swiping away a notification changes nothing. */
    fun dismissWithoutConfirm(dose: Dose): Dose = dose

    /**
     * Whether a YES/NO tap on a dose may proceed at [nowMillis].
     *
     * A tap is accepted once the dose is due, with a 60-second grace for
     * tap timing after the reminder fires. Taps earlier than that are
     * accidental (the Dose screen shows the next PENDING drop minutes
     * before it is due) and must be refused with zero state change —
     * otherwise an early YES would shift the whole 30-minute chain.
     */
    fun canConfirm(dueAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis >= dueAtMillis - 60_000L

    /**
     * Whether a YES/NO tap on [dose] at [nowMillis] may proceed.
     * Only PENDING doses that are due (see [canConfirm]) qualify; the
     * caller must change nothing when this returns false.
     */
    fun canRespond(dose: Dose, nowMillis: Long): Boolean =
        dose.status == DoseStatus.PENDING && canConfirm(dose.dueAt, nowMillis)

    /**
     * Doses with real outcomes for the history list: CONFIRMED, MISSED and
     * CANCELLED are shown; PENDING future drops are hidden (they appear
     * once resolved). Progress math still uses the full plan.
     */
    fun resolvedDoses(doses: List<Dose>): List<Dose> =
        doses.filter { it.status != DoseStatus.PENDING }

    /** True when a pending dose is past dueAt + grace and should show OVERDUE. */
    fun isOverdue(dose: Dose, nowMillis: Long, settings: DoseSettings): Boolean =
        dose.status == DoseStatus.PENDING && nowMillis > dose.dueAt + settings.overdueGraceMillis

    /**
     * v2.0: whether a missed-drop nag may fire for [dose] at [nowMillis].
     *
     * A nag fires once the dose has been PENDING for at least one full
     * [DoseSettings.nagIntervalMillis] past its due time, and then repeats
     * every interval — [lastNagAt] is the last nag trigger (null when no
     * nag has fired yet for this dose). Non-pending doses and disabled
     * nagging never nag.
     */
    fun shouldNag(dose: Dose, nowMillis: Long, settings: DoseSettings, lastNagAt: Long?): Boolean =
        dose.status == DoseStatus.PENDING &&
            settings.nagEnabled &&
            nowMillis >= dose.dueAt + settings.nagIntervalMillis &&
            (lastNagAt == null || nowMillis >= lastNagAt + settings.nagIntervalMillis)

    /**
     * v2.3: snooze-aware [shouldNag]. Inside a snooze window the dose is
     * untouchable — no nag fires, however overdue it is. At/after the
     * snooze expiry the plain rule applies unchanged (nagging resumes if
     * the dose is still unanswered).
     */
    fun shouldNag(
        dose: Dose,
        nowMillis: Long,
        settings: DoseSettings,
        lastNagAt: Long?,
        snoozeUntil: Long?,
    ): Boolean =
        !isSnoozed(snoozeUntil, nowMillis) && shouldNag(dose, nowMillis, settings, lastNagAt)

    /**
     * v2.0: the next nag trigger for a dose due at [dueAtMillis], evaluated
     * at [nowMillis]. Anchored to the DUE TIME (not to the previous nag),
     * so a delayed fire can never shift the cadence — no drift. The first
     * nag is always exactly one interval after dueAt.
     */
    fun nextNagAt(dueAtMillis: Long, nowMillis: Long, nagIntervalMillis: Long): Long =
        dueAtMillis + ((maxOf(0L, nowMillis - dueAtMillis)) / nagIntervalMillis + 1) * nagIntervalMillis

    /**
     * Marks every PENDING dose whose due time is past [cutoffMillis] as MISSED.
     * Reminders are never silently pushed beyond the cutoff.
     *
     * Callers must pre-filter with [eveningCutoffCandidates]: the cutoff is an
     * EVENING-session rule only.
     */
    fun applyCutoff(doses: List<Dose>, cutoffMillis: Long): List<Dose> =
        doses.map { d ->
            if (d.status == DoseStatus.PENDING && d.dueAt > cutoffMillis) d.copy(status = DoseStatus.MISSED)
            else d
        }

    /**
     * The daily cutoff is an EVENING-session rule only (user spec): from
     * [doses] returns the subset the cutoff pass is allowed to mark MISSED.
     * Morning/afternoon doses are never touched by the cutoff, at any hour.
     * Doses whose session type is unknown are excluded (never auto-missed).
     *
     * v2.3: doses of an ACTIVE session are ALSO excluded (user order
     * 2026-10-22) — a running evening session's drops are never killed
     * merely for being past the cutoff; reminders keep firing until the
     * session completes. [sessionStatusOf] defaults to "unknown", which
     * keeps the pre-v2.3 behavior for callers that cannot look the status
     * up (unknown is NOT active, so the dose stays a candidate).
     */
    fun eveningCutoffCandidates(
        doses: List<Dose>,
        sessionTypeOf: (sessionId: Long) -> SessionType?,
        sessionStatusOf: (sessionId: Long) -> SessionStatus? = { null },
    ): List<Dose> = doses.filter {
        sessionTypeOf(it.sessionId) == SessionType.EVENING &&
            sessionStatusOf(it.sessionId) != SessionStatus.ACTIVE
    }

    /** Marks every still-PENDING dose of [dayKey] as MISSED (day rollover / session close). */
    fun finalizeDay(doses: List<Dose>, dayKey: String): List<Dose> =
        doses.map { d ->
            if (d.dayKey == dayKey && d.status == DoseStatus.PENDING) d.copy(status = DoseStatus.MISSED)
            else d
        }

    /**
     * Stable, unique AlarmManager request code for a dose.
     *
     * Exactly ONE code per dose on purpose: retries overwrite the same alarm
     * slot (PendingIntent.FLAG_UPDATE_CURRENT), so duplicate reminders for the
     * same dose are structurally impossible.
     */
    fun alarmRequestCode(doseId: Long): Int = (0xD05E00L + doseId).toInt()

    /** Distinct alarm slot for auto-starting a session (afternoon/evening). */
    fun sessionStartRequestCode(type: SessionType, dayKey: String): Int =
        (0x5E5500L + type.ordinal * 1000003L + dayKey.hashCode()).toInt()

    /** Human/diagnostic key identifying one dose's alarm slot. */
    fun alarmKey(dose: Dose): String = "dose:${dose.id}"

    /**
     * Reconciles the desired alarm set against persisted dose state.
     * Used on every app start, on BOOT_COMPLETED, and on TIME_SET /
     * TIMEZONE_CHANGED: the OS alarm table is unreliable across those events,
     * the database is the source of truth, so we recompute what must be
     * scheduled from the DB and let the caller apply the diff.
     */
    fun reconcile(
        doses: List<Dose>,
        nowMillis: Long,
        pastToleranceMillis: Long = 60_000L,
    ): ReconcileResult {
        val schedule = mutableListOf<AlarmSpec>()
        val cancel = mutableListOf<Long>()
        for (d in doses) {
            val triggerAt = d.nextReminderAt()
            if (triggerAt != null && triggerAt >= nowMillis - pastToleranceMillis) {
                schedule.add(
                    AlarmSpec(
                        doseId = d.id,
                        triggerAt = triggerAt,
                        requestCode = alarmRequestCode(d.id),
                        alarmKey = alarmKey(d),
                    )
                )
            } else if (d.status != DoseStatus.PENDING) {
                // Confirmed/missed doses must not keep an alarm behind.
                cancel.add(d.id)
            }
            // Pending doses whose reminder time already passed beyond tolerance
            // are left for the overdue path (shown as OVERDUE, never re-armed
            // with a shifted interval).
        }
        return ReconcileResult(schedule = schedule, cancelDoseIds = cancel)
    }

    /**
     * Whether a dose needs an overdue catch-up reminder RIGHT NOW.
     *
     * This is the exact complement of the skip branch in [reconcile]: a
     * PENDING dose whose reminder time already passed beyond tolerance was
     * deliberately NOT re-armed there. But when the alarm itself was lost
     * AFTER the due time (late update-install, slow reboot/reconcile, Doze
     * delay), no reminder ever fired at all — the dose would sit OVERDUE in
     * silence until the user happens to open the app. A catch-up reminder
     * fires one immediate alarm for that episode (the caller guards against
     * nagging with a per-dose flag).
     *
     * Pure; unit-tested.
     */
    fun isCatchupDue(
        dose: Dose,
        nowMillis: Long,
        pastToleranceMillis: Long = 60_000L,
    ): Boolean =
        dose.status == DoseStatus.PENDING &&
            (dose.nextReminderAt() ?: Long.MAX_VALUE) < nowMillis - pastToleranceMillis

    /**
     * v2.3: snooze-aware [isCatchupDue]. Inside a snooze window no catch-up
     * may fire (the snooze alarm owns the dose); at/after expiry the plain
     * rule applies unchanged.
     */
    fun isCatchupDue(
        dose: Dose,
        nowMillis: Long,
        pastToleranceMillis: Long = 60_000L,
        snoozeUntil: Long?,
    ): Boolean =
        !isSnoozed(snoozeUntil, nowMillis) && isCatchupDue(dose, nowMillis, pastToleranceMillis)

    /**
     * Validates the special-drop invariants across a day's doses:
     *  - a SPECIAL dose may only belong to a MORNING session,
     *  - at most one *live* (PENDING or CONFIRMED) SPECIAL dose per dayKey may
     *    exist — a CANCELLED or MISSED special frees the slot for a later
     *    session, it can never be taken twice,
     *  - at most one SPECIAL dose per dayKey may be CONFIRMED.
     * Returns a list of violation messages (empty = all good).
     */
    fun checkSpecialInvariants(
        doses: List<Dose>,
        sessions: Map<Long, DoseSession>,
    ): List<String> {
        val violations = mutableListOf<String>()
        val specials = doses.filter {
            it.kind == DoseKind.SPECIAL &&
                (it.status == DoseStatus.PENDING || it.status == DoseStatus.CONFIRMED)
        }
        for (s in specials) {
            val session = sessions[s.sessionId]
            if (session != null && session.type != SessionType.MORNING) {
                violations.add("Special drop (dose id=${s.id}) is in a ${session.type} session; it is only allowed in MORNING.")
            }
        }
        specials.groupBy { it.dayKey }.forEach { (day, list) ->
            if (list.size > 1) {
                violations.add("More than one special drop planned on $day (${list.size}); only one per day is allowed.")
            }
            val confirmed = list.count { it.status == DoseStatus.CONFIRMED }
            if (confirmed > 1) {
                violations.add("Special drop confirmed $confirmed times on $day; it must never be taken twice in one day.")
            }
        }
        return violations
    }

    /** True when the once-daily special drop is already CONFIRMED for [dayKey]. */
    fun isSpecialTakenToday(doses: List<Dose>, dayKey: String): Boolean =
        doses.any { it.dayKey == dayKey && it.kind == DoseKind.SPECIAL && it.status == DoseStatus.CONFIRMED }

    /**
     * True when a once-daily special drop is still "live" for [dayKey] — i.e.
     * PENDING or CONFIRMED. Mirrors the partial unique index
     * (`idx_special_once_per_day`, narrowed in DB v2): a CANCELLED or MISSED
     * special frees the slot, so it does not count as live.
     */
    fun isSpecialLiveToday(doses: List<Dose>, dayKey: String): Boolean =
        doses.any {
            it.dayKey == dayKey && it.kind == DoseKind.SPECIAL &&
                (it.status == DoseStatus.PENDING || it.status == DoseStatus.CONFIRMED)
        }

    /**
     * v1.8 migration helper: the dose row whose kind must be flipped to
     * SPECIAL when the default special position moved 8 -> 6 mid-session.
     * Returns the PENDING REGULAR dose at dropNumber 6, or null when there is
     * nothing to flip. The caller must verify no live special exists today
     * first ([isSpecialLiveToday]).
     */
    fun flipTargetForSpecialPosition(doses: List<Dose>, sessionId: Long): Dose? =
        doses.firstOrNull {
            it.sessionId == sessionId && it.dropNumber == 6 &&
                it.status == DoseStatus.PENDING && it.kind == DoseKind.REGULAR
        }

    /**
     * v1.8: a SAVED `specialDropPosition` of 8 (the old default) migrates to
     * 6. Returns the new value, or null when nothing should change (fresh
     * install, or the user explicitly picked another value).
     */
    fun migratedSpecialPosition(rawSaved: Int?): Int? =
        if (rawSaved == 8) 6 else null

    /**
     * v1.9: the user's corrected schedule is morning 7 / afternoon 6 /
     * evening 6 (was 8 / 7 / 7). Only the old defaults migrate; an
     * explicitly chosen value is never touched. Returns the migrated
     * triple, or null when nothing should change (fresh install — the new
     * defaults already apply — or the user picked other values).
     */
    fun migratedDropCounts(morning: Int?, afternoon: Int?, evening: Int?): Triple<Int, Int, Int>? {
        val nm = if (morning == 8) 7 else morning
        val na = if (afternoon == 7) 6 else afternoon
        val ne = if (evening == 7) 6 else evening
        if (nm == morning && na == afternoon && ne == evening) return null
        return Triple(nm ?: 7, na ?: 6, ne ?: 6)
    }

    /**
     * v1.10: runs one one-time migration in isolation. A throwing migration
     * is reported via [onError] and swallowed — it can never block later
     * migrations. Safe because every one-time migration is idempotent and
     * flag-guarded: the failed one simply retries on the next launch.
     */
    fun runMigrationSafely(
        tag: String,
        onError: (tag: String, t: Throwable) -> Unit,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            onError(tag, t)
        }
    }

    /**
     * Main-screen counters under STRICT CHAINING (v1.5): only confirmed and
     * chain-head rows exist, so [plannedTotal] (the session's/drop-count's
     * planned size) is passed explicitly instead of being derived from the
     * row count.
     * taken    = CONFIRMED;
     * missed   = MISSED;
     * pending  = PENDING and already due (needs attention now);
     * upcoming = plannedTotal minus everything accounted for above (drops
     *            not yet created by the chain — they appear only after the
     *            previous drop is taken).
     * CANCELLED doses are counted in `total` only (they belong to a stopped
     * session: neither taken nor missed, and they need no attention).
     */
    fun sessionProgress(doses: List<Dose>, nowMillis: Long, plannedTotal: Int): Progress {
        var taken = 0; var pending = 0; var missed = 0; var cancelled = 0
        for (d in doses) {
            when (d.status) {
                DoseStatus.CONFIRMED -> taken++
                DoseStatus.MISSED -> missed++
                DoseStatus.CANCELLED -> cancelled++
                DoseStatus.PENDING ->
                    if (d.nextReminderAt() != null && d.nextReminderAt()!! <= nowMillis) pending++
            }
        }
        val upcoming = maxOf(0, plannedTotal - taken - pending - missed - cancelled)
        return Progress(taken = taken, pending = pending, missed = missed, upcoming = upcoming, total = plannedTotal)
    }

    /**
     * Whole-day completion percentage: CONFIRMED doses over the day's
     * PLANNED total (sum of the started sessions' drop counts). Under strict
     * chaining the row count grows through the day, so the denominator must
     * be the plan, not the rows.
     */
    fun dayCompletionPercent(doses: List<Dose>, plannedTotal: Int): Int {
        if (plannedTotal == 0) return 0
        val done = doses.count { it.status == DoseStatus.CONFIRMED }
        return (done * 100) / plannedTotal
    }

    /** Planned start (epoch millis) for an auto-started session on [dayKey]. */
    fun plannedSessionStartMillis(
        type: SessionType,
        dayKey: String,
        settings: DoseSettings,
        zone: ZoneId,
    ): Long {
        val minutes = when (type) {
            SessionType.AFTERNOON -> settings.afternoonStartMinutesOfDay
            SessionType.EVENING -> settings.eveningStartMinutesOfDay
            SessionType.MORNING -> error("Morning session is started manually by the user")
        }
        return minutesOfDayToMillis(dayKey, minutes, zone)
    }

    /**
     * v2.11: the calendar day after [dayKey] ("yyyy-MM-dd"). Pure
     * LocalDate arithmetic — immune to the now+24h DST trap.
     */
    fun tomorrowDayKey(dayKey: String): String =
        LocalDate.parse(dayKey, DAY_KEY_FORMAT).plusDays(1).format(DAY_KEY_FORMAT)

    /** v2.11: one session-start alarm to arm. */
    data class SessionStartArm(
        val type: SessionType,
        val dayKey: String,
        val triggerAtMillis: Long,
    )

    /**
     * v2.11: the full desired session-start alarm set for [dayKey] and
     * tomorrow, derived from the CURRENT settings (never from default
     * constants). The Android glue executes it: [schedule] arms,
     * [cancel] drops the alarm in that slot. Both lists are disjoint
     * per (type, dayKey) — the caller must cancel before scheduling
     * is unnecessary because every slot is stable + FLAG_UPDATE_CURRENT,
     * but keeping them disjoint makes the plan total and testable.
     */
    data class SessionStartRearmPlan(
        val schedule: List<SessionStartArm>,
        val cancel: List<Pair<SessionType, String>>,
    )

    /**
     * v2.11: computes which afternoon/evening auto-start alarms should be
     * armed RIGHT NOW from [settings]. This is the single decision point
     * every arming path (reconcile, day rollover, settings change) must
     * go through — the bug it fixes (2026-10-29) was the Settings screen
     * saving a new start time without ever re-running this decision, so
     * the alarm kept the old trigger time.
     *
     * Rules, per (type):
     *  - auto-start disabled, or a session row already exists for that
     *    day → cancel that day's alarm (a disabled auto-start must not
     *    leave a stale alarm behind).
     *  - planned start still ahead today → arm today's slot at the
     *    SETTING's time.
     *  - planned start already passed today → cancel today's slot and
     *    arm TOMORROW's slot at the setting's time (the new time takes
     *    effect for tomorrow immediately — no waiting for midnight).
     *  - tomorrow's slot is always kept in sync with the setting (arm
     *    when enabled and no row exists yet, else cancel).
     *
     * [hasSessionRow] is injected so the pure core stays I/O-free.
     */
    fun sessionStartRearmPlan(
        dayKey: String,
        now: Long,
        settings: DoseSettings,
        zone: ZoneId,
        hasSessionRow: (SessionType, String) -> Boolean,
    ): SessionStartRearmPlan {
        val tomorrow = tomorrowDayKey(dayKey)
        val schedule = ArrayList<SessionStartArm>()
        val cancel = ArrayList<Pair<SessionType, String>>()
        for (type in listOf(SessionType.AFTERNOON, SessionType.EVENING)) {
            val enabled = if (type == SessionType.AFTERNOON) settings.autoStartAfternoon
            else settings.autoStartEvening
            // Today.
            val startAt = plannedSessionStartMillis(type, dayKey, settings, zone)
            if (enabled && !hasSessionRow(type, dayKey) && startAt > now) {
                schedule.add(SessionStartArm(type, dayKey, startAt))
            } else {
                cancel.add(type to dayKey)
            }
            // Tomorrow — always synced with the setting.
            if (enabled && !hasSessionRow(type, tomorrow)) {
                schedule.add(
                    SessionStartArm(
                        type, tomorrow,
                        plannedSessionStartMillis(type, tomorrow, settings, zone),
                    )
                )
            } else {
                cancel.add(type to tomorrow)
            }
        }
        return SessionStartRearmPlan(schedule, cancel)
    }

    // ------------------------------------------------------- wake detection

    /**
     * Wake-detection window derived from [settings]. A window with
     * start >= end is treated as empty (never prompts, never crashes).
     */
    fun wakeWindow(settings: DoseSettings): WakeWindow = WakeWindow(
        startMinutesOfDay = settings.wakeWindowStartMinutesOfDay,
        endMinutesOfDay = settings.wakeWindowEndMinutesOfDay,
        checkIntervalMillis = settings.wakeCheckIntervalMinutes * 60_000L,
    )

    /** True when [nowMillis] falls inside the morning wake window (local time). */
    fun isInWakeWindow(nowMillis: Long, settings: DoseSettings, zone: ZoneId): Boolean {
        val w = wakeWindow(settings)
        if (w.startMinutesOfDay >= w.endMinutesOfDay) return false
        val mod = minutesOfDay(nowMillis, zone)
        return mod >= w.startMinutesOfDay && mod < w.endMinutesOfDay
    }

    /** Epoch millis of the wake window start on [dayKey] (local time). */
    fun wakeWindowStartMillis(dayKey: String, settings: DoseSettings, zone: ZoneId): Long =
        minutesOfDayToMillis(dayKey, settings.wakeWindowStartMinutesOfDay, zone)

    /**
     * Decides whether a wake tick at [nowMillis] should push the
     * "Drops start karein?" prompt.
     *
     * Prompts iff ALL of these hold:
     *  - [nowMillis] is inside the wake window,
     *  - the morning session has NOT started today,
     *  - [lastDeviceUsedAt] (max `lastTimeUsed` across packages, app itself
     *    excluded) is within the last check interval — i.e. the phone was
     *    just used,
     *  - no prompt was shown yet for this tick ([lastPromptAt] is null or at
     *    least one check interval old), so a duplicated tick cannot stack a
     *    second notification.
     *
     * [lastDeviceUsedAt] == null means no usage signal is available (usage
     * access not granted or no data); the caller then uses
     * [shouldPromptWakeFallback] instead — this function returns false so the
     * two paths never both fire.
     */
    fun shouldPromptWake(
        nowMillis: Long,
        window: WakeWindow,
        lastDeviceUsedAt: Long?,
        morningStartedToday: Boolean,
        lastPromptAt: Long?,
        zone: ZoneId,
    ): Boolean {
        if (morningStartedToday) return false
        if (window.startMinutesOfDay >= window.endMinutesOfDay) return false
        val mod = minutesOfDay(nowMillis, zone)
        if (mod < window.startMinutesOfDay || mod >= window.endMinutesOfDay) return false
        if (lastDeviceUsedAt == null) return false
        val interval = window.checkIntervalMillis
        if (interval <= 0) return false
        if (lastPromptAt != null && nowMillis - lastPromptAt < interval) return false
        val age = nowMillis - lastDeviceUsedAt
        // Small future skew (clock adjustments) is tolerated; anything older
        // than one check interval means the phone was NOT just used.
        return age >= -60_000L && age <= interval
    }

    /**
     * Fallback path when usage access is NOT granted: prompt exactly once per
     * day, on the first wake tick at/after the window start, regardless of
     * usage detection. [fallbackPromptedDayKey] is the dayKey already prompted
     * (persisted by the caller); null when nothing was prompted yet.
     */
    fun shouldPromptWakeFallback(
        nowMillis: Long,
        window: WakeWindow,
        morningStartedToday: Boolean,
        fallbackPromptedDayKey: String?,
        dayKey: String,
        zone: ZoneId,
    ): Boolean {
        if (morningStartedToday) return false
        if (fallbackPromptedDayKey == dayKey) return false
        if (window.startMinutesOfDay >= window.endMinutesOfDay) return false
        val mod = minutesOfDay(nowMillis, zone)
        return mod >= window.startMinutesOfDay && mod < window.endMinutesOfDay
    }

    /**
     * Unified wake-prompt decision used by the repository's wake tick.
     *
     * - [force]=true (explicit "15 min baad" request): always prompt — the
     *   user asked to be reminded. (The caller already verified the morning
     *   session has not started.)
     * - Usage path: when usage access is granted AND a usage signal exists,
     *   prompt only if the phone was just used ([shouldPromptWake]).
     * - Fallback: whenever there is no usable usage signal (access denied
     *   OR granted-but-no-data-yet), prompt once per day on the first
     *   in-window tick ([shouldPromptWakeFallback]). This is the catch-up
     *   guarantee: if the window-start tick was missed for any reason, the
     *   first available tick inside the window still prompts.
     */
    fun decideWakePrompt(
        nowMillis: Long,
        window: WakeWindow,
        force: Boolean,
        hasUsageAccess: Boolean,
        lastDeviceUsedAt: Long?,
        morningStartedToday: Boolean,
        lastPromptAt: Long?,
        fallbackPromptedDayKey: String?,
        dayKey: String,
        zone: ZoneId,
    ): Boolean {
        if (morningStartedToday) return false
        if (force) return true
        if (window.startMinutesOfDay >= window.endMinutesOfDay) return false
        val mod = minutesOfDay(nowMillis, zone)
        if (mod < window.startMinutesOfDay || mod >= window.endMinutesOfDay) return false
        if (hasUsageAccess && lastDeviceUsedAt != null) {
            return shouldPromptWake(
                nowMillis, window, lastDeviceUsedAt,
                morningStartedToday = false, lastPromptAt, zone,
            )
        }
        return shouldPromptWakeFallback(
            nowMillis, window, morningStartedToday = false,
            fallbackPromptedDayKey, dayKey, zone,
        )
    }

    /**
     * When the next wake check should fire after a tick at [nowMillis]:
     *  - before the window: at the window start today,
     *  - inside the window: one check interval later (but if that would fall
     *    past the window end, the chain stops and resumes at tomorrow's
     *    window start),
     *  - after the window: at tomorrow's window start.
     */
    fun nextWakeCheckAt(nowMillis: Long, settings: DoseSettings, zone: ZoneId): Long {
        val dayKey = dayKey(nowMillis, zone)
        val ws = minutesOfDayToMillis(dayKey, settings.wakeWindowStartMinutesOfDay, zone)
        val we = minutesOfDayToMillis(dayKey, settings.wakeWindowEndMinutesOfDay, zone)
        val interval = settings.wakeCheckIntervalMinutes * 60_000L
        if (nowMillis < ws) return ws
        if (nowMillis < we && interval > 0) {
            val next = nowMillis + interval
            if (next < we) return next
        }
        val tomorrowKey = dayKey(startOfDayMillis(dayKey, zone) + 24L * 60 * 60 * 1000, zone)
        return minutesOfDayToMillis(tomorrowKey, settings.wakeWindowStartMinutesOfDay, zone)
    }

    /**
     * v2.5: where the wake chain goes once today's morning session HAS
     * started — tomorrow's window start, unconditionally. Before v2.5
     * the already-started paths cancelled the chain and armed nothing,
     * so the chain's survival depended entirely on an external re-arm
     * (process start / boot / DATE_CHANGED) landing the next day; on
     * 2026-10-23 none did and the whole morning passed with zero wake
     * evaluations. With this, every chain state rolls forward: there is
     * always exactly one future check armed.
     */
    fun nextWakeChainAfterMorningStarted(
        nowMillis: Long, settings: DoseSettings, zone: ZoneId
    ): Long {
        val dayKey = dayKey(nowMillis, zone)
        val tomorrowKey = dayKey(startOfDayMillis(dayKey, zone) + 24L * 60 * 60 * 1000, zone)
        return minutesOfDayToMillis(tomorrowKey, settings.wakeWindowStartMinutesOfDay, zone)
    }

    /**
     * v2.5: pure wake-chain health predicate for the reconcile
     * self-healing pass. The chain is healthy only when a check is
     * actually armed AND its recorded trigger is still in the future;
     * a disarmed chain, a missing trigger record, or a stale trigger
     * (its fire was lost) all need a re-arm. The last recorded wake
     * outcome is deliberately NOT an input — reality (the alarm table)
     * is validated, never the diary.
     */
    fun wakeChainNeedsRearm(
        chainArmed: Boolean, nextCheckAtMillis: Long?, nowMillis: Long
    ): Boolean =
        !chainArmed || nextCheckAtMillis == null || nextCheckAtMillis <= nowMillis

    /**
     * The wake notification's "Start karein" action starts the morning
     * session only when it has not started yet — a second tap (or a tap
     * racing the in-app Start button) is a no-op, so a double start can
     * never create a duplicate session.
     */
    fun shouldStartMorningFromWake(morningStartedToday: Boolean): Boolean = !morningStartedToday

    /**
     * One-time data repair for databases corrupted by the pre-v1.5
     * double-write bug (`startSession` pre-inserted the whole plan AND
     * `confirmDose` inserted another row for drop N+1 on every YES, with no
     * unique constraint — e.g. "Taken 6 • Upcoming 8" on an 8-drop session).
     *
     * Pure function of the session's dose rows; the repository executes the
     * returned deletions inside one transaction. Rules, in order:
     *  a. Group rows by dropNumber. A group containing a CONFIRMED row keeps
     *     the earliest confirmed row (min id); every other row in the group
     *     is deleted. A group with no CONFIRMED row keeps the newest
     *     (max id) PENDING row — the re-anchored chain row with the correct
     *     dueAt — and deletes the rest. Groups of only MISSED/CANCELLED
     *     rows are history and are kept untouched.
     *  b. maxC = highest confirmed dropNumber (0 when none). PENDING rows
     *     with dropNumber <= maxC are stale (their drop was already taken
     *     via another row) and are deleted.
     *  c. Of the surviving PENDING rows, only the chain head — the lowest
     *     dropNumber — may remain; the rest are deleted. A drop must never
     *     exist before the previous one is taken.
     * Only rows are ever deleted; nothing is inserted, so the once-daily
     * special partial unique index cannot be violated.
     */
    fun planDoseRepair(doses: List<Dose>): DoseRepairPlan {
        val deleteIds = mutableSetOf<Long>()
        val keptConfirmed = ArrayList<Dose>()
        val keptPending = ArrayList<Dose>()
        for ((_, group) in doses.groupBy { it.dropNumber }) {
            val confirmed = group.filter { it.status == DoseStatus.CONFIRMED }
            if (confirmed.isNotEmpty()) {
                val keep = confirmed.minByOrNull { it.id }!!
                keptConfirmed.add(keep)
                deleteIds.addAll(group.filter { it.id != keep.id }.map { it.id })
            } else {
                val pending = group.filter { it.status == DoseStatus.PENDING }
                if (pending.isNotEmpty()) {
                    val keep = pending.maxByOrNull { it.id }!!
                    keptPending.add(keep)
                    deleteIds.addAll(group.filter { it.id != keep.id }.map { it.id })
                }
                // Groups holding only MISSED/CANCELLED rows are history: kept.
            }
        }
        val maxConfirmedDrop = keptConfirmed.maxOfOrNull { it.dropNumber } ?: 0
        val stale = keptPending.filter { it.dropNumber <= maxConfirmedDrop }
        deleteIds.addAll(stale.map { it.id })
        keptPending.removeAll(stale.toSet())
        // Single chain head survives: the lowest dropNumber pending row.
        val head = keptPending.minByOrNull { it.dropNumber }
        keptPending.filter { it.id != head?.id }.forEach { deleteIds.add(it.id) }
        return DoseRepairPlan(deleteIds = deleteIds)
    }
}

/** Which dose rows a [ScheduleEngine.planDoseRepair] run must delete. */
data class DoseRepairPlan(val deleteIds: Set<Long>)
