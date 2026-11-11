package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * v1.5 strict chaining: a session persists ONLY drop 1 at start; each YES
 * appends exactly the next row (dueAt = confirmedAt + interval). A drop can
 * never exist before the previous one is taken, so duplicate dropNumbers
 * are structurally impossible.
 *
 * The repo's startSession/confirmDose SQL is mirrored here (the repository
 * itself needs Android and cannot run on the JVM); the DB-level python tests
 * execute the real SQL against real SQLite.
 */
class ChainingTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val settings = DoseSettings()
    private val dayKey = "2026-10-19" // a Monday

    private fun startAt() = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)

    /** Mirrors DropsRepository.startSession (drop 1 only) + confirmDose (chained insert). */
    inner class ChainSim {
        var idSeq = 1L
        val rows = mutableListOf<Dose>()
        lateinit var session: DoseSession

        fun start(type: SessionType, at: Long) {
            val plan = ScheduleEngine.planSession(
                type, dayKey, at, settings,
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

        /** YES on drop [n] at [now]: confirm it, append drop n+1. */
        fun yes(n: Int, now: Long): Dose {
            val d = rows.first { it.sessionId == session.id && it.dropNumber == n }
            val count = ScheduleEngine.dropCountFor(session.type, settings)
            val r = ScheduleEngine.confirmDose(d, now, settings, isLastDrop = n == count)
            check(!r.unchanged) { "YES on drop $n must not be a no-op here" }
            rows[rows.indexOf(d)] = r.dose
            val nextDueAt = r.nextDueAt
            if (nextDueAt != null) {
                val kind = ScheduleEngine.kindForDrop(
                    session.type, n + 1, settings,
                    ScheduleEngine.isSpecialTakenToday(rows, dayKey),
                )
                rows.add(
                    Dose(
                        id = idSeq++, sessionId = session.id, dropNumber = n + 1,
                        kind = kind, dayKey = dayKey, dueAt = nextDueAt,
                    )
                )
            }
            return r.dose
        }

        /** NO on drop [n] at [now]: re-arm the same row, create nothing. */
        fun no(n: Int, now: Long) {
            val d = rows.first { it.sessionId == session.id && it.dropNumber == n }
            rows[rows.indexOf(d)] = ScheduleEngine.declineDose(d, now, settings)
        }
    }

    @Test
    fun `start persists exactly one pending row - drop 1`() {
        val sim = ChainSim()
        sim.start(SessionType.MORNING, startAt())
        assertEquals(1, sim.rows.size)
        val d1 = sim.rows.single()
        assertEquals(1, d1.dropNumber)
        assertEquals(DoseStatus.PENDING, d1.status)
        assertEquals(startAt(), d1.dueAt)
        assertEquals(DoseKind.REGULAR, d1.kind)
        // Only one row exists, so exactly one alarm can be armed for it.
    }

    @Test
    fun `YES on drop 1 creates drop 2 due exactly 10 min after confirmation`() {
        val sim = ChainSim()
        sim.start(SessionType.MORNING, startAt())
        val t1 = startAt() + 7 * 60 * 1000 // 7 min late
        sim.yes(1, t1)
        assertEquals(2, sim.rows.size)
        val d2 = sim.rows.first { it.dropNumber == 2 }
        assertEquals(DoseStatus.PENDING, d2.status)
        assertEquals(t1 + settings.doseIntervalMillis, d2.dueAt)
        assertEquals(t1 + 10 * 60 * 1000, d2.dueAt)
    }

    @Test
    fun `drop 3 does not exist before drop 2 is confirmed`() {
        val sim = ChainSim()
        sim.start(SessionType.MORNING, startAt())
        sim.yes(1, startAt() + 60_000)
        assertFalse(sim.rows.any { it.dropNumber == 3 })
        val d2 = sim.rows.first { it.dropNumber == 2 }
        sim.yes(2, d2.dueAt)
        assertTrue(sim.rows.any { it.dropNumber == 3 })
        assertEquals(3, sim.rows.size)
    }

    @Test
    fun `full 4-drop morning walkthrough creates exactly 4 rows, all regular`() {
        val sim = ChainSim()
        sim.start(SessionType.MORNING, startAt())
        val clock = FakeClock(startAt())
        for (n in 1..settings.morningDropCount) {
            val d = sim.rows.first { it.dropNumber == n }
            // Wandering confirm times: late on odd drops, on time on even.
            clock.set(d.dueAt + if (n % 2 == 1) 6 * 60 * 1000 else 0)
            val confirmed = sim.yes(n, clock.nowMillis())
            assertEquals(clock.nowMillis(), confirmed.confirmedAt)
            if (n < settings.morningDropCount) {
                val next = sim.rows.first { it.dropNumber == n + 1 }
                assertEquals("next due anchors to confirmation, not to plan",
                    clock.nowMillis() + settings.doseIntervalMillis, next.dueAt)
            }
        }
        assertEquals("exactly 4 rows are ever created", 4, sim.rows.size)
        assertEquals(4, sim.rows.count { it.status == DoseStatus.CONFIRMED })
        // No special drop exists in New Medicine: all 4 confirmed regular.
        assertTrue(sim.rows.all { it.kind == DoseKind.REGULAR })
        assertEquals(0, sim.rows.count { it.kind == DoseKind.SPECIAL })
        assertTrue(ScheduleEngine.checkSpecialInvariants(sim.rows, mapOf(sim.session.id to sim.session)).isEmpty())
        assertFalse(ScheduleEngine.isSpecialTakenToday(sim.rows, dayKey))
        // No duplicate dropNumbers can exist.
        assertEquals(4, sim.rows.map { it.dropNumber }.toSet().size)
    }

    @Test
    fun `NO retries create no rows`() {
        val sim = ChainSim()
        sim.start(SessionType.AFTERNOON, startAt())
        repeat(3) { sim.no(1, startAt() + it * 5 * 60 * 1000) }
        assertEquals(1, sim.rows.size)
        assertEquals(3, sim.rows.single().retryCount)
        sim.yes(1, startAt() + 3 * 5 * 60 * 1000)
        assertEquals(2, sim.rows.size)
    }

    @Test
    fun `kindForDrop never returns SPECIAL in New Medicine`() {
        // New Medicine defaults: the special drop is disabled entirely.
        assertFalse(settings.specialDropEnabled)
        for (type in SessionType.values()) {
            for (n in 1..4) {
                assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(type, n, settings, specialTaken = false))
            }
        }
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.MORNING, 6, settings, specialTaken = false))
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.AFTERNOON, 6, settings, specialTaken = false))
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.EVENING, 6, settings, specialTaken = false))
        // Already taken today -> never again.
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.MORNING, 6, settings, specialTaken = true))
        // Custom position is inert while the special drop is disabled.
        val custom = settings.copy(specialDropPosition = 3)
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.MORNING, 3, custom, specialTaken = false))
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.MORNING, 6, custom, specialTaken = false))
    }

    @Test
    fun `sessionProgress uses the planned total under chaining`() {
        val sim = ChainSim()
        sim.start(SessionType.MORNING, startAt())
        val clock = FakeClock(startAt())
        // Drops 1-3 taken; drop 4 is the pending chain head, due in the future.
        for (n in 1..3) {
            val d = sim.rows.first { it.dropNumber == n }
            clock.set(d.dueAt)
            sim.yes(n, clock.nowMillis())
        }
        val d4 = sim.rows.first { it.dropNumber == 4 }
        clock.set(d4.dueAt - 10 * 60 * 1000) // 10 min before drop 4 is due
        val p = ScheduleEngine.sessionProgress(sim.rows, clock.nowMillis(), 7)
        assertEquals(3, p.taken)
        assertEquals(0, p.pending)
        assertEquals(0, p.missed)
        assertEquals(4, p.upcoming)
        assertEquals(7, p.total)
        assertEquals(42, p.percent) // 3/7
        // At the due time the chain head counts as pending (needs attention).
        clock.set(d4.dueAt)
        val p2 = ScheduleEngine.sessionProgress(sim.rows, clock.nowMillis(), 7)
        assertEquals(1, p2.pending)
        assertEquals(3, p2.upcoming)
    }

    @Test
    fun `dayCompletionPercent uses the planned total`() {
        val sim = ChainSim()
        sim.start(SessionType.MORNING, startAt())
        val clock = FakeClock(startAt())
        for (n in 1..3) {
            val d = sim.rows.first { it.dropNumber == n }
            clock.set(d.dueAt)
            sim.yes(n, clock.nowMillis())
        }
        // 3 taken of a planned 19-drop day (7+6+6, v1.9).
        assertEquals(15, ScheduleEngine.dayCompletionPercent(sim.rows, 19))
        assertEquals(0, ScheduleEngine.dayCompletionPercent(emptyList(), 0))
    }
}
