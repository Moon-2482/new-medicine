package com.moon.newmedicine.core

/**
 * Local backup export/import. Produces/consumes a single JSON document with
 * everything the app needs to restore: settings, sessions and doses.
 *
 * Fully offline: no account, no cloud, no network. Hand-rolled JSON (no
 * third-party dependency) so the core module stays dependency-free.
 */
object BackupJson {

    const val BACKUP_VERSION = 1

    data class BackupData(
        val settings: DoseSettings,
        val sessions: List<DoseSession>,
        val doses: List<Dose>,
    )

    sealed interface ImportResult {
        data class Ok(val data: BackupData) : ImportResult
        data class Error(val message: String) : ImportResult
    }

    // ------------------------------------------------------------------ write

    fun exportBackup(sessions: List<DoseSession>, doses: List<Dose>, settings: DoseSettings): String {
        val sb = StringBuilder()
        sb.append("{\"version\":").append(BACKUP_VERSION).append(",\"settings\":")
        writeSettings(sb, settings)
        sb.append(",\"sessions\":[")
        sessions.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(s.id)
                .append(",\"type\":").append(q(s.type.name))
                .append(",\"dayKey\":").append(q(s.dayKey))
                .append(",\"startedAt\":").append(s.startedAt)
                .append(",\"status\":").append(q(s.status.name))
                .append('}')
        }
        sb.append("],\"doses\":[")
        doses.forEachIndexed { i, d ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(d.id)
                .append(",\"sessionId\":").append(d.sessionId)
                .append(",\"dropNumber\":").append(d.dropNumber)
                .append(",\"kind\":").append(q(d.kind.name))
                .append(",\"dayKey\":").append(q(d.dayKey))
                .append(",\"dueAt\":").append(d.dueAt)
                .append(",\"confirmedAt\":").append(d.confirmedAt?.toString() ?: "null")
                .append(",\"status\":").append(q(d.status.name))
                .append(",\"retryCount\":").append(d.retryCount)
                .append(",\"nextRetryAt\":").append(d.nextRetryAt?.toString() ?: "null")
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun writeSettings(sb: StringBuilder, s: DoseSettings) {
        sb.append('{')
        fun kv(name: String, value: String, first: Boolean = false) {
            if (!first) sb.append(',')
            sb.append(q(name)).append(':').append(value)
        }
        kv("doseIntervalMillis", s.doseIntervalMillis.toString(), first = true)
        kv("retryIntervalMillis", s.retryIntervalMillis.toString())
        kv("cutoffMinutesOfDay", s.cutoffMinutesOfDay.toString())
        kv("morningDropCount", s.morningDropCount.toString())
        kv("afternoonDropCount", s.afternoonDropCount.toString())
        kv("eveningDropCount", s.eveningDropCount.toString())
        kv("specialDropEnabled", s.specialDropEnabled.toString())
        kv("specialDropPosition", s.specialDropPosition.toString())
        kv("afternoonStartMinutesOfDay", s.afternoonStartMinutesOfDay.toString())
        kv("eveningStartMinutesOfDay", s.eveningStartMinutesOfDay.toString())
        kv("autoStartAfternoon", s.autoStartAfternoon.toString())
        kv("autoStartEvening", s.autoStartEvening.toString())
        kv("overdueGraceMillis", s.overdueGraceMillis.toString())
        kv("soundEnabled", s.soundEnabled.toString())
        kv("vibrationEnabled", s.vibrationEnabled.toString())
        kv("wakeWindowStartMinutesOfDay", s.wakeWindowStartMinutesOfDay.toString())
        kv("wakeWindowEndMinutesOfDay", s.wakeWindowEndMinutesOfDay.toString())
        kv("wakeCheckIntervalMinutes", s.wakeCheckIntervalMinutes.toString())
        kv("nagEnabled", s.nagEnabled.toString())
        kv("nagIntervalMillis", s.nagIntervalMillis.toString())
        kv("callDeferGapMillis", s.callDeferGapMillis.toString())
        sb.append('}')
    }

    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    // ------------------------------------------------------------------ read

    fun importBackup(json: String): ImportResult {
        val parsed = try {
            JsonParser(json).parse()
        } catch (e: Exception) {
            return ImportResult.Error("Invalid backup file: not valid JSON (${e.message}).")
        }
        if (parsed !is Map<*, *>) return ImportResult.Error("Invalid backup file: top-level JSON must be an object.")
        val version = (parsed["version"] as? Number)?.toInt()
            ?: return ImportResult.Error("Invalid backup file: missing version.")
        if (version != BACKUP_VERSION) {
            return ImportResult.Error("Unsupported backup version $version (this app reads version $BACKUP_VERSION).")
        }
        val settings = try {
            readSettings(parsed["settings"] as? Map<*, *> ?: return ImportResult.Error("Invalid backup: missing settings."))
        } catch (e: IllegalArgumentException) {
            return ImportResult.Error("Invalid backup settings: ${e.message}")
        }
        val sessions = mutableListOf<DoseSession>()
        val doses = mutableListOf<Dose>()
        try {
            val sessionList = parsed["sessions"] as? List<*>
                ?: return ImportResult.Error("Invalid backup: missing sessions list.")
            for (raw in sessionList) {
                val m = raw as? Map<*, *> ?: return ImportResult.Error("Invalid backup: bad session entry.")
                sessions.add(
                    DoseSession(
                        id = reqLong(m, "id"),
                        type = enumValueOf<SessionType>(reqString(m, "type")),
                        dayKey = reqString(m, "dayKey"),
                        startedAt = reqLong(m, "startedAt"),
                        status = enumValueOf<SessionStatus>(reqString(m, "status")),
                    )
                )
            }
            val doseList = parsed["doses"] as? List<*>
                ?: return ImportResult.Error("Invalid backup: missing doses list.")
            for (raw in doseList) {
                val m = raw as? Map<*, *> ?: return ImportResult.Error("Invalid backup: bad dose entry.")
                doses.add(
                    Dose(
                        id = reqLong(m, "id"),
                        sessionId = reqLong(m, "sessionId"),
                        dropNumber = reqInt(m, "dropNumber"),
                        kind = enumValueOf<DoseKind>(reqString(m, "kind")),
                        dayKey = reqString(m, "dayKey"),
                        dueAt = reqLong(m, "dueAt"),
                        confirmedAt = optLong(m, "confirmedAt"),
                        status = enumValueOf<DoseStatus>(reqString(m, "status")),
                        retryCount = reqInt(m, "retryCount"),
                        nextRetryAt = optLong(m, "nextRetryAt"),
                    )
                )
            }
        } catch (e: IllegalArgumentException) {
            return ImportResult.Error("Invalid backup data: ${e.message}")
        }
        // Cross-record sanity checks.
        val sessionIds = sessions.map { it.id }.toSet()
        for (d in doses) {
            if (d.sessionId !in sessionIds) {
                return ImportResult.Error("Invalid backup: dose id=${d.id} references unknown session ${d.sessionId}.")
            }
            if (d.dueAt < 0) return ImportResult.Error("Invalid backup: dose id=${d.id} has a negative due time.")
            if (d.confirmedAt != null && d.status != DoseStatus.CONFIRMED) {
                return ImportResult.Error("Invalid backup: dose id=${d.id} has a confirmation time but is not CONFIRMED.")
            }
            if (d.status == DoseStatus.CONFIRMED && d.confirmedAt == null) {
                return ImportResult.Error("Invalid backup: dose id=${d.id} is CONFIRMED without a confirmation time.")
            }
        }
        val violations = ScheduleEngine.checkSpecialInvariants(
            doses,
            sessions.associateBy { it.id },
        )
        if (violations.isNotEmpty()) {
            return ImportResult.Error("Invalid backup: " + violations.joinToString("; "))
        }
        return ImportResult.Ok(BackupData(settings, sessions, doses))
    }

    private fun readSettings(m: Map<*, *>): DoseSettings {
        fun lng(k: String) = (m[k] as? Number)?.toLong() ?: throw IllegalArgumentException("settings.$k missing/not a number")
        fun int(k: String) = (m[k] as? Number)?.toInt() ?: throw IllegalArgumentException("settings.$k missing/not a number")
        fun bool(k: String) = (m[k] as? Boolean) ?: throw IllegalArgumentException("settings.$k missing/not a boolean")
        // Wake-detection keys are optional so backups written before this
        // feature existed still import (they fall back to the defaults).
        fun optInt(k: String, default: Int) = (m[k] as? Number)?.toInt() ?: default
        // v2.0: nag keys are optional for the same reason (pre-nag backups).
        fun optLong(k: String, default: Long) = (m[k] as? Number)?.toLong() ?: default
        fun optBool(k: String, default: Boolean) = (m[k] as? Boolean) ?: default
        val s = DoseSettings(
            doseIntervalMillis = lng("doseIntervalMillis"),
            retryIntervalMillis = lng("retryIntervalMillis"),
            cutoffMinutesOfDay = int("cutoffMinutesOfDay"),
            morningDropCount = int("morningDropCount"),
            afternoonDropCount = int("afternoonDropCount"),
            eveningDropCount = int("eveningDropCount"),
            specialDropEnabled = bool("specialDropEnabled"),
            specialDropPosition = int("specialDropPosition"),
            afternoonStartMinutesOfDay = int("afternoonStartMinutesOfDay"),
            eveningStartMinutesOfDay = int("eveningStartMinutesOfDay"),
            autoStartAfternoon = bool("autoStartAfternoon"),
            autoStartEvening = bool("autoStartEvening"),
            overdueGraceMillis = lng("overdueGraceMillis"),
            soundEnabled = bool("soundEnabled"),
            vibrationEnabled = bool("vibrationEnabled"),
            wakeWindowStartMinutesOfDay = optInt("wakeWindowStartMinutesOfDay", 6 * 60),
            wakeWindowEndMinutesOfDay = optInt("wakeWindowEndMinutesOfDay", 11 * 60),
            wakeCheckIntervalMinutes = optInt("wakeCheckIntervalMinutes", 15),
            nagEnabled = optBool("nagEnabled", true),
            nagIntervalMillis = optLong("nagIntervalMillis", 10L * 60 * 1000),
            // v2.10: optional so pre-v2.10 backups still import (default
            // 1 min). Sanitized: a foreign value falls back to the
            // default preset.
            callDeferGapMillis = ScheduleEngine.sanitizeCallDeferGap(
                optLong("callDeferGapMillis", 60L * 1000)
            ),
        )
        require(s.doseIntervalMillis > 0 && s.retryIntervalMillis > 0) { "intervals must be positive" }
        require(s.cutoffMinutesOfDay in 1..(24 * 60)) { "cutoff out of range" }
        require(s.wakeWindowStartMinutesOfDay in 0..(24 * 60)) { "wake window start out of range" }
        require(s.wakeWindowEndMinutesOfDay in 0..(24 * 60)) { "wake window end out of range" }
        require(s.wakeCheckIntervalMinutes in 1..120) { "wake check interval out of range" }
        require(s.nagIntervalMillis in 5L * 60 * 1000..60L * 60 * 1000) { "nag interval out of range" }
        return s
    }

    private fun reqString(m: Map<*, *>, k: String): String =
        m[k] as? String ?: throw IllegalArgumentException("$k missing/not a string")

    private fun reqLong(m: Map<*, *>, k: String): Long =
        (m[k] as? Number)?.toLong() ?: throw IllegalArgumentException("$k missing/not a number")

    private fun reqInt(m: Map<*, *>, k: String): Int =
        (m[k] as? Number)?.toInt() ?: throw IllegalArgumentException("$k missing/not a number")

    private fun optLong(m: Map<*, *>, k: String): Long? = (m[k] as? Number)?.toLong()

    // Minimal recursive-descent JSON parser (objects, arrays, strings, numbers,
    // booleans, null) — just enough for documents this module writes.
    private class JsonParser(private val text: String) {
        private var pos = 0

        fun parse(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            if (pos != text.length) throw IllegalArgumentException("trailing characters at $pos")
            return v
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= text.length) throw IllegalArgumentException("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expect("true", true)
                'f' -> expect("false", false)
                'n' -> expect("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("unexpected char '$c' at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                if (peek() != ':') throw IllegalArgumentException("expected ':' at $pos")
                pos++
                map[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw IllegalArgumentException("unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) throw IllegalArgumentException("bad escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw IllegalArgumentException("bad \\u escape")
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Number {
            val start = pos
            while (pos < text.length && text[pos] in "-+0123456789.eE") pos++
            val token = text.substring(start, pos)
            return token.toLongOrNull() ?: token.toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number '$token'")
        }

        private fun expect(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw IllegalArgumentException("expected '$word' at $pos")
            pos += word.length
            return value
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else 0.toChar()

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
