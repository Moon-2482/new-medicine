package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test

/**
 * v1.8: the once-daily SPECIAL drop moved to position 6 (user order),
 * morning session only. Covers:
 * - the new default (6),
 * - the kindForDrop matrix (morning-only, taken/disabled guards),
 * - isSpecialLiveToday (mirrors the partial unique index),
 * - the pure migration helpers (migratedSpecialPosition,
 *   flipTargetForSpecialPosition).
 *
 * The repo's thin glue (flag check, SQL UPDATE) is covered by the DB-level
 * python tests (real SQLite); the decision logic here is pure and
 * JVM-tested.
 */
class SpecialPositionTest {

    private val dayKey = "2026-10-19"
    private val settings = DoseSettings()

    private fun dose(
        id: Long = 1L,
        sessionId: Long = 1L,
        dropNumber: Int = 6,
        kind: DoseKind = DoseKind.REGULAR,
        dayKey: String = this.dayKey,
        status: DoseStatus = DoseStatus.PENDING,
    ) = Dose(
        id = id, sessionId = sessionId, dropNumber = dropNumber, kind = kind,
        dayKey = dayKey, dueAt = 1_790_000_000_000L, status = status,
    )

    @Test
    fun `default special drop position is 6`() {
        assertEquals(6, DoseSettings().specialDropPosition)
        assertEquals(6, settings.specialDropPosition)
    }

    @Test
    fun `kindForDrop never returns SPECIAL with New Medicine defaults`() {
        // New Medicine: the special drop is disabled, so no position in
        // any session can ever be special — including position 6.
        val s = settings
        assertFalse(s.specialDropEnabled)
        for (n in 1..8) {
            assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.MORNING, n, s, specialTaken = false))
        }
    }

    @Test
    fun `kindForDrop never puts special in afternoon or evening`() {
        val s = settings
        for (n in 1..7) {
            assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.AFTERNOON, n, s, specialTaken = false))
            assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.EVENING, n, s, specialTaken = false))
        }
        // Explicit: position 6 in the other sessions is regular.
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.AFTERNOON, 6, s, specialTaken = false))
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.EVENING, 6, s, specialTaken = false))
    }

    @Test
    fun `kindForDrop respects taken and disabled guards at 6`() {
        val s = settings
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.MORNING, 6, s, specialTaken = true))
        val disabled = s.copy(specialDropEnabled = false)
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.MORNING, 6, disabled, specialTaken = false))
    }

    @Test
    fun `kindForDrop ignores custom positions while the special drop is disabled`() {
        val custom = settings.copy(specialDropPosition = 4)
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.MORNING, 4, custom, specialTaken = false))
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.MORNING, 6, custom, specialTaken = false))
        // Out of range for the session count -> never special either.
        val oob = settings.copy(specialDropPosition = 9)
        assertEquals(DoseKind.REGULAR, ScheduleEngine.kindForDrop(SessionType.MORNING, 6, oob, specialTaken = false))
    }

    @Test
    fun `isSpecialLiveToday mirrors the partial unique index`() {
        // PENDING special -> live.
        assertTrue(ScheduleEngine.isSpecialLiveToday(
            listOf(dose(kind = DoseKind.SPECIAL, status = DoseStatus.PENDING)), dayKey))
        // CONFIRMED special -> live.
        assertTrue(ScheduleEngine.isSpecialLiveToday(
            listOf(dose(kind = DoseKind.SPECIAL, status = DoseStatus.CONFIRMED)), dayKey))
        // CANCELLED / MISSED free the slot -> not live.
        assertFalse(ScheduleEngine.isSpecialLiveToday(
            listOf(dose(kind = DoseKind.SPECIAL, status = DoseStatus.CANCELLED)), dayKey))
        assertFalse(ScheduleEngine.isSpecialLiveToday(
            listOf(dose(kind = DoseKind.SPECIAL, status = DoseStatus.MISSED)), dayKey))
        // Regular drops never count, whatever their status.
        assertFalse(ScheduleEngine.isSpecialLiveToday(
            listOf(dose(kind = DoseKind.REGULAR, status = DoseStatus.CONFIRMED)), dayKey))
        // Other days don't count.
        assertFalse(ScheduleEngine.isSpecialLiveToday(
            listOf(dose(kind = DoseKind.SPECIAL, status = DoseStatus.PENDING, dayKey = "2026-10-18")), dayKey))
        // Empty -> not live.
        assertFalse(ScheduleEngine.isSpecialLiveToday(emptyList(), dayKey))
    }

    @Test
    fun `migratedSpecialPosition moves 8 to 6 and nothing else`() {
        assertEquals(6, ScheduleEngine.migratedSpecialPosition(8))
        assertNull("explicitly chosen 5 stays", ScheduleEngine.migratedSpecialPosition(5))
        assertNull("explicitly chosen 6 stays", ScheduleEngine.migratedSpecialPosition(6))
        assertNull("fresh install (no saved value) needs nothing", ScheduleEngine.migratedSpecialPosition(null))
        assertNull("any other value stays", ScheduleEngine.migratedSpecialPosition(3))
    }

    @Test
    fun `flipTargetForSpecialPosition finds the pending regular 6`() {
        val doses = listOf(
            dose(id = 1, dropNumber = 1, status = DoseStatus.CONFIRMED),
            dose(id = 6, dropNumber = 6, kind = DoseKind.REGULAR, status = DoseStatus.PENDING),
        )
        assertEquals(6L, ScheduleEngine.flipTargetForSpecialPosition(doses, 1L)?.id)
    }

    @Test
    fun `flipTargetForSpecialPosition skips when 6 is already resolved`() {
        // #6 already CONFIRMED -> today's special is honestly missed, no flip.
        val confirmed6 = listOf(dose(id = 6, dropNumber = 6, status = DoseStatus.CONFIRMED))
        assertNull(ScheduleEngine.flipTargetForSpecialPosition(confirmed6, 1L))
        // #6 already SPECIAL (pending) -> nothing to flip.
        val special6 = listOf(dose(id = 6, dropNumber = 6, kind = DoseKind.SPECIAL))
        assertNull(ScheduleEngine.flipTargetForSpecialPosition(special6, 1L))
        // #6 MISSED -> no flip.
        val missed6 = listOf(dose(id = 6, dropNumber = 6, status = DoseStatus.MISSED))
        assertNull(ScheduleEngine.flipTargetForSpecialPosition(missed6, 1L))
    }

    @Test
    fun `flipTargetForSpecialPosition ignores other sessions and empty lists`() {
        val otherSession = listOf(dose(id = 6, sessionId = 99L, dropNumber = 6))
        assertNull(ScheduleEngine.flipTargetForSpecialPosition(otherSession, 1L))
        assertNull(ScheduleEngine.flipTargetForSpecialPosition(emptyList(), 1L))
        // Pending regular #5 is not a flip target.
        val five = listOf(dose(id = 5, dropNumber = 5))
        assertNull(ScheduleEngine.flipTargetForSpecialPosition(five, 1L))
    }
}
