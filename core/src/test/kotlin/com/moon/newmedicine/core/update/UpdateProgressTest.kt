package com.moon.newmedicine.core.update

import org.junit.Assert.*
import org.junit.Test

/**
 * v1.3 — visible update download. The v1.2 "tapped Download & Install and
 * nothing happened" report: progress math and failure-reason mapping are
 * pure and tested here; the dialog polls DownloadManager with these.
 */
class UpdateProgressTest {

    @Test
    fun `percent - empty download is 0`() {
        assertEquals(0, UpdateProgress.percent(0, 100))
    }

    @Test
    fun `percent - half download is 50`() {
        assertEquals(50, UpdateProgress.percent(50, 100))
    }

    @Test
    fun `percent - complete download is 100`() {
        assertEquals(100, UpdateProgress.percent(816_420, 816_420))
    }

    @Test
    fun `percent - unknown total is indeterminate`() {
        assertEquals(-1, UpdateProgress.percent(1234, 0))
        assertEquals(-1, UpdateProgress.percent(1234, -1))
    }

    @Test
    fun `percent - clamps over-reporting servers`() {
        assertEquals(100, UpdateProgress.percent(200, 100))
    }

    @Test
    fun `percent - negative download is 0`() {
        assertEquals(0, UpdateProgress.percent(-5, 100))
    }

    @Test
    fun `failure reason - maps known DownloadManager codes`() {
        assertTrue(UpdateFailureReason.forCode(1006).contains("storage", ignoreCase = true))
        assertTrue(UpdateFailureReason.forCode(1001).contains("save", ignoreCase = true))
        assertTrue(UpdateFailureReason.forCode(1004).contains("Network", ignoreCase = true))
        assertTrue(UpdateFailureReason.forCode(1002).contains("server", ignoreCase = true))
        assertTrue(UpdateFailureReason.forCode(1005).contains("redirect", ignoreCase = true))
    }

    @Test
    fun `failure reason - unknown code stays actionable`() {
        val msg = UpdateFailureReason.forCode(9999)
        assertTrue(msg.contains("9999"))
        assertTrue(msg.contains("retry", ignoreCase = true))
    }
}
