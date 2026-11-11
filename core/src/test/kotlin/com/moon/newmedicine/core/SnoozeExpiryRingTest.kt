package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test

/**
 * v2.9 regression: the snooze-expiry ring that stayed quiet on the
 * user's device (live report 2026-10-24).
 *
 * What the user did: snoozed a due drop for 30 minutes. At expiry
 * the app did NOT ring out loud — only a small quiet notification
 * appeared saying the time had passed, which he noticed 3 minutes
 * late. Expected: the FULL alarm ring exactly like a fresh dose
 * fire, plus nag re-arming.
 *
 * What the code investigation proved (see TEST-REPORT §31):
 *  - the expiry DECISION was never the defect: [ScheduleEngine
 *    .doseFireDecision] returns RING at/after expiry, and both the
 *    primary expiry alarm and the +2 min watchdog are exact alarms
 *    to the identical receiver path — the watchdog's output class
 *    is the same as the primary's, by construction;
 *  - the defect class is the ring START: the only in-code routes
 *    to the observed output (small plain note, no audible ring)
 *    are a refused foreground-service start (receiver posts the
 *    plain note) and an in-service start failure (v2.8 fallback
 *    posts the plain note). The watchdog re-runs the identical
 *    fragile step, so a systematic refusal at that moment defeats
 *    BOTH chances — and after that nothing retried the ring
 *    promptly (next chance: the nag, up to 10 minutes later, same
 *    step).
 *
 * v2.9 pins the contract in pure form ([ScheduleEngine
 * .doseFireOutput]: expiry RING + started = FULL_RING, RING +
 * refused = FALLBACK_NOTE — never silence) and bounds the
 * receiver-side recovery to one prompt retry ([ScheduleEngine
 * .ringRetryAllowed]) before the fallback note.
 */
class SnoozeExpiryRingTest {

    private val dayKey = "2026-10-24"
    // A dose due at T0 in the fake timeline.
    private val due = 1_792_656_000_000L
    private val settings = DoseSettings()

    private fun dose(
        status: DoseStatus = DoseStatus.PENDING,
        dueAt: Long = due,
        nextRetryAt: Long? = null,
    ) = Dose(
        id = 7L, sessionId = 3L, dropNumber = 2, kind = DoseKind.REGULAR,
        dayKey = dayKey, dueAt = dueAt, status = status, nextRetryAt = nextRetryAt,
    )

    /**
     * Mirrors the receiver's RING branch step for step: first ring
     * attempt; on refusal, at most one retry ([ScheduleEngine
     * .ringRetryAllowed]); the output is decided by [ScheduleEngine
     * .doseFireOutput]. [outcomes] is the scripted start result per
     * attempt (true = service start accepted).
     */
    private class FakeRingAttempt(private val outcomes: List<Boolean>) {
        var calls = 0
            private set
        var retriesUsed = 0
            private set

        fun run(): FireOutput {
            var started = outcomes.getOrElse(calls++) { false }
            while (!started && ScheduleEngine.ringRetryAllowed(retriesUsed)) {
                retriesUsed++
                started = outcomes.getOrElse(calls++) { false }
            }
            return ScheduleEngine.doseFireOutput(FireDecision.RING, started)
        }
    }

    // -------------------------------------------- the live sequence, as
    // decisions + outputs: due -> snooze 30 min -> expiry.

