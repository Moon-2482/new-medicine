package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test

/**
 * v2.0 missed-drop nagging + Skip: pure-engine coverage.
 *
 * The repo glue (armNag KV lifecycle, skipDose transaction choreography,
 * reconcile nag/catch-up interplay, the v2->v3 upgrade repair) needs
 * Android/SQLite and is mirrored in tests/db_v22_nag_skip.py.
 */
class NagSkipTest {

    private val interval = 10L * 60 * 1000 // default nag interval
    private val due = 1_700_000_000_000L

    private fun settings(nagEnabled: Boolean = true, nagInterval: Long = interval) =
        DoseSettings(nagEnabled = nagEnabled, nagIntervalMillis = nagInterval)

    private fun dose(status: DoseStatus = DoseStatus.PENDING, dueAt: Long = due) =
        Dose(
            id = 7L, sessionId = 3L, dropNumber = 2, kind = DoseKind.REGULAR,
            dayKey = "2026-10-19", dueAt = dueAt, status = status,
        )

    // ---------------------------------------------------------- shouldNag

    @Test
    fun `nag fires once the dose is overdue by one full interval`() {
        assertTrue(ScheduleEngine.shouldNag(dose(), due + interval, settings(), null))
    }

    @Test
    fun `exact boundary now == dueAt + interval nags`() {
        assertTrue(ScheduleEngine.shouldNag(dose(), due + interval, settings(), null))
    }

    @Test
    fun `no nag within the first interval after due`() {
        assertFalse(ScheduleEngine.shouldNag(dose(), due + interval - 1, settings(), null))
        assertFalse(ScheduleEngine.shouldNag(dose(), due, settings(), null))
    }

    @Test
    fun `second nag suppressed inside its interval`() {
        val lastNag = due + interval
        assertFalse(ScheduleEngine.shouldNag(dose(), lastNag + interval - 1, settings(), lastNag))
    }

    @Test
    fun `second nag fires after its interval`() {
        val lastNag = due + interval
        assertTrue(ScheduleEngine.shouldNag(dose(), lastNag + interval, settings(), lastNag))
    }

    @Test
    fun `non-pending doses never nag`() {
        for (s in listOf(DoseStatus.CONFIRMED, DoseStatus.MISSED, DoseStatus.CANCELLED)) {
            assertFalse(
                "status $s must not nag",
                ScheduleEngine.shouldNag(dose(status = s), due + 3 * interval, settings(), null),
            )
        }
    }

    @Test
    fun `nagEnabled=false never nags`() {
        assertFalse(
            ScheduleEngine.shouldNag(dose(), due + 5 * interval, settings(nagEnabled = false), null)
        )
    }

    @Test
    fun `custom interval is honored`() {
        val s = settings(nagInterval = 5L * 60 * 1000)
        assertFalse(ScheduleEngine.shouldNag(dose(), due + 4 * 60 * 1000, s, null))
        assertTrue(ScheduleEngine.shouldNag(dose(), due + 5 * 60 * 1000, s, null))
    }

    // ---------------------------------------------------------- nextNagAt

    @Test
    fun `nextNagAt first nag is exactly one interval after due`() {
        assertEquals(due + interval, ScheduleEngine.nextNagAt(due, due, interval))
        assertEquals(due + interval, ScheduleEngine.nextNagAt(due, due - 5 * 60 * 1000, interval))
    }

    @Test
    fun `nextNagAt anchors to dueAt with no drift`() {
        // due+15min -> the next 10-min boundary anchored at due is due+20.
        assertEquals(due + 2 * interval, ScheduleEngine.nextNagAt(due, due + 15 * 60 * 1000, interval))
        // Exactly on a boundary still moves one interval forward (that nag
        // is the one firing now).
        assertEquals(due + 3 * interval, ScheduleEngine.nextNagAt(due, due + 2 * interval, interval))
    }

    @Test
    fun `nextNagAt with a custom interval`() {
        val five = 5L * 60 * 1000
        assertEquals(due + 2 * five, ScheduleEngine.nextNagAt(due, due + 7 * 60 * 1000, five))
        assertEquals(due + five, ScheduleEngine.nextNagAt(due, due, five))
    }

    // -------------------------------------------------------- SkipResult

    @Test
    fun `SkipResult carries dose, nextDueAt, unchanged and defaults notDueYet to false`() {
        val d = dose()
        val r = SkipResult(dose = d, nextDueAt = due + 30 * 60 * 1000, unchanged = false)
        assertEquals(d, r.dose)
        assertEquals(due + 30 * 60 * 1000, r.nextDueAt)
        assertFalse(r.unchanged)
        assertFalse(r.notDueYet)
        val refused = SkipResult(dose = d, nextDueAt = null, unchanged = false, notDueYet = true)
        assertTrue(refused.notDueYet)
        assertNull(refused.nextDueAt)
        val stale = SkipResult(dose = d, nextDueAt = null, unchanged = true)
        assertTrue(stale.unchanged)
    }

    @Test
    fun `DoseSettings nag defaults are enabled with a 10-minute interval`() {
        val s = DoseSettings()
        assertTrue(s.nagEnabled)
        assertEquals(10L * 60 * 1000, s.nagIntervalMillis)
    }
}
