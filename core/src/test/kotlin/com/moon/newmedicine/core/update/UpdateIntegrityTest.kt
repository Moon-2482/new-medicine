package com.moon.newmedicine.core.update

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v1.7 — bulletproof updater. The v1.6 "App not installed as package
 * appears to be invalid" (INSTALL_FAILED_INVALID_APK) report: two
 * concurrent DownloadManager streams interleaved bytes into one fixed
 * filename while DM still reported STATUS_SUCCESSFUL. Covered here:
 * the in-flight reuse policy and the pre-install ZIP EOCD integrity gate.
 * Pure JVM — no Android dependencies.
 */
class UpdateIntegrityTest {

    // ---------- shouldReuseDownload: every DownloadManager STATUS_* ----------

    @Test
    fun `reuse - STATUS_PENDING is reused`() {
        assertTrue(UpdateDownloadPolicy.shouldReuseDownload(1)) // PENDING
    }

    @Test
    fun `reuse - STATUS_RUNNING is reused`() {
        assertTrue(UpdateDownloadPolicy.shouldReuseDownload(2)) // RUNNING
    }

    @Test
    fun `reuse - STATUS_PAUSED is reused`() {
        assertTrue(UpdateDownloadPolicy.shouldReuseDownload(4)) // PAUSED
    }

    @Test
    fun `reuse - STATUS_SUCCESSFUL is not reused`() {
        assertFalse(UpdateDownloadPolicy.shouldReuseDownload(8)) // SUCCESSFUL
    }

    @Test
    fun `reuse - STATUS_FAILED is not reused`() {
        assertFalse(UpdateDownloadPolicy.shouldReuseDownload(16)) // FAILED
    }

    @Test
    fun `reuse - unknown status is not reused`() {
        assertFalse(UpdateDownloadPolicy.shouldReuseDownload(0))
        assertFalse(UpdateDownloadPolicy.shouldReuseDownload(999))
        assertFalse(UpdateDownloadPolicy.shouldReuseDownload(-1))
    }

    // ---------- isValidApk ----------

    private fun tempApk(bytes: ByteArray): File {
        val dir = Files.createTempDirectory("upd-integrity").toFile()
        val f = File(dir, "test.apk")
        f.writeBytes(bytes)
        f.deleteOnExit()
        dir.deleteOnExit()
        return f
    }

    /** A minimal structurally-complete ZIP: >= 1MB with a real EOCD at the end. */
    private fun completeZipBytes(size: Int = 1_000_100): ByteArray {
        val buf = ByteArray(size) { 0x00 }
        // EOCD record: PK\x05\x06 + 18 bytes of (empty) fields.
        val eocd = byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)
        eocd.copyInto(buf, size - eocd.size)
        return buf
    }

    /** Deterministic filler that can never contain the EOCD signature. */
    private fun noEocdBytes(size: Int): ByteArray =
        ByteArray(size) { i ->
            val b = (i % 250) + 1
            (if (b == 0x50) 0x51 else b).toByte()
        }

    @Test
    fun `integrity - complete zip with EOCD is valid`() {
        assertTrue(UpdateIntegrity.isValidApk(tempApk(completeZipBytes())))
    }

    @Test
    fun `integrity - truncated file without EOCD is invalid`() {
        // Same size, but the EOCD signature was cut off (interleaved /
        // truncated download).
        assertFalse(UpdateIntegrity.isValidApk(tempApk(noEocdBytes(1_000_100))))
    }

    @Test
    fun `integrity - EOCD cut off mid-signature is invalid`() {
        val buf = completeZipBytes()
        val eocdStart = buf.size - 22
        // End the file inside the 4-byte signature: trailing PK\x05 only.
        val cut = buf.copyOf(eocdStart + 3)
        assertFalse(UpdateIntegrity.isValidApk(tempApk(cut)))
    }

    @Test
    fun `integrity - random-looking bytes without EOCD are invalid`() {
        assertFalse(UpdateIntegrity.isValidApk(tempApk(noEocdBytes(2_000_000))))
    }

    @Test
    fun `integrity - tiny file is invalid even with EOCD`() {
        val tiny = byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(100)
        assertFalse(UpdateIntegrity.isValidApk(tempApk(tiny)))
    }

    @Test
    fun `integrity - real-apk size class with EOCD is valid`() {
        // Guards the MIN_APK_BYTES threshold: the real APK is ~837KB, so a
        // 600KB+ complete file must pass (a 1MB threshold would wrongly
        // reject the genuine update).
        assertTrue(UpdateIntegrity.isValidApk(tempApk(completeZipBytes(600_000))))
    }

    @Test
    fun `integrity - empty file is invalid`() {
        assertFalse(UpdateIntegrity.isValidApk(tempApk(ByteArray(0))))
    }

    @Test
    fun `integrity - nonexistent file is invalid`() {
        val f = File(Files.createTempDirectory("upd-missing").toFile(), "nope.apk")
        assertFalse(f.exists())
        assertFalse(UpdateIntegrity.isValidApk(f))
    }

    @Test
    fun `integrity - directory is invalid`() {
        val dir = Files.createTempDirectory("upd-dir").toFile()
        dir.deleteOnExit()
        assertFalse(UpdateIntegrity.isValidApk(dir))
    }
}