    @Test
    fun `live sequence - primary expiry fire outputs FULL_RING when the ring starts`() {
        val d = dose()
        val until = due + 60_000L + 30 * 60_000L // snoozed 1 min after due, +30 min
        // Exactly at expiry…
        assertEquals(
            FireDecision.RING,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = until, nowMillis = until, alreadyRingingForDose = false),
        )
        assertEquals(
            FireOutput.FULL_RING,
            ScheduleEngine.doseFireOutput(FireDecision.RING, ringStarted = true),
        )
        // …and after it.
        assertEquals(
            FireDecision.RING,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = until, nowMillis = until + 30_000L, alreadyRingingForDose = false),
        )
        assertEquals(
            FireOutput.FULL_RING,
            ScheduleEngine.doseFireOutput(FireDecision.RING, ringStarted = true),
        )
    }

    @Test
    fun `live sequence - watchdog fire after a lost primary outputs FULL_RING when the ring starts`() {
        val d = dose()
        val until = due + 60_000L + 30 * 60_000L
        val watchdogAt = ScheduleEngine.snoozeWatchdogAt(until)
        assertEquals(
            FireDecision.RING,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = until, nowMillis = watchdogAt, alreadyRingingForDose = false),
        )
        assertEquals(
            FireOutput.FULL_RING,
            ScheduleEngine.doseFireOutput(FireDecision.RING, ringStarted = true),
        )
    }

    @Test
    fun `live sequence - a fire inside the snooze window stays silent and re-arms`() {
        val d = dose()
        val until = due + 60_000L + 30 * 60_000L
        assertEquals(
            FireDecision.SNOOZED,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = until, nowMillis = due + 5 * 60_000L, alreadyRingingForDose = false),
        )
        assertEquals(
            FireOutput.SILENT_REARM,
            ScheduleEngine.doseFireOutput(FireDecision.SNOOZED, ringStarted = false),
        )
    }

    // ------------------------------------------------------- the failure
    // class: refused ring start must retry once, then fall back — never
    // silence.

    @Test
    fun `refused start retries once and a transient refusal still rings`() {
        val attempt = FakeRingAttempt(listOf(false, true))
        assertEquals(FireOutput.FULL_RING, attempt.run())
        assertEquals(2, attempt.calls)
        assertEquals(1, attempt.retriesUsed)
    }

    @Test
    fun `systematically refused start falls back to the plain note, never silence`() {
        val attempt = FakeRingAttempt(listOf(false, false))
        assertEquals(FireOutput.FALLBACK_NOTE, attempt.run())
        assertEquals(2, attempt.calls) // first attempt + exactly one retry
        assertEquals(1, attempt.retriesUsed)
    }

    @Test
    fun `successful first start never retries`() {
        val attempt = FakeRingAttempt(listOf(true))
        assertEquals(FireOutput.FULL_RING, attempt.run())
        assertEquals(1, attempt.calls)
        assertEquals(0, attempt.retriesUsed)
    }

    @Test
    fun `retry rule is bounded to exactly one retry`() {
        assertTrue(ScheduleEngine.ringRetryAllowed(retriesUsed = 0))
        assertFalse(ScheduleEngine.ringRetryAllowed(retriesUsed = 1))
        assertFalse(ScheduleEngine.ringRetryAllowed(retriesUsed = 2))
    }

    @Test
    fun `retry delay is short - well before the watchdog second chance`() {
        assertTrue(ScheduleEngine.RING_RETRY_DELAY_MILLIS > 0)
        assertTrue(ScheduleEngine.RING_RETRY_DELAY_MILLIS < ScheduleEngine.SNOOZE_WATCHDOG_DELAY_MILLIS)
        assertEquals(5_000L, ScheduleEngine.RING_RETRY_DELAY_MILLIS)
    }

    // ------------------------------------------------------- total mapping

    @Test
    fun `output mapping is total - every decision maps to a visible outcome or an explicit silent re-arm`() {
        assertEquals(
            FireOutput.FULL_RING,
            ScheduleEngine.doseFireOutput(FireDecision.RING, ringStarted = true),
        )
        assertEquals(
            FireOutput.FALLBACK_NOTE,
            ScheduleEngine.doseFireOutput(FireDecision.RING, ringStarted = false),
        )
        assertEquals(
            FireOutput.SILENT_REARM,
            ScheduleEngine.doseFireOutput(FireDecision.SNOOZED, ringStarted = false),
        )
        assertEquals(
            FireOutput.CANCEL_TRAY,
            ScheduleEngine.doseFireOutput(FireDecision.STALE, ringStarted = false),
        )
        assertEquals(
            FireOutput.KEEP_RINGING,
            ScheduleEngine.doseFireOutput(FireDecision.ALREADY_RINGING, ringStarted = true),
        )
    }

    @Test
    fun `resolved dose at expiry is stale, never a ring`() {
        val until = due + 30 * 60_000L
        for (status in listOf(DoseStatus.CONFIRMED, DoseStatus.MISSED, DoseStatus.CANCELLED)) {
            assertEquals(
                "status $status",
                FireDecision.STALE,
                ScheduleEngine.doseFireDecision(status, snoozeUntil = until, nowMillis = until, alreadyRingingForDose = false),
            )
            assertEquals(
                "status $status",
                FireOutput.CANCEL_TRAY,
                ScheduleEngine.doseFireOutput(FireDecision.STALE, ringStarted = false),
            )
        }
    }

    // ------------------------------------------------------- nag + mode at
    // expiry: the ring must behave like a fresh dose fire.

    @Test
    fun `nagging resumes at expiry if the dose is still unanswered`() {
        val d = dose()
        val until = due + 60_000L + 30 * 60_000L
        // At expiry the snooze is spent, so the plain nag rule applies.
        assertTrue(
            ScheduleEngine.shouldNag(d, until, settings, lastNagAt = null, snoozeUntil = until),
        )
        // Anchored to dueAt, never drifting with the snooze:
        // 31 min past due = 3 full intervals + 1 = the 4th.
        assertEquals(
            due + 4 * settings.nagIntervalMillis,
            ScheduleEngine.nextNagAt(d.dueAt, until, settings.nagIntervalMillis),
        )
    }

    @Test
    fun `in NORMAL ringer mode the expiry ring is the loud looping path, not a one-shot ding`() {
        // This is the perceptual difference the user reported: a plain
        // fallback note on the sounding channel is a single ding, while
        // the full ring loops the sound. The contract is FULL_RING in
        // NORMAL mode — the mode decision itself is unchanged (v2.6).
        assertEquals(
            RingBehavior.SOUND_AND_VIBRATE,
            ScheduleEngine.ringBehaviorFor(2), // AudioManager.RINGER_MODE_NORMAL
        )
        assertEquals(
            RingBehavior.VIBRATE_ONLY,
            ScheduleEngine.ringBehaviorFor(1), // RINGER_MODE_VIBRATE
        )
        assertEquals(
            RingBehavior.VISUAL_ONLY,
            ScheduleEngine.ringBehaviorFor(0), // RINGER_MODE_SILENT
        )
    }
}
