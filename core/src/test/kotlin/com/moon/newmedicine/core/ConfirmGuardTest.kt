package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * v1.4 accidental-YES guard + history filtering.
 *
 * The Dose screen shows the next PENDING drop minutes before it is due; an
 * accidental early YES would confirm it early and shift the whole 30-minute
 * chain. The repo refuses YES/NO taps before the dose is due (60s grace)
 * with zero state change. History lists only resolved drops.
 */
class ConfirmGuardTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val settings = DoseSettings()
    private val dayKey = "2026-10-19"
    private val dueAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone) // 09:00

    private fun pendingDose(id: Long = 1) = Dose(
        id = id, sessionId = 1, dropNumber = 2, dayKey = dayKey, dueAt = dueAt,
        status = DoseStatus.PENDING,
    )

    // ---------------------------------------------------------- canConfirm

    @Test
    fun `canConfirm - 13 minutes early is refused`() {
        assertFalse(ScheduleEngine.canConfirm(dueAt, dueAt - 13 * 60 * 1000))
    }

    @Test
    fun `canConfirm - 61 seconds early is refused`() {
        assertFalse(ScheduleEngine.canConfirm(dueAt, dueAt - 61_000))
    }

    @Test
    fun `canConfirm - exactly 60 seconds early is accepted (grace boundary)`() {
        assertTrue(ScheduleEngine.canConfirm(dueAt, dueAt - 60_000))
    }

    @Test
    fun `canConfirm - 30 seconds early is accepted inside grace`() {
        assertTrue(ScheduleEngine.canConfirm(dueAt, dueAt - 30_000))
    }

    @Test
    fun `canConfirm - at and after due is accepted`() {
        assertTrue(ScheduleEngine.canConfirm(dueAt, dueAt))
        assertTrue(ScheduleEngine.canConfirm(dueAt, dueAt + 5 * 60 * 1000))
    }

    // ---------------------------------------------------------- canRespond

    @Test
    fun `canRespond - pending dose 13 min early is refused`() {
        assertFalse(ScheduleEngine.canRespond(pendingDose(), dueAt - 13 * 60 * 1000))
    }

    @Test
    fun `canRespond - pending dose 30s early is accepted`() {
        assertTrue(ScheduleEngine.canRespond(pendingDose(), dueAt - 30_000))
    }

    @Test
    fun `canRespond - pending dose at and after due is accepted`() {
        assertTrue(ScheduleEngine.canRespond(pendingDose(), dueAt))
        assertTrue(ScheduleEngine.canRespond(pendingDose(), dueAt + 60_000))
    }

    @Test
    fun `canRespond - non-pending dose is never respondable`() {
        val confirmed = pendingDose().copy(status = DoseStatus.CONFIRMED, confirmedAt = dueAt)
        assertFalse(ScheduleEngine.canRespond(confirmed, dueAt + 60_000))
        val missed = pendingDose().copy(status = DoseStatus.MISSED)
        assertFalse(ScheduleEngine.canRespond(missed, dueAt + 60_000))
    }

    // --------------------------------- refusal contract (what the repo does)

    @Test
    fun `YES 13 min early is refused - dose stays PENDING, no next dose computed`() {
        val dose = pendingDose()
        val now = dueAt - 13 * 60 * 1000
        // The repo refuses before the engine is ever invoked:
        assertFalse(ScheduleEngine.canRespond(dose, now))
        // ...so the dose object is untouched: still PENDING, no timestamp,
        // and no next-due was ever computed for drop 3.
        assertEquals(DoseStatus.PENDING, dose.status)
        assertNull(dose.confirmedAt)
        assertEquals(0, dose.retryCount)
    }

    @Test
    fun `YES 30s early inside grace is accepted and anchors +10 min`() {
        val dose = pendingDose()
        val now = dueAt - 30_000
        assertTrue(ScheduleEngine.canRespond(dose, now))
        val res = ScheduleEngine.confirmDose(dose, now, settings, isLastDrop = false)
        assertFalse(res.notDueYet)
        assertFalse(res.unchanged)
        assertEquals(DoseStatus.CONFIRMED, res.dose.status)
        assertEquals(now, res.dose.confirmedAt)
        assertEquals(now + 10 * 60 * 1000, res.nextDueAt)
    }

    @Test
    fun `YES at and after due is accepted unchanged behavior`() {
        val atDue = ScheduleEngine.confirmDose(pendingDose(), dueAt, settings, isLastDrop = false)
        assertFalse(atDue.notDueYet)
        assertEquals(DoseStatus.CONFIRMED, atDue.dose.status)

        val late = ScheduleEngine.confirmDose(pendingDose(id = 2), dueAt + 7 * 60 * 1000, settings, isLastDrop = false)
        assertFalse(late.notDueYet)
        assertEquals(dueAt + 7 * 60 * 1000 + 10 * 60 * 1000, late.nextDueAt)
    }

    @Test
    fun `NO 10 min early is refused`() {
        val dose = pendingDose()
        assertFalse(ScheduleEngine.canRespond(dose, dueAt - 10 * 60 * 1000))
        // Refusal means no retry-count bump and no retry re-arming:
        assertEquals(0, dose.retryCount)
        assertNull(dose.nextRetryAt)
    }

    @Test
    fun `double YES stays idempotent`() {
        val dose = pendingDose()
        val first = ScheduleEngine.confirmDose(dose, dueAt, settings, isLastDrop = false)
        assertFalse(first.unchanged)
        val second = ScheduleEngine.confirmDose(first.dose, dueAt + 5_000, settings, isLastDrop = false)
        assertTrue(second.unchanged)
        assertNull(second.nextDueAt)
        assertEquals(dueAt, second.dose.confirmedAt)
    }

    // ---------------------------------------------------- history filtering

    @Test
    fun `resolvedDoses - 1 confirmed plus 7 pending shows 1 line`() {
        val confirmed = pendingDose(id = 1).copy(status = DoseStatus.CONFIRMED, confirmedAt = dueAt)
        val pending = (2..8).map { pendingDose(id = it.toLong()).copy(dropNumber = it) }
        val shown = ScheduleEngine.resolvedDoses(listOf(confirmed) + pending)
        assertEquals(1, shown.size)
        assertEquals(DoseStatus.CONFIRMED, shown[0].status)
    }

    @Test
    fun `resolvedDoses - missed and cancelled stay visible`() {
        val doses = listOf(
            pendingDose(id = 1).copy(status = DoseStatus.MISSED),
            pendingDose(id = 2).copy(status = DoseStatus.CANCELLED),
            pendingDose(id = 3), // PENDING -> hidden
        )
        val shown = ScheduleEngine.resolvedDoses(doses)
        assertEquals(2, shown.size)
        assertTrue(shown.all { it.status != DoseStatus.PENDING })
    }

    @Test
    fun `resolvedDoses - all pending shows nothing`() {
        val doses = (1..3).map { pendingDose(id = it.toLong()) }
        assertTrue(ScheduleEngine.resolvedDoses(doses).isEmpty())
    }
}
