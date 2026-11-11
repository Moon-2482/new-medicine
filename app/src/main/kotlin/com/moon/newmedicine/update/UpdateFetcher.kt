package com.moon.newmedicine.update

import com.moon.newmedicine.core.update.UpdateCheckResult
import com.moon.newmedicine.core.update.UpdateChecker
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network fetch for the in-app update check. Call ONLY from a background
 * thread. Every failure (offline, timeout, bad response) degrades to
 * [UpdateCheckResult.Failed] — the check is always silent and never throws.
 */
object UpdateFetcher {
    private const val TIMEOUT_MS = 10_000

    fun fetchBody(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(UpdateChecker.UPDATE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                instanceFollowRedirects = true
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /** Full check: fetch (background) + pure decide. Never throws. */
    fun check(installedVersionCode: Int): UpdateCheckResult =
        try {
            UpdateChecker.decide(fetchBody(), installedVersionCode)
        } catch (e: Exception) {
            UpdateCheckResult.Failed
        }
}
