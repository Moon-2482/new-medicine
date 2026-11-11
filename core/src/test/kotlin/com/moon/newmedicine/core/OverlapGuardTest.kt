package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test

/**
 * v1.13: single-active-session invariant (user order after the 17:00 live
 * incident — the evening session auto-started while the afternoon session
 * was still ACTIVE). A session must NEVER start while another session is
 * still ACTIVE; the next session starts only after the current one finishes
 * (COMPLETED or CANCELLED).
 *
 * Covers the pure decision helpers:
 * - `blockingSession`: which ACTIVE row (if any) blocks a start,
 * - `deferredStartKey`: the day-scoped KV flag for a deferred auto start.
 *
 * The repo's thin glue (guard query, flag set/clear SQL, notification,
 * alarm cancel) is covered by the DB-level python tests (real SQLite);
 * the Android-only wiring (notification posting, alarm cancel) runs on
 * device only.
 */
class OverlapGuardTest {

    private val dayKey = "2026-10-19"

    private fun session(
        id: Long = 1L,
        type: SessionType = SessionType.AFTERNOON,
        status: SessionStatus = SessionStatus.ACTIVE,
    ) = DoseSession(id = id, type = type, dayKey = dayKey, startedAt = 1L, status = status)

    @Test
    fun `no active session blocks nothing`() {
        assertNull(ScheduleEngine.blockingSession(null, SessionType.EVENING))
        assertNull(ScheduleEngine.blockingSession(null, SessionType.MORNING))
    }

    @Test
    fun `same-type active session is not a blocker`() {
        // Same-type case keeps the pre-existing AlreadyActive path.
        assertNull(
            ScheduleEngine.blockingSession(session(type = SessionType.AFTERNOON), SessionType.AFTERNOON)
        )
        assertNull(
            ScheduleEngine.blockingSession(session(type = SessionType.MORNING), SessionType.MORNING)
        )
    }

    @Test
    fun `different-type active session blocks the start`() {
        val afternoon = session(id = 7L, type = SessionType.AFTERNOON)
        val blocking = ScheduleEngine.blockingSession(afternoon, SessionType.EVENING)
        assertNotNull(blocking)
        assertEquals(7L, blocking!!.id)
        assertEquals(SessionType.AFTERNOON, blocking.type)
    }

    @Test
    fun `morning active blocks afternoon auto start`() {
        val morning = session(type = SessionType.MORNING)
        assertEquals(morning, ScheduleEngine.blockingSession(morning, SessionType.AFTERNOON))
    }

    @Test
    fun `evening active blocks afternoon start`() {
        val evening = session(type = SessionType.EVENING)
        assertEquals(evening, ScheduleEngine.blockingSession(evening, SessionType.AFTERNOON))
    }

    @Test
    fun `deferred start key format`() {
        assertEquals(
            "deferred_start_AFTERNOON_2026-10-19",
            ScheduleEngine.deferredStartKey(SessionType.AFTERNOON, dayKey),
        )
        assertEquals(
            "deferred_start_EVENING_2026-10-19",
            ScheduleEngine.deferredStartKey(SessionType.EVENING, dayKey),
        )
    }

    @Test
    fun `deferred start key is scoped per type and per day`() {
        val a = ScheduleEngine.deferredStartKey(SessionType.EVENING, "2026-10-19")
        val b = ScheduleEngine.deferredStartKey(SessionType.EVENING, "2026-10-20")
        val c = ScheduleEngine.deferredStartKey(SessionType.AFTERNOON, "2026-10-19")
        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }
}
