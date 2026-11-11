package com.moon.newmedicine.core.update

/**
 * Pure helpers for the visible update-download UI. Zero Android
 * dependencies — every function is unit-testable on the JVM.
 */
object UpdateProgress {

    /**
     * Download progress 0..100. Returns -1 (indeterminate) when the total
     * size is unknown (<= 0); clamps over-100 values that some servers
     * report.
     */
    fun percent(downloadedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0) return -1
        if (downloadedBytes <= 0) return 0
        val p = (downloadedBytes * 100 / totalBytes).toInt()
        return p.coerceIn(0, 100)
    }
}

/**
 * Human-readable reasons for DownloadManager failure codes. The numeric
 * values mirror android.app.DownloadManager.ERROR_* (kept as literals so
 * this file stays Android-free and JVM-testable).
 */
object UpdateFailureReason {
    fun forCode(code: Int): String = when (code) {
        1006 -> "Not enough storage space for the update."
        1001 -> "Could not save the update file."
        1004, 1008 -> "Network error while downloading the update."
        1002 -> "The update server gave an unexpected response."
        1005 -> "Too many redirects from the update server."
        1007 -> "Download storage unavailable."
        1009 -> "An old update file is in the way."
        else -> "Download failed (code $code). Check your connection and retry."
    }
}
