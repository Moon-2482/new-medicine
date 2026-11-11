package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * Session-stop ("Session rok dein") semantics, pure engine + fake clock.
 *
 * The repository's `cancelSession` runs one transaction (session → CANCELLED,
 * every PENDING dose → CANCELLED, CONFIRMED untouched) and then drops the
 * cancelled doses' alarms. What the engine must guarantee around that:
 *  - CANCELLED doses need no reminders and must not keep alarms behind
 *    (reconcile lists them under cancelDoseIds, never under schedule),
 *  - CONFIRMED doses are never affected by a cancel,
 *  - a CANCELLED special drop does not count as "taken today", so a later
 *    session may still include the special drop,
 *  - CANCELLED doses are never auto-marked MISSED (cutoff / day rollover),
 *  - YES/NO on a CANCELLED dose is a no-op (confirm) or rejected (decline).
 */
class SessionCancelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val settings = DoseSettings()
    private val dayKey = "2026-10-19" // a Monday

    private fun startAt() = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)

    /** A morning session mid-flight: drops 1-2 CONFIRMED, 3-8 PENDING (8 = special). */
    private fun midFlightSession(): Pair<DoseSession, List<Dose>> {
        val start = startAt()
        val session = DoseSession(id = 1, type = SessionType.MORNING, dayKey = dayKey, startedAt = start)
        val doses = (1..8).map { n ->
            val base = Dose(
                id = n.toLong(), sessionId = session.id, dropNumber = n,
                kind = if (n == 8) DoseKind.SPECIAL else DoseKind.REGULAR,
                dayKey = dayKey, dueAt = start + (n - 1) * 30L * 60 * 1000,
            )
            if (n <= 2) base.copy(status = DoseStatus.CONFIRMED, confirmedAt = base.dueAt + 60_000L)
            else base
        }
        return session to doses
    }

    /** Mirrors DropsRepository.cancelSession's transaction (PENDING → CANCELLED). */
    private fun cancelDoses(doses: List<Dose>): List<Dose> =
        doses.map { d ->
            if (d.status == DoseStatus.PENDING) d.copy(status = DoseStatus.CANCELLED, nextRetryAt = null)
            else d
        }

    @Test
    fun `cancel keeps confirmed doses and cancels only pending ones`() {
        val (_, doses) = midFlightSession()
        val after = cancelDoses(doses)
        val confirmed = after.filter { it.status == DoseStatus.CONFIRMED }
        val cancelled = after.filter { it.status == DoseStatus.CANCELLED }
        assertEquals(2, confirmed.size)
        assertEquals(6, cancelled.size)
        // Confirmed timestamps are untouched.
        assertEquals(doses[0].confirmedAt, confirmed[0].confirmedAt)
        assertEquals(doses[1].confirmedAt, confirmed[1].confirmedAt)
        // No retry arm survives on a cancelled dose.
        assertTrue(cancelled.all { it.nextRetryAt == null })
        // The pending special drop is cancelled, not confirmed.
        val special = after.first { it.kind == DoseKind.SPECIAL }
        assertEquals(DoseStatus.CANCELLED, special.status)
        assertNull(special.confirmedAt)
    }

    @Test
    fun `sessionProgress counts cancelled doses in total only`() {
        val (_, doses) = midFlightSession()
        val after = cancelDoses(doses)
        // plannedTotal is explicit under strict chaining (row count grows
        // through the day); the fixture's 8 rows are the full plan here.
        val p = ScheduleEngine.sessionProgress(after, startAt() + 10 * 60 * 60 * 1000, 8)
        assertEquals(2, p.taken)
        assertEquals(0, p.pending)
        assertEquals(0, p.missed)
        assertEquals(0, p.upcoming)
        assertEquals(8, p.total)
        assertEquals(25, p.percent)
    }

    @Test
    fun `reconcile drops alarms for cancelled doses and schedules nothing for them`() {
        val (_, doses) = midFlightSession()
        val after = cancelDoses(doses)
        val now = startAt() + 5 * 60 * 1000 // drop 3 due long ago, drop 4+ upcoming
        val result = ScheduleEngine.reconcile(after, now)
        val cancelledIds = after.filter { it.status == DoseStatus.CANCELLED }.map { it.id }.toSet()
        val scheduledIds = result.schedule.map { it.doseId }.toSet()
        // Every cancelled dose must have its alarm cancelled...
        assertTrue(result.cancelDoseIds.containsAll(cancelledIds))
        // ...and none of them may be (re)scheduled.
        assertTrue(scheduledIds.intersect(cancelledIds).isEmpty())
        // Confirmed doses keep no alarm either.
        assertTrue(result.cancelDoseIds.containsAll(listOf(1L, 2L)))
    }

    @Test
    fun `cancelled special drop does not count as taken today`() {
        val (_, doses) = midFlightSession()
        val after = cancelDoses(doses)
        // The special was PENDING → CANCELLED, never confirmed: the slot is free.
        assertFalse(ScheduleEngine.isSpecialTakenToday(after, dayKey))
        // Sanity: a confirmed special still blocks.
        val taken = after.map {
            if (it.kind == DoseKind.SPECIAL) it.copy(status = DoseStatus.CONFIRMED, confirmedAt = it.dueAt)
            else it
        }
        assertTrue(ScheduleEngine.isSpecialTakenToday(taken, dayKey))
    }

    @Test
    fun `cutoff and day rollover never mark cancelled doses missed`() {
        val (_, doses) = midFlightSession()
        val after = cancelDoses(doses)
        val cutoff = ScheduleEngine.minutesOfDayToMillis(dayKey, 20 * 60, zone)
        val afterCutoff = ScheduleEngine.applyCutoff(after, cutoff)
        assertTrue(afterCutoff.none { it.status == DoseStatus.MISSED })
        val finalized = ScheduleEngine.finalizeDay(after, dayKey)
        assertTrue(finalized.none { it.status == DoseStatus.MISSED })
        assertEquals(6, finalized.count { it.status == DoseStatus.CANCELLED })
    }

    @Test
    fun `confirm on a cancelled dose is a no-op, decline is rejected`() {
        val (_, doses) = midFlightSession()
        val cancelled = cancelDoses(doses).first { it.dropNumber == 3 }
        val res = ScheduleEngine.confirmDose(cancelled, startAt() + 60_000L, settings, isLastDrop = false)
        assertTrue(res.unchanged)
        assertNull(res.nextDueAt)
        try {
            ScheduleEngine.declineDose(cancelled, startAt() + 60_000L, settings)
            fail("declineDose on a CANCELLED dose must throw")
        } catch (e: IllegalArgumentException) {
            // expected: only PENDING doses can be declined
        }
    }

    @Test
    fun `cancelled special plus a live special is not an invariant violation`() {
        val (session, doses) = midFlightSession()
        val after = cancelDoses(doses)
        // Simulate the later replacement session's live special the same day.
        val liveSpecial = Dose(
            id = 100, sessionId = 2, dropNumber = 8, kind = DoseKind.SPECIAL,
            dayKey = dayKey, dueAt = startAt() + 7 * 30 * 60 * 1000,
            status = DoseStatus.PENDING,
        )
        val sessions = mapOf(
            session.id to session,
            2L to DoseSession(id = 2, type = SessionType.MORNING, dayKey = dayKey, startedAt = startAt()),
        )
        val violations = ScheduleEngine.checkSpecialInvariants(after + liveSpecial, sessions)
        assertTrue("expected no violations, got: $violations", violations.isEmpty())
    }

    @Test
    fun `two confirmed specials in one day is still a violation`() {
        val (session, doses) = midFlightSession()
        val twoConfirmed = doses.map {
            if (it.kind == DoseKind.SPECIAL || it.dropNumber == 7)
                it.copy(kind = DoseKind.SPECIAL, status = DoseStatus.CONFIRMED, confirmedAt = it.dueAt)
            else it
        }
        val violations = ScheduleEngine.checkSpecialInvariants(
            twoConfirmed, mapOf(session.id to session)
        )
        assertTrue(violations.any { it.contains("never be taken twice") })
    }

    @Test
    fun `cancelled dose needs no reminder and is never overdue`() {
        val (_, doses) = midFlightSession()
        val cancelled = cancelDoses(doses).first { it.dropNumber == 3 }
        assertNull(cancelled.nextReminderAt())
        assertFalse(ScheduleEngine.isOverdue(cancelled, startAt() + 24 * 60 * 60 * 1000, settings))
    }
}
