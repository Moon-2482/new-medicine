package com.moon.newmedicine.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.moon.newmedicine.core.Dose
import com.moon.newmedicine.core.DoseKind
import com.moon.newmedicine.core.DoseSession
import com.moon.newmedicine.core.DoseStatus
import com.moon.newmedicine.core.SessionStatus
import com.moon.newmedicine.core.SessionType

/**
 * SQLite storage for sessions, doses and settings.
 *
 * Schema mirrors the core domain model 1:1; the repository owns all
 * read/write logic, this class only owns the schema, transactions and
 * row <-> model mapping.
 */
class DropsDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "drops.db"
        const val DATABASE_VERSION = 3
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, dayKey TEXT NOT NULL, startedAt INTEGER NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE doses (id INTEGER PRIMARY KEY AUTOINCREMENT, sessionId INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, dropNumber INTEGER NOT NULL, kind TEXT NOT NULL, dayKey TEXT NOT NULL, dueAt INTEGER NOT NULL, confirmedAt INTEGER, status TEXT NOT NULL, retryCount INTEGER NOT NULL DEFAULT 0, nextRetryAt INTEGER)")
        // DB-level once-daily special guarantee, narrowed to *live* specials:
        // a CANCELLED or MISSED special must not block a later session's
        // special (the user can never take it twice — CONFIRMED still blocks).
        db.execSQL("CREATE UNIQUE INDEX idx_special_once_per_day ON doses(dayKey) WHERE kind='SPECIAL' AND status IN ('PENDING','CONFIRMED')")
        db.execSQL("CREATE INDEX idx_doses_session ON doses(sessionId)")
        db.execSQL("CREATE INDEX idx_doses_status_day ON doses(status, dayKey)")
        // v2.0: single-active-session is a DB invariant (partial unique
        // index), so the double-start race can never create an overlap even
        // if two threads slip past the repository's in-memory guard.
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_one_active_session ON sessions(dayKey) WHERE status='ACTIVE'")
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2: the once-daily special index is narrowed to live specials
        // only, so a cancelled/missed special no longer blocks a later
        // session's special. No data is touched.
        if (oldVersion < 2) {
            db.execSQL("DROP INDEX IF EXISTS idx_special_once_per_day")
            db.execSQL("CREATE UNIQUE INDEX idx_special_once_per_day ON doses(dayKey) WHERE kind='SPECIAL' AND status IN ('PENDING','CONFIRMED')")
        }
        // v2 -> v3 (v2.0): single-active-session becomes a DB invariant.
        // The repair MUST run before the index is created — a duplicate
        // ACTIVE pair from the pre-v2.0 race would otherwise make CREATE
        // INDEX fail. Per dayKey the newest ACTIVE session (max id) is kept;
        // the rest are demoted to CANCELLED along with their PENDING doses,
        // and their stale per-dose KV flags are dropped.
        //
        // Alarm/notification cleanup for demoted rows is deliberately left
        // to the normal paths (no AlarmManager/NotificationManager access
        // here): a stale alarm for a CANCELLED dose is a silent no-op in
        // AlarmReceiver (it cancels the notification and returns), and the
        // next reconcileAlarms only arms PENDING doses of ACTIVE sessions.
        if (oldVersion < 3) {
            repairDuplicateActiveSessions(db)
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_one_active_session ON sessions(dayKey) WHERE status='ACTIVE'")
        }
    }

    /**
     * v2->v3 repair (also mirrored by the repo's import-dedupe): for every
     * dayKey with more than one ACTIVE session, keep the newest (max id)
     * and demote the rest to CANCELLED, cancelling their PENDING doses and
     * dropping their stale per-dose KV flags (`overdue_catchup_*`,
     * `nag_next_*`). Runs inside onUpgrade's own transaction.
     */
    private fun repairDuplicateActiveSessions(db: SQLiteDatabase) {
        val demoted = ArrayList<Long>()
        db.rawQuery(
            "SELECT id, dayKey FROM sessions WHERE status = 'ACTIVE' ORDER BY id DESC", null
        ).use { c ->
            val seen = HashSet<String>()
            while (c.moveToNext()) {
                if (!seen.add(c.getString(1))) demoted.add(c.getLong(0))
            }
        }
        if (demoted.isEmpty()) return
        val doseIds = ArrayList<Long>()
        for (sid in demoted) {
            db.rawQuery(
                "SELECT id FROM doses WHERE sessionId = ? AND status = 'PENDING'",
                arrayOf(sid.toString())
            ).use { c ->
                while (c.moveToNext()) doseIds.add(c.getLong(0))
            }
            db.execSQL(
                "UPDATE doses SET status = 'CANCELLED', nextRetryAt = NULL WHERE sessionId = ? AND status = 'PENDING'",
                arrayOf(sid.toString())
            )
        }
        val placeholders = demoted.joinToString(",") { "?" }
        db.execSQL(
            "UPDATE sessions SET status = 'CANCELLED' WHERE id IN ($placeholders)",
            demoted.map { it.toString() }.toTypedArray()
        )
        for (did in doseIds) {
            db.delete(
                "settings", "key = ? OR key = ?",
                arrayOf("overdue_catchup_$did", "nag_next_$did")
            )
        }
    }

    /**
     * Runs [block] inside a single SQLite transaction. The transaction is
     * committed only when [block] returns normally; any exception (or an
     * early non-local return) rolls everything back.
     */
    inline fun <T> inTransaction(block: (SQLiteDatabase) -> T): T {
        val w = writableDatabase
        w.beginTransaction()
        try {
            val result = block(w)
            w.setTransactionSuccessful()
            return result
        } finally {
            w.endTransaction()
        }
    }

    // ---------------------------------------------------------- row mapping

    fun sessionFromCursor(c: Cursor): DoseSession = DoseSession(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        type = SessionType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))),
        dayKey = c.getString(c.getColumnIndexOrThrow("dayKey")),
        startedAt = c.getLong(c.getColumnIndexOrThrow("startedAt")),
        status = SessionStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))),
    )

    fun doseFromCursor(c: Cursor): Dose {
        val confirmedAtIdx = c.getColumnIndexOrThrow("confirmedAt")
        val nextRetryAtIdx = c.getColumnIndexOrThrow("nextRetryAt")
        return Dose(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            sessionId = c.getLong(c.getColumnIndexOrThrow("sessionId")),
            dropNumber = c.getInt(c.getColumnIndexOrThrow("dropNumber")),
            kind = DoseKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),
            dayKey = c.getString(c.getColumnIndexOrThrow("dayKey")),
            dueAt = c.getLong(c.getColumnIndexOrThrow("dueAt")),
            confirmedAt = if (c.isNull(confirmedAtIdx)) null else c.getLong(confirmedAtIdx),
            status = DoseStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))),
            retryCount = c.getInt(c.getColumnIndexOrThrow("retryCount")),
            nextRetryAt = if (c.isNull(nextRetryAtIdx)) null else c.getLong(nextRetryAtIdx),
        )
    }
}
