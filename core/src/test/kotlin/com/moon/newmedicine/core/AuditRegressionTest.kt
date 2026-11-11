package com.moon.newmedicine.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Robustness-audit regression guards (added 2026-10-19).
 *
 * Two halves:
 *  A. Manifest-backed guards — pure-JVM reads of
 *     `app/src/main/AndroidManifest.xml` (run from the project root, per
 *     BUILD.md). These lock in fixes whose logic lives outside the pure
 *     core module (manifest flags, manifest<->receiver contracts).
 *  B. Engine-level guards for the v1.13 "YES landed on the wrong session"
 *     follow-up: every UI/receiver action is dose-id scoped, and a stale
 *     action on a non-PENDING dose (stopped/completed session, double tap)
 *     must be a safe no-op that schedules nothing.
 *
 * NOTE on `bootReceiver handles every manifest-declared action`: it is
 * EXPECTED RED until the Part-A audit finding is fixed (BootReceiver checks
 * ACTION_TIME_CHANGED while its manifest filter declares TIME_SET, so clock
 * changes never reconcile alarms). The engine agent's fix turns it green.
 */
class AuditRegressionTest {

    // ------------------------------------------------- manifest-backed guards

    private fun manifestText(): String {
        val f = File("app/src/main/AndroidManifest.xml")
        assertTrue(
            "AndroidManifest.xml not found at ${f.absolutePath} — run the suite from the project root (~/workspace/new-medicine)",
            f.isFile,
        )
        return f.readText()
    }

    private fun receiverBlock(manifest: String, name: String): String {
        val idx = manifest.indexOf("android:name=\"$name\"")
        assertTrue("receiver $name not found in AndroidManifest.xml", idx >= 0)
        val start = manifest.lastIndexOf("<receiver", idx)
        val end = manifest.indexOf("</receiver>", idx)
        assertTrue("malformed <receiver> block for $name", start >= 0 && end > start)
        return manifest.substring(start, end)
    }

    @Test
    fun `v1_1 regression - UpdateDownloadReceiver is exported`() {
        // v1.1 "invisible update download": with exported=false the system
        // DownloadManager (a different UID) could never deliver
        // DOWNLOAD_COMPLETE to the receiver on API 31+.
        val block = receiverBlock(manifestText(), ".update.UpdateDownloadReceiver")
        assertTrue(
            "UpdateDownloadReceiver must declare android:exported=\"true\" or update downloads stay invisible",
            block.contains("android:exported=\"true\""),
        )
    }

    @Test
    fun `PartA - BootReceiver handles every action its manifest filter declares - EXPECTED RED until TIME_SET fix`() {
        // Part-A audit finding (2026-10-19): the manifest filter declares
        // android.intent.action.TIME_SET but BootReceiver's when() only
        // matches Intent.ACTION_TIME_CHANGED ("...TIME_CHANGED"), so the
        // receiver early-returns and clock changes never reconcile alarms.
        val block = receiverBlock(manifestText(), ".alarm.BootReceiver")
        val actions = Regex("""<action android:name="([^"]+)"\s*/>""")
            .findAll(block).map { it.groupValues[1] }.toList()
        assertTrue("BootReceiver must declare at least one intent-filter action", actions.isNotEmpty())
        val src = File("app/src/main/kotlin/com/moon/newmedicine/alarm/BootReceiver.kt")
        assertTrue("BootReceiver.kt not found", src.isFile)
        val receiverSrc = src.readText()
        val unhandled = actions.filter { action ->
            val constSuffix = action.substringAfterLast(".") // e.g. TIME_SET
            !receiverSrc.contains(constSuffix)
        }
        assertTrue(
            "BootReceiver ignores manifest-declared actions: $unhandled — add them to the when() in BootReceiver.onReceive",
            unhandled.isEmpty(),
        )
    }

    // --------------------------- stale-action guards (Part B item 13, data layer)

    private fun dose(
        id: Long = 1L,
        status: DoseStatus = DoseStatus.PENDING,
        dueAt: Long = 1_000L,
    ) = Dose(
        id = id,
        sessionId = 9L,
        dropNumber = 1,
        kind = DoseKind.REGULAR,
        dayKey = "2026-10-19",
        dueAt = dueAt,
        status = status,
    )

    @Test
    fun `stale YES on CANCELLED dose (stopped session) changes nothing and schedules nothing`() {
        // The notification for a stopped session's dose can still be in the
        // tray; tapping YES must be a pure no-op.
        val res = ScheduleEngine.confirmDose(
            dose(status = DoseStatus.CANCELLED), nowMillis = 5_000L,
            settings = DoseSettings(), isLastDrop = false,
        )
        assertTrue(res.unchanged)
        assertNull(res.nextDueAt)
        assertFalse(res.notDueYet)
    }

    @Test
    fun `stale YES on MISSED dose changes nothing and schedules nothing`() {
        val res = ScheduleEngine.confirmDose(
            dose(status = DoseStatus.MISSED), nowMillis = 5_000L,
            settings = DoseSettings(), isLastDrop = false,
        )
        assertTrue(res.unchanged)
        assertNull(res.nextDueAt)
    }

    @Test
    fun `stale YES on already-CONFIRMED dose stays idempotent`() {
        val res = ScheduleEngine.confirmDose(
            dose(status = DoseStatus.CONFIRMED), nowMillis = 5_000L,
            settings = DoseSettings(), isLastDrop = false,
        )
        assertTrue(res.unchanged)
        assertNull(res.nextDueAt)
    }

    @Test
    fun `stale NO on non-pending dose throws - repo converts it to a notification cancel`() {
        // DoseActionReceiver catches IllegalArgumentException and cancels the
        // stale notification; the engine must refuse loudly, never write.
        for (s in listOf(DoseStatus.CONFIRMED, DoseStatus.MISSED, DoseStatus.CANCELLED)) {
            try {
                ScheduleEngine.declineDose(dose(status = s), 5_000L, DoseSettings())
                fail("declineDose on a $s dose must throw")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `canRespond is false for every non-pending status`() {
        for (s in listOf(DoseStatus.CONFIRMED, DoseStatus.MISSED, DoseStatus.CANCELLED)) {
            assertFalse("canRespond must be false for $s", ScheduleEngine.canRespond(dose(status = s), 5_000L))
        }
    }
}
