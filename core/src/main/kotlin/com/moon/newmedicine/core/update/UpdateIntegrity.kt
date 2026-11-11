package com.moon.newmedicine.core.update

import java.io.File
import java.io.RandomAccessFile

/**
 * Pre-install integrity gate for the self-updater. Pure JVM — zero Android
 * dependencies, fully unit-testable.
 *
 * The v1.6 "App not installed as package appears to be invalid"
 * (INSTALL_FAILED_INVALID_APK) report: two concurrent DownloadManager
 * requests wrote interleaved bytes to one fixed filename; DM still
 * reported STATUS_SUCCESSFUL per stream, so the corrupt file went
 * straight to the installer, which rejected it. The server file was
 * byte-perfect (832,804 bytes, badging OK) — the corruption happened
 * on-device. This gate rejects such files before the installer ever
 * sees them.
 */
object UpdateIntegrity {

    /**
     * Minimum plausible APK size: rejects empty/truncated downloads fast.
     * Kept well below the real APK (~837KB as of v1.7) — a partial
     * download that lost its tail fails the EOCD check regardless.
     */
    const val MIN_APK_BYTES = 500_000L

    /**
     * True when [file] looks like a complete APK: it exists, is big enough,
     * and carries a ZIP End-of-Central-Directory record (PK\x05\x06) in its
     * tail. A truncated or interleaved (concurrent-write) download fails
     * this — the EOCD is always the last structure of a complete ZIP.
     */
    fun isValidApk(file: File): Boolean {
        if (!file.isFile) return false
        val len = file.length()
        if (len < MIN_APK_BYTES) return false
        // The EOCD record is 22 bytes + up to 65535 bytes of comment, so it
        // always lies within the last (65535 + 22) bytes of a valid ZIP.
        val tailSize = minOf(len, 65_535L + 22L).toInt()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(len - tailSize)
                val buf = ByteArray(tailSize)
                raf.readFully(buf)
                var i = 0
                var found = false
                while (i <= buf.size - 4) {
                    if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4B.toByte() &&
                        buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()
                    ) {
                        found = true
                        break
                    }
                    i++
                }
                found
            }
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * In-flight download reuse policy: never enqueue a second DownloadManager
 * request while one is already pending/running/paused — two concurrent
 * streams to one file is the v1.6 corruption class.
 *
 * The DownloadManager STATUS_* values are kept as literals (kept in sync
 * with android.app.DownloadManager: PENDING=1, RUNNING=2, PAUSED=4,
 * SUCCESSFUL=8, FAILED=16) so this stays Android-free and JVM-testable.
 */
object UpdateDownloadPolicy {

    /** True when an in-flight download must be reused instead of re-enqueued. */
    fun shouldReuseDownload(status: Int): Boolean =
        status == 1 || status == 2 || status == 4 // PENDING, RUNNING, PAUSED
}
