package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * v2.11: the session-start-time disconnect (user report 2026-10-29).
 *
 * The complaint: the Settings buttons for afternoon/evening session start
 * times changed the button text AND the home screen (both read the saved
 * setting), but the auto-start alarm kept firing at the OLD time.
 *
 * Root cause, pinned by /tmp/repro_session_start.py against the shipped
 * source: SettingsFragment.updateSettings() saved the new time to the DB
 * but never re-ran the arming decision — DropsRepository.rearmSessionStarts
 * (the only armer of session-start alarms) ran solely from reconcileAlarms
 * (app start) and ensureDayRollover (midnight). The AlarmManager alarm
 * therefore kept its old trigger until the next start/rollover.
 *
 * The fix, under test here:
 *  1. The arming DECISION is the pure ScheduleEngine.sessionStartRearmPlan
 *     — one decision point for reconcile, rollover AND settings change —
 *     and it reads the time from the passed DoseSettings, never from a
 *     default constant.
 *  2. A settings change re-arms immediately (wired in updateSettings).
 *  3. Passed-today → tomorrow's slot is armed at the new time (no waiting
 *     for midnight); disabled auto-start cancels today AND tomorrow.
 */
class SessionStartTimeTest {

    private val zone = ZoneId.of("Asia/Karachi")
    private val dayKey = "2026-10-29"
    private val tomorrowKey = "2026-10-30"
    // 2026-10-29 10:00 PKT — before any plausible session start.
    private val morning = ScheduleEngine.minutesOfDayToMillis(dayKey, 10 * 60, zone)
    // 2026-10-29 18:00 PKT — after a 13:45 afternoon start, before evening.
    private val evening = ScheduleEngine.minutesOfDayToMillis(dayKey, 18 * 60, zone)

    private fun noRows(): (SessionType, String) -> Boolean = { _, _ -> false }

    // ------------------------------------------------------- tomorrowDayKey

    @Test
    fun `tomorrow of a regular day`() {
        assertEquals("2026-10-30", ScheduleEngine.tomorrowDayKey("2026-10-29"))
    }

    @Test
    fun `tomorrow across a month boundary`() {
        assertEquals("2026-11-01", ScheduleEngine.tomorrowDayKey("2026-10-31"))
    }

    @Test
    fun `tomorrow across a year boundary`() {
        assertEquals("2027-01-01", ScheduleEngine.tomorrowDayKey("2026-12-31"))
    }

    @Test
    fun `tomorrow across leap day`() {
        assertEquals("2024-02-29", ScheduleEngine.tomorrowDayKey("2024-02-28"))
    }

    // --------------------------------- planned time reads the SETTING

    @Test
    fun `planned afternoon start uses the setting not the 14-30 default`() {
        val s = DoseSettings(afternoonStartMinutesOfDay = 15 * 60 + 15) // 15:15
        val at = ScheduleEngine.plannedSessionStartMillis(SessionType.AFTERNOON, dayKey, s, zone)
        assertEquals(ScheduleEngine.minutesOfDayToMillis(dayKey, 15 * 60 + 15, zone), at)
        assertNotEquals(ScheduleEngine.minutesOfDayToMillis(dayKey, 14 * 60 + 30, zone), at)
    }

    @Test
    fun `planned evening start uses the setting not the 17-00 default`() {
        val s = DoseSettings(eveningStartMinutesOfDay = 18 * 60 + 45) // 18:45
        val at = ScheduleEngine.plannedSessionStartMillis(SessionType.EVENING, dayKey, s, zone)
        assertEquals(ScheduleEngine.minutesOfDayToMillis(dayKey, 18 * 60 + 45, zone), at)
        assertNotEquals(ScheduleEngine.minutesOfDayToMillis(dayKey, 17 * 60, zone), at)
    }

    // ------------------------------------------------- rearm plan: ahead

