package com.moon.newmedicine.core.update

/**
 * Pure in-app update logic. Zero Android and zero network dependencies on
 * purpose: the network fetch lives in the app module ([UpdateFetcher]), this
 * object only decides from an already-fetched body, so every decision is
 * unit-testable with canned JSON.
 *
 * All failures (offline, timeout, malformed JSON) degrade to
 * [UpdateCheckResult.Failed] — the check is always silent and never blocks
 * the UI.
 */

/** Parsed contents of version.json. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data object Failed : UpdateCheckResult()
}

object UpdateChecker {
    /**
     * Single endpoint constant — change here if the hosting ever moves.
     * NOTE: the parent publishes version.json + the APK to this repo after
     * each release build; until then every check simply fails silently.
     */
    const val UPDATE_URL =
        "https://raw.githubusercontent.com/Moon-2482/new-medicine/main/version.json"

    /** Pure decision from a fetched body (null = fetch failed) and the installed versionCode. */
    fun decide(body: String?, installedVersionCode: Int): UpdateCheckResult {
        val info = body?.let { parse(it) } ?: return UpdateCheckResult.Failed
        return if (info.versionCode > installedVersionCode) UpdateCheckResult.Available(info)
        else UpdateCheckResult.UpToDate
    }

    /**
     * Minimal dependency-free parse of the flat version.json object:
     * {"versionCode":2,"versionName":"1.1","apkUrl":"https://…","notes":"…"}.
     * Returns null when malformed or any required field is missing.
     */
    fun parse(body: String): UpdateInfo? {
        val versionCode = extractInt(body, "versionCode") ?: return null
        val versionName = extractString(body, "versionName") ?: return null
        val apkUrl = extractString(body, "apkUrl") ?: return null
        val notes = extractString(body, "notes") ?: ""
        if (!apkUrl.startsWith("https://")) return null
        return UpdateInfo(versionCode, versionName, apkUrl, notes)
    }

    private fun extractInt(body: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(body)
            ?.groupValues?.get(1)?.toIntOrNull()

    private fun extractString(body: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            ?: return null
        return unescape(m.groupValues[1])
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
