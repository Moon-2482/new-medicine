package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test

/**
 * v2.4 regression: the snooze-expiry ring that never happened on the
 * user's device (live report 2026-10-22).
 *
 * Root cause (see TEST-REPORT §26): the v2.3 snooze-expiry reminder had
 * exactly ONE delivery chance — the dose's single one-shot alarm slot,
 * with the nag flag deleted at snooze time. If that one fire did not
 * produce a ring on the device (cold process / OEM background block on
 * the foreground-service start — every failure swallowed silently by
 * the receiver's blanket catch), the dose sat in silence: slot empty,
 * no nag flag, no actor scheduled to run again.
 *
 * v2.4 removes the single point of failure:
 *  1. the receiver's fire decision is extracted here as the pure
 *     [ScheduleEngine.doseFireDecision] so the whole live sequence is
 *     pinned by tests (due → RING, inside window → SNOOZED, at/after
 *     expiry → RING, resolved → STALE, already ringing → no re-ring);
 *  2. snoozing also arms an independent WATCHDOG alarm at
 *     [ScheduleEngine.snoozeWatchdogAt] on its own request-code slot —
 *     if the primary expiry fire is lost, the watchdog fire runs the
 *     identical receiver path and rings; nagging then resumes from it.
 */
class SnoozeWatchdogTest {

    private val dayKey = "2026-10-22"
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

    // -------------------------------------------- the live sequence, as
    // decisions: due -> snooze 10 min -> expiry. Every step must decide
    // exactly what the user was promised.

    @Test
    fun `live sequence - due fire decides RING`() {
        val d = dose()
        assertEquals(
            FireDecision.RING,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = null, nowMillis = due, alreadyRingingForDose = false),
        )
    }

    @Test
    fun `live sequence - a fire inside the snooze window decides SNOOZED`() {
        val d = dose()
        val until = due + 60_000L + 10 * 60_000L // snoozed 1 min after due, +10 min
        assertEquals(
            FireDecision.SNOOZED,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = until, nowMillis = due + 5 * 60_000L, alreadyRingingForDose = false),
        )
    }

    @Test
    fun `live sequence - the expiry fire decides RING (the v2_3 live failure)`() {
        val d = dose()
        val until = due + 60_000L + 10 * 60_000L
        // Exactly at expiry…
        assertEquals(
            FireDecision.RING,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = until, nowMillis = until, alreadyRingingForDose = false),
        )
        // …and after it.
        assertEquals(
            FireDecision.RING,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = until, nowMillis = until + 30_000L, alreadyRingingForDose = false),
        )
    }

    @Test
    fun `live sequence - watchdog fire while the expiry ring is still sounding decides ALREADY_RINGING`() {
        val d = dose()
        val until = due + 60_000L + 10 * 60_000L
        val watchdogAt = ScheduleEngine.snoozeWatchdogAt(until)
        assertEquals(
            FireDecision.ALREADY_RINGING,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = until, nowMillis = watchdogAt, alreadyRingingForDose = true),
        )
    }

    @Test
    fun `live sequence - watchdog fire after a LOST expiry fire decides RING`() {
        // The reported bug: the primary expiry fire produced no ring on
        // the device. The watchdog fire (same receiver path) must ring —
        // this is the second chance v2.3 did not have.
        val d = dose()
        val until = due + 60_000L + 10 * 60_000L
        val watchdogAt = ScheduleEngine.snoozeWatchdogAt(until)
        assertEquals(
            FireDecision.RING,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = until, nowMillis = watchdogAt, alreadyRingingForDose = false),
        )
    }

    @Test
    fun `a resolved dose decides STALE whatever the flags say`() {
        val until = due + 10 * 60_000L
        for (status in listOf(DoseStatus.CONFIRMED, DoseStatus.MISSED, DoseStatus.CANCELLED)) {
            assertEquals(
                "status $status",
                FireDecision.STALE,
                ScheduleEngine.doseFireDecision(status, snoozeUntil = until, nowMillis = due + 60_000L, alreadyRingingForDose = false),
            )
            assertEquals(
                "status $status past expiry",
                FireDecision.STALE,
                ScheduleEngine.doseFireDecision(status, snoozeUntil = null, nowMillis = until + 60_000L, alreadyRingingForDose = false),
            )
        }
    }

    @Test
    fun `snooze check wins over the already-ringing check inside the window`() {
        // A re-snooze landed while a ring was sounding: a stray slot fire
        // inside the NEW window is a snoozed early-return, not a re-ring.
        val d = dose()
        val until = due + 30 * 60_000L
        assertEquals(
            FireDecision.SNOOZED,
            ScheduleEngine.doseFireDecision(d.status, snoozeUntil = until, nowMillis = due + 60_000L, alreadyRingingForDose = true),
        )
    }

    // ------------------------------------------------------- watchdog math

    @Test
    fun `watchdog fires exactly 2 minutes after the snooze expiry`() {
        assertEquals(120_000L, ScheduleEngine.SNOOZE_WATCHDOG_DELAY_MILLIS)
        val until = due + 11 * 60_000L
        assertEquals(until + 120_000L, ScheduleEngine.snoozeWatchdogAt(until))
    }

    @Test
    fun `watchdog request codes are stable, per-dose and never alias a dose alarm slot`() {
        // The watchdog PendingIntent shares (broadcast, AlarmReceiver,
        // ACTION_ALARM) with the dose alarm, so its request code must
        // NEVER equal any dose's alarm code (extras are not part of
        // PendingIntent identity). Separation is by construction
        // (0x100000); pin it over a wide id range so a future base edit
        // cannot silently reintroduce an alias.
        var prev = Int.MIN_VALUE
        for (id in 1L..200_000L) {
            val w = ScheduleEngine.snoozeWatchdogRequestCode(id)
            assertEquals("watchdog code must be stable", w, ScheduleEngine.snoozeWatchdogRequestCode(id))
            assertTrue("watchdog codes must be per-dose distinct", w > prev)
            prev = w
            assertNotEquals(
                "watchdog($id) aliases its own dose alarm slot",
                ScheduleEngine.alarmRequestCode(id), w,
            )
        }
        // Cross-dose aliasing would need alarm(y) == watchdog(x) with
        // y = x + 0x100000 — far beyond any real dose id; pin the gap.
        assertEquals(
            0x100000,
            ScheduleEngine.snoozeWatchdogRequestCode(1L) - ScheduleEngine.alarmRequestCode(1L),
        )
    }

    // -------------------------------------------- nagging after the rescue

    @Test
    fun `nagging resumes after the expiry fire if the dose is still unanswered`() {
        val d = dose()
        val until = due + 60_000L + 10 * 60_000L
        val watchdogAt = ScheduleEngine.snoozeWatchdogAt(until)
        // The expiry (or watchdog) fire re-arms the nag chain via
        // armNag; the pure rule must agree a nag is due by watchdog time
        // (anchored to dueAt, snooze spent).
        assertTrue(
            ScheduleEngine.shouldNag(d, watchdogAt, settings, lastNagAt = null, snoozeUntil = until),
        )
        assertEquals(
            due + 2 * settings.nagIntervalMillis,
            ScheduleEngine.nextNagAt(d.dueAt, watchdogAt, settings.nagIntervalMillis),
        )
    }
}