    @Test
    fun `new time still ahead today arms today and tomorrow at the new time`() {
        // The user's live case: afternoon moved 13:45 -> 15:15, changed at 10:00.
        val s = DoseSettings(afternoonStartMinutesOfDay = 15 * 60 + 15)
        val plan = ScheduleEngine.sessionStartRearmPlan(dayKey, morning, s, zone, noRows())
        val aftToday = plan.schedule.filter { it.type == SessionType.AFTERNOON && it.dayKey == dayKey }
        assertEquals(1, aftToday.size)
        assertEquals(
            ScheduleEngine.minutesOfDayToMillis(dayKey, 15 * 60 + 15, zone),
            aftToday[0].triggerAtMillis,
        )
        val aftTmr = plan.schedule.filter { it.type == SessionType.AFTERNOON && it.dayKey == tomorrowKey }
        assertEquals(1, aftTmr.size)
        assertEquals(
            ScheduleEngine.minutesOfDayToMillis(tomorrowKey, 15 * 60 + 15, zone),
            aftTmr[0].triggerAtMillis,
        )
        // The OLD 13:45 must not appear anywhere in the plan.
        val oldAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 13 * 60 + 45, zone)
        assertTrue(plan.schedule.none { it.triggerAtMillis == oldAt })
    }

    // ------------------------------------------------- rearm plan: passed

    @Test
    fun `new time already passed today cancels today and arms tomorrow`() {
        // Changed at 18:00 to an afternoon start of 13:45 (already passed).
        val s = DoseSettings(afternoonStartMinutesOfDay = 13 * 60 + 45)
        val plan = ScheduleEngine.sessionStartRearmPlan(dayKey, evening, s, zone, noRows())
        assertTrue(plan.cancel.contains(SessionType.AFTERNOON to dayKey))
        assertTrue(plan.schedule.none { it.type == SessionType.AFTERNOON && it.dayKey == dayKey })
        val aftTmr = plan.schedule.filter { it.type == SessionType.AFTERNOON && it.dayKey == tomorrowKey }
        assertEquals(1, aftTmr.size)
        assertEquals(
            ScheduleEngine.minutesOfDayToMillis(tomorrowKey, 13 * 60 + 45, zone),
            aftTmr[0].triggerAtMillis,
        )
    }

    // ------------------------------------------------- rearm plan: disabled

    @Test
    fun `disabling auto-start cancels today and tomorrow`() {
        val s = DoseSettings(autoStartAfternoon = false)
        val plan = ScheduleEngine.sessionStartRearmPlan(dayKey, morning, s, zone, noRows())
        assertTrue(plan.schedule.none { it.type == SessionType.AFTERNOON })
        assertTrue(plan.cancel.contains(SessionType.AFTERNOON to dayKey))
        assertTrue(plan.cancel.contains(SessionType.AFTERNOON to tomorrowKey))
        // Evening (still enabled) is unaffected.
        assertTrue(plan.schedule.any { it.type == SessionType.EVENING && it.dayKey == dayKey })
    }

    // ------------------------------------------------- rearm plan: rows exist

    @Test
    fun `session already started today cancels today but still arms tomorrow`() {
        val s = DoseSettings()
        val hasRow: (SessionType, String) -> Boolean = { t, dk ->
            t == SessionType.AFTERNOON && dk == dayKey
        }
        val plan = ScheduleEngine.sessionStartRearmPlan(dayKey, morning, s, zone, hasRow)
        assertTrue(plan.cancel.contains(SessionType.AFTERNOON to dayKey))
        assertTrue(plan.schedule.none { it.type == SessionType.AFTERNOON && it.dayKey == dayKey })
        assertTrue(plan.schedule.any { it.type == SessionType.AFTERNOON && it.dayKey == tomorrowKey })
    }

    // ------------------------------------------------- plan hygiene

    @Test
    fun `schedule and cancel are disjoint per type and day`() {
        val s = DoseSettings()
        val plan = ScheduleEngine.sessionStartRearmPlan(dayKey, evening, s, zone, noRows())
        val scheduledKeys = plan.schedule.map { it.type to it.dayKey }.toSet()
        val cancelledKeys = plan.cancel.toSet()
        assertTrue(scheduledKeys.intersect(cancelledKeys).isEmpty())
    }

    @Test
    fun `every type-day pair appears in exactly one list`() {
        val s = DoseSettings()
        val plan = ScheduleEngine.sessionStartRearmPlan(dayKey, morning, s, zone, noRows())
        for (type in listOf(SessionType.AFTERNOON, SessionType.EVENING)) {
            for (dk in listOf(dayKey, tomorrowKey)) {
                val inSchedule = plan.schedule.count { it.type == type && it.dayKey == dk }
                val inCancel = plan.cancel.count { it == (type to dk) }
                assertEquals("pair $type/$dk", 1, inSchedule + inCancel)
            }
        }
    }

    @Test
    fun `evening plan is independent of the afternoon setting`() {
        val s = DoseSettings(
            afternoonStartMinutesOfDay = 12 * 60, // passed by `morning`? no: 10:00 < 12:00, still ahead
            eveningStartMinutesOfDay = 19 * 60 + 30,
        )
        val plan = ScheduleEngine.sessionStartRearmPlan(dayKey, morning, s, zone, noRows())
        val eveToday = plan.schedule.single { it.type == SessionType.EVENING && it.dayKey == dayKey }
        assertEquals(
            ScheduleEngine.minutesOfDayToMillis(dayKey, 19 * 60 + 30, zone),
            eveToday.triggerAtMillis,
        )
    }
}
