package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * v2.5 — wake-chain self-healing (live failure, 2026-10-23).
 *
 * On 2026-10-22 the morning session genuinely started, so an 08:45 wake
 * evaluation concluded MORNING_ALREADY_STARTED — correctly — and
 * CANCELLED the wake chain, arming nothing. Every re-arm trigger after
 * that is external (process start / boot / DATE_CHANGED / settings
 * change); on the user's phone none of them landed for 2026-10-23, and
 * reconcileAlarms — the one pass that runs on every receiver fire and
 * process start — never validated the chain. Result: zero wake
 * evaluations all morning on 2026-10-23 ("Wake chain armed: NAHI",
 * "Last check: MORNING_ALREADY_STARTED 08:45" — yesterday's outcome),
 * and the "Drops start karein?" prompt never arrived.
 *
 * The fix pinned here:
 *  - once today's morning has started, the chain must roll forward to
 *    TOMORROW's window start instead of dying
 *    ([ScheduleEngine.nextWakeChainAfterMorningStarted]);
 *  - chain health is a pure predicate of observable state — armed?
 *    next check recorded? next check still in the future? — so
 *    reconcile can validate reality instead of trusting the last
 *    recorded outcome ([ScheduleEngine.wakeChainNeedsRearm]).
 */
class WakeChainSelfHealTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val settings = DoseSettings() // window 06:00-11:00, interval 15

    private fun at(dayKey: String, hour: Int, minute: Int): Long =
        ScheduleEngine.minutesOfDayToMillis(dayKey, hour * 60 + minute, zone)

    // --------------------------------------- next check after "started"

    @Test
    fun `morning started inside the window - next chain check is tomorrow window start`() {
        // The live poison moment: 2026-10-22 08:45 concluded ALREADY_STARTED.
        val now = at("2026-10-22", 8, 45)
        assertEquals(
            at("2026-10-23", 6, 0),
            ScheduleEngine.nextWakeChainAfterMorningStarted(now, settings, zone)
        )
    }

    @Test
    fun `morning started before the window - next chain check is tomorrow window start`() {
        val now = at("2026-10-22", 5, 0)
        assertEquals(
            at("2026-10-23", 6, 0),
            ScheduleEngine.nextWakeChainAfterMorningStarted(now, settings, zone)
        )
    }

    @Test
    fun `morning started after the window - next chain check is tomorrow window start`() {
        val now = at("2026-10-22", 17, 0)
        assertEquals(
            at("2026-10-23", 6, 0),
            ScheduleEngine.nextWakeChainAfterMorningStarted(now, settings, zone)
        )
    }

    @Test
    fun `morning started late evening - next chain check is tomorrow window start`() {
        val now = at("2026-10-22", 23, 30)
        assertEquals(
            at("2026-10-23", 6, 0),
            ScheduleEngine.nextWakeChainAfterMorningStarted(now, settings, zone)
        )
    }

    @Test
    fun `morning started on month end - tomorrow crosses the month boundary`() {
        val now = at("2026-10-31", 9, 0)
        assertEquals(
            at("2026-11-01", 6, 0),
            ScheduleEngine.nextWakeChainAfterMorningStarted(now, settings, zone)
        )
    }

    @Test
    fun `custom window start is honored for the rolled-forward check`() {
        val custom = settings.copy(wakeWindowStartMinutesOfDay = 5 * 60 + 30)
        val now = at("2026-10-22", 8, 45)
        assertEquals(
            at("2026-10-23", 5, 30),
            ScheduleEngine.nextWakeChainAfterMorningStarted(now, custom, zone)
        )
    }

    // ------------------------------------------------- chain health

    @Test
    fun `chain not armed always needs rearm`() {
        val now = at("2026-10-23", 8, 0)
        assertTrue(ScheduleEngine.wakeChainNeedsRearm(false, null, now))
        // Even with a future timestamp recorded, a missing PendingIntent
        // means no check will ever fire.
        assertTrue(ScheduleEngine.wakeChainNeedsRearm(false, now + 60_000L, now))
    }

    @Test
    fun `armed but no recorded next check needs rearm`() {
        val now = at("2026-10-23", 8, 0)
        assertTrue(ScheduleEngine.wakeChainNeedsRearm(true, null, now))
    }

    @Test
    fun `armed with a stale next check needs rearm`() {
        val now = at("2026-10-23", 8, 0)
        assertTrue(ScheduleEngine.wakeChainNeedsRearm(true, now - 1L, now))
        // A check due exactly now counts as stale: its fire should already
        // have been consumed and replaced by the evaluation it triggered.
        assertTrue(ScheduleEngine.wakeChainNeedsRearm(true, now, now))
    }

    @Test
    fun `armed with a future next check is healthy`() {
        val now = at("2026-10-23", 8, 0)
        assertFalse(ScheduleEngine.wakeChainNeedsRearm(true, now + 1L, now))
        assertFalse(ScheduleEngine.wakeChainNeedsRearm(true, now + 3_600_000L, now))
    }
}
