package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * v1.5 one-time data repair ([ScheduleEngine.planDoseRepair]) for the
 * pre-v1.5 double-write corruption: `startSession` pre-inserted the whole
 * plan AND `confirmDose` inserted another row for drop N+1 on every YES
 * (no unique constraint on sessionId+dropNumber), so each YES could hit a
 * different duplicate row — e.g. the "Taken 6 • Pending 0 • Upcoming 8" on
 * an 8-drop session.
 *
 * The fixture below mirrors that exact shape, scaled to New
 * Medicine's 4-drop session: 4 planned rows + 6 re-anchored rows from
 * 6 YES taps across drops 1-3 (each drop confirmed twice, via its
 * planned row and via its re-anchored row).
 */
class RepairTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val settings = DoseSettings()
    private val dayKey = "2026-10-19"

    private fun startAt() = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)

    private fun dose(
        id: Long, dropNumber: Int, status: DoseStatus,
        dueAt: Long, confirmedAt: Long? = null, kind: DoseKind = DoseKind.REGULAR,
    ) = Dose(
        id = id, sessionId = 1, dropNumber = dropNumber, kind = kind,
        dayKey = dayKey, dueAt = dueAt, status = status, confirmedAt = confirmedAt,
    )

    /**
     * The corrupted fixture (10 rows, 10-minute gap):
     *  - planned rows: d1..d4, ids 1..4
     *  - YES id1 -> id1 CONFIRMED, re-anchored d2 (id9)
     *  - YES id9 -> id9 CONFIRMED, re-anchored d3 (id10)
     *  - YES id2 (planned) -> id2 CONFIRMED, re-anchored d3 (id11)
     *  - YES id10 -> id10 CONFIRMED, re-anchored d4 (id12)
     *  - YES id3 (planned) -> id3 CONFIRMED, re-anchored d4 (id13)
     *  - YES id11 -> id11 CONFIRMED, re-anchored d4 (id14)
     * => 6 CONFIRMED (d1,d2,d2,d3,d3,d3), 4 PENDING.
     */
    private fun corruptedFixture(): MutableList<Dose> {
        val t = startAt()
        val t1 = t + 2 * 60 * 1000L // YES on drop 1
        val t9 = t1 + 5 * 60 * 1000L // YES on re-anchored drop 2
        val t2 = t + 15 * 60 * 1000L // YES on planned drop 2
        val t10 = t9 + 5 * 60 * 1000L // YES on re-anchored drop 3 (id10)
        val t3 = t + 25 * 60 * 1000L // YES on planned drop 3
        val t11 = t2 + 5 * 60 * 1000L // YES on re-anchored drop 3 (id11)
        return mutableListOf(
            // Planned rows (pre-v1.5 bulk insert).
            dose(1, 1, DoseStatus.CONFIRMED, t, t1),
            dose(2, 2, DoseStatus.CONFIRMED, t + 10 * 60 * 1000, t2),
            dose(3, 3, DoseStatus.CONFIRMED, t + 20 * 60 * 1000, t3),
            dose(4, 4, DoseStatus.PENDING, t + 30 * 60 * 1000),
            // Re-anchored rows (one per YES).
            dose(9, 2, DoseStatus.CONFIRMED, t1 + 10 * 60 * 1000, t9),
            dose(10, 3, DoseStatus.CONFIRMED, t9 + 10 * 60 * 1000, t10),
            dose(11, 3, DoseStatus.CONFIRMED, t2 + 10 * 60 * 1000, t11),
            dose(12, 4, DoseStatus.PENDING, t10 + 10 * 60 * 1000),
            dose(13, 4, DoseStatus.PENDING, t3 + 10 * 60 * 1000),
            dose(14, 4, DoseStatus.PENDING, t11 + 10 * 60 * 1000),
        )
    }

    private fun applyPlan(rows: MutableList<Dose>, plan: DoseRepairPlan): List<Dose> {
        rows.removeAll { it.id in plan.deleteIds }
        return rows
    }

    @Test
    fun `repair converges the 10-row corruption to 3 confirmed plus the chain head`() {
        val rows = corruptedFixture()
        assertEquals(6, rows.count { it.status == DoseStatus.CONFIRMED })
        assertEquals(4, rows.count { it.status == DoseStatus.PENDING })

        val plan = ScheduleEngine.planDoseRepair(rows)
        val survivors = applyPlan(rows, plan)

        // 3 CONFIRMED (drops 1-3), 1 PENDING (drop 4) — derived, not hardcoded.
        assertEquals(3, survivors.count { it.status == DoseStatus.CONFIRMED })
        assertEquals(1, survivors.count { it.status == DoseStatus.PENDING })
        assertEquals(setOf(1, 2, 3), survivors.filter { it.status == DoseStatus.CONFIRMED }.map { it.dropNumber }.toSet())
        val head = survivors.single { it.status == DoseStatus.PENDING }
        assertEquals(4, head.dropNumber)
        // The chain head is the newest re-anchored drop-4 row: its dueAt is
        // anchored to a drop-3 confirmation + 10 min (from the pre-repair
        // rows — the row it was created from may itself be a duplicate).
        assertEquals(14L, head.id)
        val drop3Confirms = corruptedFixture().filter { it.dropNumber == 3 }.mapNotNull { it.confirmedAt }
        assertTrue(
            "chain head dueAt must anchor to a drop-3 confirmation + 30 min",
            drop3Confirms.any { it + settings.doseIntervalMillis == head.dueAt },
        )
        // Zero duplicate dropNumbers survive.
        assertEquals(survivors.size, survivors.map { it.dropNumber }.toSet().size)
    }

    @Test
    fun `repair on a fresh 4-planned session keeps only drop 1`() {
        val t = startAt()
        val rows = mutableListOf<Dose>()
        for (n in 1..4) {
            rows.add(dose(n.toLong(), n, DoseStatus.PENDING, t + (n - 1) * 10 * 60 * 1000))
        }
        val survivors = applyPlan(rows, ScheduleEngine.planDoseRepair(rows))
        assertEquals(1, survivors.size)
        assertEquals(1, survivors.single().dropNumber)
        assertEquals(DoseStatus.PENDING, survivors.single().status)
    }

    @Test
    fun `repair is a no-op on a clean chained state`() {
        val t = startAt()
        val rows = mutableListOf(
            dose(1, 1, DoseStatus.CONFIRMED, t, t + 60_000),
            dose(2, 2, DoseStatus.CONFIRMED, t + 30 * 60 * 1000, t + 31 * 60 * 1000),
            dose(3, 3, DoseStatus.CONFIRMED, t + 60 * 60 * 1000, t + 61 * 60 * 1000),
            dose(4, 4, DoseStatus.PENDING, t + 91 * 60 * 1000),
        )
        val plan = ScheduleEngine.planDoseRepair(rows)
        assertTrue("clean chained state must need no deletions", plan.deleteIds.isEmpty())
        // Idempotent: repairing the repaired fixture again deletes nothing.
        val repaired = applyPlan(corruptedFixture(), ScheduleEngine.planDoseRepair(corruptedFixture()))
        assertTrue(ScheduleEngine.planDoseRepair(repaired).deleteIds.isEmpty())
    }

    @Test
    fun `repair never touches missed or cancelled history`() {
        val t = startAt()
        val rows = mutableListOf(
            dose(1, 1, DoseStatus.CONFIRMED, t, t + 60_000),
            dose(2, 2, DoseStatus.MISSED, t + 30 * 60 * 1000),
            dose(3, 3, DoseStatus.CANCELLED, t + 60 * 60 * 1000),
            dose(4, 4, DoseStatus.PENDING, t + 90 * 60 * 1000),
        )
        val plan = ScheduleEngine.planDoseRepair(rows)
        assertTrue(plan.deleteIds.isEmpty())
        val survivors = applyPlan(rows, plan)
        assertEquals(4, survivors.size)
    }

    @Test
    fun `stale pending rows at or below the confirmed frontier are removed`() {
        val t = startAt()
        // Drops 1-2 confirmed; a stale pre-planned PENDING row for drop 2
        // lingers alongside the true chain head (drop 3).
        val rows = mutableListOf(
            dose(1, 1, DoseStatus.CONFIRMED, t, t + 60_000),
            dose(2, 2, DoseStatus.CONFIRMED, t + 30 * 60 * 1000, t + 31 * 60 * 1000),
            dose(3, 2, DoseStatus.PENDING, t + 30 * 60 * 1000), // stale
            dose(4, 3, DoseStatus.PENDING, t + 61 * 60 * 1000), // chain head
        )
        val survivors = applyPlan(rows, ScheduleEngine.planDoseRepair(rows))
        assertEquals(setOf(1L, 2L, 4L), survivors.map { it.id }.toSet())
    }

    @Test
    fun `repair only deletes - the special index can never be violated`() {
        // Even with duplicate rows, the plan contains no inserts — only deletes.
        val rows = corruptedFixture()
        val plan = ScheduleEngine.planDoseRepair(rows)
        assertTrue(plan.deleteIds.isNotEmpty())
        assertEquals(10 - plan.deleteIds.size, applyPlan(rows, plan).size)
    }
}
