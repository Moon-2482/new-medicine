package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * New Medicine v1.0: 4 drops in EVERY session (morning / afternoon /
 * evening), all REGULAR — no special drop exists in this app.
 *
 * Covers: new defaults, the one-time settings migration helpers
 * ([ScheduleEngine.migratedDropCounts], kept from the old app and inert
 * on a fresh install), full chained walkthroughs under the new counts,
 * and mid-session safety (a running session completes cleanly at #4 —
 * no #5 is ever invented, because `isLastDrop` reads the LIVE settings).
 */
class DropCountTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val settings = DoseSettings()
    private val dayKey = "2026-10-19" // a Monday

    private fun startAt() = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)

    /** Minimal chained-session simulator (mirrors DropsRepository confirmDose). */
    inner class ChainSim {
        var idSeq = 1L
        val rows = mutableListOf<Dose>()
        lateinit var session: DoseSession

        fun start(type: SessionType, at: Long, s: DoseSettings = settings) {
            val plan = ScheduleEngine.planSession(
                type, dayKey, at, s,
                specialAlreadyTakenToday = false, zone,
            ) as SessionPlan.Ok
            session = DoseSession(id = 1, type = type, dayKey = dayKey, startedAt = at)
            val first = plan.doses.first()
            rows.add(
                Dose(
                    id = idSeq++, sessionId = session.id, dropNumber = 1,
                    kind = first.kind, dayKey = dayKey, dueAt = first.dueAt,
                )
            )
        }

        /** YES on drop [n] at [now]; appends drop n+1 unless it was the last. */
        fun yes(n: Int, now: Long, s: DoseSettings = settings): Dose {
            val d = rows.first { it.dropNumber == n }
            // Mirrors DropsRepository.confirmDose: >= (not ==) so an
            // overshoot drop from a pre-migration count still completes.
            val isLast = n >= ScheduleEngine.dropCountFor(session.type, s)
            val result = ScheduleEngine.confirmDose(d, now, s, isLast)
            assertFalse(result.unchanged)
            rows[rows.indexOf(d)] = result.dose
            val nextDueAt = result.nextDueAt
            if (nextDueAt != null) {
                val specialTaken = ScheduleEngine.isSpecialTakenToday(rows, dayKey)
                rows.add(
                    Dose(
                        id = idSeq++, sessionId = session.id, dropNumber = n + 1,
                        kind = ScheduleEngine.kindForDrop(session.type, n + 1, s, specialTaken),
                        dayKey = dayKey, dueAt = nextDueAt,
                    )
                )
            }
            return result.dose
        }
    }

    @Test
    fun `defaults are morning 4, afternoon 4, evening 4, no special drop`() {
        assertEquals(4, settings.morningDropCount)
        assertEquals(4, settings.afternoonDropCount)
        assertEquals(4, settings.eveningDropCount)
        assertEquals(10L * 60 * 1000, settings.doseIntervalMillis)
        assertFalse(settings.specialDropEnabled)
    }

    @Test
    fun `migratedDropCounts moves the old defaults 8-7-7 to 7-6-6`() {
        assertEquals(Triple(7, 6, 6), ScheduleEngine.migratedDropCounts(8, 7, 7))
    }

    @Test
    fun `migratedDropCounts leaves explicit choices and fresh installs alone`() {
        assertNull(ScheduleEngine.migratedDropCounts(9, 5, 5)) // explicit user choices
        assertNull(ScheduleEngine.migratedDropCounts(null, null, null)) // fresh install
        assertNull(ScheduleEngine.migratedDropCounts(7, 6, 6)) // already migrated
    }

    @Test
    fun `migratedDropCounts migrates partially-changed settings`() {
        // Only the old-default values move; explicit ones pass through.
        assertEquals(Triple(7, 5, 6), ScheduleEngine.migratedDropCounts(8, 5, 7))
        assertEquals(Triple(7, 6, 6), ScheduleEngine.migratedDropCounts(8, null, null))
    }

    @Test
    fun `morning walkthrough - exactly 4 drops, all regular, 4th completes`() {
        val sim = ChainSim()
        sim.start(SessionType.MORNING, startAt())
        val clock = FakeClock(startAt())
        for (n in 1..4) {
            val d = sim.rows.first { it.dropNumber == n }
            clock.set(d.dueAt)
            sim.yes(n, clock.nowMillis())
        }
        assertEquals(4, sim.rows.size)
        assertEquals(4, sim.rows.count { it.status == DoseStatus.CONFIRMED })
        assertTrue(sim.rows.all { it.kind == DoseKind.REGULAR })
        // #4 was the last: no #5 row exists and the session completes.
        assertFalse(sim.rows.any { it.dropNumber == 5 })
        assertEquals(0, sim.rows.count { it.kind == DoseKind.SPECIAL })
        assertTrue(ScheduleEngine.checkSpecialInvariants(sim.rows, mapOf(sim.session.id to sim.session)).isEmpty())
    }

    @Test
    fun `afternoon walkthrough - 4 regular drops, no special, completes at 4`() {
        val sim = ChainSim()
        val at = ScheduleEngine.minutesOfDayToMillis(dayKey, 13 * 60 + 45, zone)
        sim.start(SessionType.AFTERNOON, at)
        val clock = FakeClock(at)
        for (n in 1..4) {
            val d = sim.rows.first { it.dropNumber == n }
            clock.set(d.dueAt)
            sim.yes(n, clock.nowMillis())
        }
        assertEquals(4, sim.rows.size)
        assertTrue(sim.rows.all { it.kind == DoseKind.REGULAR })
        assertTrue(sim.rows.none { it.kind == DoseKind.SPECIAL })
        assertFalse(sim.rows.any { it.dropNumber == 5 })
    }

    @Test
    fun `evening walkthrough - 4 drops finishing before the 20-00 cutoff`() {
        val sim = ChainSim()
        val at = ScheduleEngine.minutesOfDayToMillis(dayKey, 17 * 60, zone)
        sim.start(SessionType.EVENING, at)
        val cutoff = ScheduleEngine.minutesOfDayToMillis(dayKey, settings.cutoffMinutesOfDay, zone)
        val clock = FakeClock(at)
        for (n in 1..4) {
            val d = sim.rows.first { it.dropNumber == n }
            assertTrue("drop $n must not pass the cutoff", d.dueAt <= cutoff)
            clock.set(d.dueAt)
            sim.yes(n, clock.nowMillis())
        }
        assertEquals(4, sim.rows.size)
        // Last drop due 17:30 — 2.5 hours of margin before 20:00.
        assertEquals(
            ScheduleEngine.minutesOfDayToMillis(dayKey, 17 * 60 + 30, zone),
            sim.rows.first { it.dropNumber == 4 }.dueAt,
        )
    }

    @Test
    fun `mid-session - 3 taken, confirming 4 completes with no 5`() {
        // isLastDrop reads the LIVE settings: confirming the 4th drop
        // completes the session instead of inventing a 5th.
        val sim = ChainSim()
        sim.start(SessionType.MORNING, startAt())
        val clock = FakeClock(startAt())
        for (n in 1..3) {
            val d = sim.rows.first { it.dropNumber == n }
            clock.set(d.dueAt)
            sim.yes(n, clock.nowMillis())
        }
        assertEquals(4, sim.rows.size) // 3 confirmed + pending #4 chain head
        val d4 = sim.rows.first { it.dropNumber == 4 }
        assertEquals(DoseStatus.PENDING, d4.status)
        clock.set(d4.dueAt)
        val confirmed4 = sim.yes(4, clock.nowMillis())
        assertEquals(DoseStatus.CONFIRMED, confirmed4.status)
        // No #5 is invented; the session simply completes.
        assertFalse("no drop 5 may ever be created", sim.rows.any { it.dropNumber == 5 })
        assertEquals(4, sim.rows.size)
    }

    @Test
    fun `overshoot - pending 5 from a wider count completes under the new count`() {
        // The session ran under a wider count of 5 (4 taken, #5 pending
        // chain head); the count becomes 4. Confirming the overshoot #5
        // must COMPLETE the session — never invent #6.
        val oldSettings = settings.copy(morningDropCount = 5)
        val sim = ChainSim()
        sim.start(SessionType.MORNING, startAt(), oldSettings)
        val clock = FakeClock(startAt())
        for (n in 1..4) {
            val d = sim.rows.first { it.dropNumber == n }
            clock.set(d.dueAt)
            sim.yes(n, clock.nowMillis(), oldSettings)
        }
        // 4 confirmed + pending #5, built under the old count.
        assertEquals(5, sim.rows.size)
        val d5 = sim.rows.first { it.dropNumber == 5 }
        assertEquals(DoseStatus.PENDING, d5.status)
        // Now the new settings apply: confirming #5 completes, no #6.
        clock.set(d5.dueAt)
        val confirmed5 = sim.yes(5, clock.nowMillis(), settings)
        assertEquals(DoseStatus.CONFIRMED, confirmed5.status)
        assertFalse("no drop 6 may ever be created", sim.rows.any { it.dropNumber == 6 })
        assertEquals(5, sim.rows.size)
    }
}
