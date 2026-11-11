package com.moon.newmedicine.core.update

import org.junit.Assert.*
import org.junit.Test

/** No network in tests: every decision is exercised with canned bodies. */
class UpdateCheckerTest {

    private val goodBody = """
        {
          "versionCode": 2,
          "versionName": "1.1",
          "apkUrl": "https://github.com/Moon-2482/new-medicine/releases/download/v1.0/NewMedicine-debug.apk",
          "notes": "Wake detection fixes.\nEvening cutoff fix."
        }
    """.trimIndent()

    @Test
    fun `newer versionCode means update available`() {
        val result = UpdateChecker.decide(goodBody, installedVersionCode = 1)
        assertTrue(result is UpdateCheckResult.Available)
        val info = (result as UpdateCheckResult.Available).info
        assertEquals(2, info.versionCode)
        assertEquals("1.1", info.versionName)
        assertTrue(info.apkUrl.startsWith("https://"))
        assertTrue(info.notes.contains("cutoff"))
    }

    @Test
    fun `same versionCode is up to date`() {
        assertTrue(UpdateChecker.decide(goodBody, 2) is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `older server versionCode is up to date`() {
        assertTrue(UpdateChecker.decide(goodBody, 5) is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `null body fails silently`() {
        assertTrue(UpdateChecker.decide(null, 1) is UpdateCheckResult.Failed)
    }

    @Test
    fun `blank body fails silently`() {
        assertTrue(UpdateChecker.decide("   ", 1) is UpdateCheckResult.Failed)
    }

    @Test
    fun `malformed json fails silently`() {
        assertTrue(UpdateChecker.decide("{not json", 1) is UpdateCheckResult.Failed)
        assertTrue(UpdateChecker.decide("{\"versionCode\":", 1) is UpdateCheckResult.Failed)
    }

    @Test
    fun `missing required fields fail`() {
        assertNull(UpdateChecker.parse("{\"versionName\":\"1.1\",\"apkUrl\":\"https://x/y.apk\"}"))
        assertNull(UpdateChecker.parse("{\"versionCode\":2,\"apkUrl\":\"https://x/y.apk\"}"))
        assertNull(UpdateChecker.parse("{\"versionCode\":2,\"versionName\":\"1.1\"}"))
        assertTrue(UpdateChecker.decide("{\"versionCode\":2}", 1) is UpdateCheckResult.Failed)
    }

    @Test
    fun `non-https apkUrl is rejected`() {
        val body = goodBody.replace("https://github.com", "http://github.com")
        assertNull(UpdateChecker.parse(body))
    }

    @Test
    fun `field order and whitespace do not matter`() {
        val body = "{ \"notes\" : \"hi\" , \"apkUrl\":\"https://x/y.apk\",\"versionName\" : \"2.0\" , \"versionCode\" : 9 }"
        val info = UpdateChecker.parse(body)
        assertNotNull(info)
        assertEquals(9, info!!.versionCode)
        assertEquals("2.0", info.versionName)
        assertEquals("hi", info.notes)
    }

    @Test
    fun `notes field is optional`() {
        val body = "{\"versionCode\":3,\"versionName\":\"1.2\",\"apkUrl\":\"https://x/y.apk\"}"
        val info = UpdateChecker.parse(body)
        assertNotNull(info)
        assertEquals("", info!!.notes)
    }

    @Test
    fun `escaped characters in notes are unescaped`() {
        val body = "{\"versionCode\":3,\"versionName\":\"1.2\",\"apkUrl\":\"https://x/y.apk\"," +
            "\"notes\":\"line1\\nline2 \\\"quoted\\\"\"}"
        val info = UpdateChecker.parse(body)
        assertNotNull(info)
        assertEquals("line1\nline2 \"quoted\"", info!!.notes)
    }
}
