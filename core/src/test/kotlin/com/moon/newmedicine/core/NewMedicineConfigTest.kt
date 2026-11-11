package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * New Medicine v1.0: pins the new medicine's configuration, distinct from
 * the old Drops Reminder (7/6/6 drops, 30-minute gap, special drop #6).
 *
 * - 4 drops in EVERY session (morning / afternoon / evening), all REGULAR
 *   — no special drop exists in this app.
 * - 10-minute gap between drops, anchored to the actual YES time:
 *   the user's example — morning 8:00 YES → next due 8:10 → 8:20 → 8:30.
 * - Gap is a setting (presets 5 / 7 / 10 / 15 min, default 10).
 * - Session defaults: wake window 06:00–11:00, afternoon auto-start
 *   13:45, evening auto-start 17:00, evening cutoff 20:00.
 */
class NewMedicineConfigTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val settings = DoseSettings()
    private val dayKey = "2026-11-11" // a Wednesday

    @Test
    fun `defaults are 4 drops per session, 10 minute gap, no special drop`() {
        assertEquals(4, settings.morningDropCount)
        assertEquals(4, settings.afternoonDropCount)
        assertEquals(4, settings.eveningDropCount)
        assertEquals(10L * 60 * 1000, settings.doseIntervalMillis)
        assertFalse(settings.specialDropEnabled)
        assertEquals(13 * 60 + 45, settings.afternoonStartMinutesOfDay)
        assertEquals(17 * 60, settings.eveningStartMinutesOfDay)
        assertEquals(20 * 60, settings.cutoffMinutesOfDay)
        assertEquals(6 * 60, settings.wakeWindowStartMinutesOfDay)
        assertEquals(11 * 60, settings.wakeWindowEndMinutesOfDay)
        assertEquals(60_000L, settings.callDeferGapMillis)
    }

    @Test
    fun `dropCountFor is 4 for every session type`() {
        for (type in SessionType.values()) {
            assertEquals(4, ScheduleEngine.dropCountFor(type, settings))
        }
    }

    @Test
    fun `morning walkthrough - 8-00 YES then 8-10, 8-20, 8-30, all regular`() {
        val at = ScheduleEngine.minutesOfDayToMillis(dayKey, 8 * 60, zone)
        val plan = ScheduleEngine.planSession(
            SessionType.MORNING, dayKey, at, settings,
            specialAlreadyTakenToday = false, zone,
        ) as SessionPlan.Ok
        assertEquals(4, plan.doses.size)
        assertEquals(at, plan.doses[0].dueAt)
        assertEquals(at + 10 * 60 * 1000, plan.doses[1].dueAt)
        assertEquals(at + 20 * 60 * 1000, plan.doses[2].dueAt)
        assertEquals(at + 30 * 60 * 1000, plan.doses[3].dueAt)
        assertTrue(plan.doses.all { it.kind == DoseKind.REGULAR })

        // Chained YES: confirming drop 1 at 8:00 dues drop 2 at 8:10.
        val d1 = Dose(id = 1, sessionId = 1, dropNumber = 1, dayKey = dayKey, dueAt = at)
        val confirmed = ScheduleEngine.confirmDose(d1, at, settings, isLastDrop = false)
        assertEquals(at + 10 * 60 * 1000, confirmed.nextDueAt)
        // Confirming drop 4 completes the session — no drop 5.
        val d4 = Dose(id = 4, sessionId = 1, dropNumber = 4, dayKey = dayKey, dueAt = at + 30 * 60 * 1000)
        val last = ScheduleEngine.confirmDose(d4, at + 30 * 60 * 1000, settings, isLastDrop = true)
        assertNull(last.nextDueAt)
    }

    @Test
    fun `kindForDrop is always REGULAR - even in the morning, even at position 6`() {
        for (type in SessionType.values()) {
            for (n in 1..4) {
                assertEquals(
                    DoseKind.REGULAR,
                    ScheduleEngine.kindForDrop(type, n, settings, specialTaken = false),
                )
            }
        }
        // Even a hypothetical position-6 drop could never be special here.
        assertEquals(
            DoseKind.REGULAR,
            ScheduleEngine.kindForDrop(SessionType.MORNING, 6, settings, specialTaken = false),
        )
    }

    @Test
    fun `gap presets are 5-7-10-15 minutes and sanitize falls back to 10`() {
        assertArrayEquals(
            longArrayOf(5L * 60_000L, 7L * 60_000L, 10L * 60_000L, 15L * 60_000L),
            ScheduleEngine.DOSE_INTERVAL_PRESETS_MILLIS,
        )
        assertEquals(10L * 60_000L, ScheduleEngine.sanitizeDoseInterval(10L * 60_000L))
        assertEquals(5L * 60_000L, ScheduleEngine.sanitizeDoseInterval(5L * 60_000L))
        // The old 30-minute gap is not a preset: it falls back to 10.
        assertEquals(10L * 60_000L, ScheduleEngine.sanitizeDoseInterval(30L * 60_000L))
        assertEquals(10L * 60_000L, ScheduleEngine.sanitizeDoseInterval(0L))
        assertEquals(10L * 60_000L, ScheduleEngine.sanitizeDoseInterval(-1L))
    }

    @Test
    fun `evening session at 17-00 ends 17-30, well before the 20-00 cutoff`() {
        val at = ScheduleEngine.minutesOfDayToMillis(dayKey, 17 * 60, zone)
        val plan = ScheduleEngine.planSession(
            SessionType.EVENING, dayKey, at, settings,
            specialAlreadyTakenToday = false, zone,
        ) as SessionPlan.Ok
        val cutoff = ScheduleEngine.minutesOfDayToMillis(dayKey, settings.cutoffMinutesOfDay, zone)
        assertEquals(at + 30 * 60 * 1000, plan.doses.last().dueAt)
        assertTrue(plan.doses.last().dueAt < cutoff)
        // Latest start that still finishes by 20:00 is 19:30.
        assertEquals(
            19 * 60 + 30,
            ScheduleEngine.latestStartMinutesOfDay(4, settings.doseIntervalMillis, settings.cutoffMinutesOfDay),
        )
    }

    @Test
    fun `afternoon and evening plans are 4 regular drops at the 10 minute gap`() {
        for ((type, startHour) in listOf(
            SessionType.AFTERNOON to (13 * 60 + 45),
            SessionType.EVENING to (17 * 60),
        )) {
            val at = ScheduleEngine.minutesOfDayToMillis(dayKey, startHour, zone)
            val plan = ScheduleEngine.planSession(
                type, dayKey, at, settings,
                specialAlreadyTakenToday = false, zone,
            ) as SessionPlan.Ok
            assertEquals(4, plan.doses.size)
            assertTrue(plan.doses.all { it.kind == DoseKind.REGULAR })
            for (i in 1..3) {
                assertEquals(
                    at + i * 10 * 60 * 1000,
                    plan.doses[i].dueAt,
                )
            }
        }
    }
}
