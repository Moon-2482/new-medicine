package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test

/**
 * v1.10: one-time migrations run in isolation ([ScheduleEngine.runMigrationSafely]).
 * A throwing migration is reported and swallowed — it can never block the
 * later migrations, and (being idempotent + flag-guarded) it retries on the
 * next launch.
 *
 * Covers: block runs when healthy; a throwing block does not propagate;
 * onError receives the exact tag and throwable; migrations after a failing
 * one still execute; a Throwable (not just Exception) is also contained.
 */
class MigrationIsolationTest {

    private val errors = ArrayList<Pair<String, Throwable>>()
    private val onError: (String, Throwable) -> Unit = { tag, t -> errors.add(tag to t) }

    @Test
    fun `1 - healthy migration runs its block`() {
        var ran = false
        ScheduleEngine.runMigrationSafely("ok", onError) { ran = true }
        assertTrue(ran)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `2 - throwing migration does not propagate and is reported`() {
        val boom = RuntimeException("migration blew up")
        ScheduleEngine.runMigrationSafely("ensureDataRepaired", onError) { throw boom }
        assertEquals(1, errors.size)
        assertEquals("ensureDataRepaired", errors[0].first)
        assertSame(boom, errors[0].second)
    }

    @Test
    fun `3 - a failing migration does not block later migrations`() {
        val order = ArrayList<String>()
        ScheduleEngine.runMigrationSafely("first", onError) {
            order.add("first")
            throw IllegalStateException("first failed")
        }
        ScheduleEngine.runMigrationSafely("second", onError) { order.add("second") }
        ScheduleEngine.runMigrationSafely("third", onError) { order.add("third") }
        // "first" added itself before throwing; second and third still ran.
        assertEquals(listOf("first", "second", "third"), order)
        assertEquals(1, errors.size)
        assertEquals("first", errors[0].first)
    }

    @Test
    fun `4 - Error (not just Exception) is also contained`() {
        ScheduleEngine.runMigrationSafely("broken", onError) { throw AssertionError("serious") }
        assertEquals(1, errors.size)
        assertEquals("broken", errors[0].first)
        assertTrue(errors[0].second is AssertionError)
    }

    @Test
    fun `5 - block exceptions never escape even with throwing onError-adjacent code`() {
        // The block's own result is unaffected by the wrapper.
        var count = 0
        ScheduleEngine.runMigrationSafely("a", onError) { count++ }
        ScheduleEngine.runMigrationSafely("b", onError) { count++ }
        assertEquals(2, count)
        assertTrue(errors.isEmpty())
    }
}
