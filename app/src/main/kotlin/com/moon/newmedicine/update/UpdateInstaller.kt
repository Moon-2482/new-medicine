package com.moon.newmedicine.update

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.moon.newmedicine.R
import com.moon.newmedicine.core.update.UpdateDownloadPolicy
import com.moon.newmedicine.core.update.UpdateInfo
import com.moon.newmedicine.core.update.UpdateIntegrity
import java.io.File

/**
 * Downloads the update APK via DownloadManager and installs it.
 *
 * Flow: [downloadApk] (from the update dialog) -> [UpdateDownloadReceiver]
 * fires on completion -> [installApk]. If the system blocks installs from
 * this app ("unknown apps" not allowed), the APK path is remembered and the
 * user is guided to the system toggle; [retryBlockedInstallIfAllowed] (called
 * from MainActivity.onResume) finishes the install once allowed.
 *
 * v1.7 hardening (the v1.6 INSTALL_FAILED_INVALID_APK report): downloads
 * used to go to one FIXED filename with no in-flight guard, so a double-tap
 * on "Download & Install" (or Cancel→Download, or activity recreation)
 * started two concurrent DownloadManager streams writing interleaved bytes
 * to the same file — DM still reported STATUS_SUCCESSFUL and the corrupt
 * file went to the installer. Now: an in-flight download is never
 * re-enqueued ([UpdateDownloadPolicy.shouldReuseDownload]), every download
 * gets a UNIQUE filename, and [installApk] runs a ZIP EOCD integrity gate
 * before the installer ever sees the file.
 */
object UpdateInstaller {
    /** Legacy pre-v1.7 fixed filename — only used as a one-time fallback. */
    const val APK_FILE_NAME = "DropsReminder-update.apk"
    private const val PREFS = "update_prefs"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_APK_NAME = "apk_name"
    private const val KEY_BLOCKED_APK = "blocked_apk"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun downloadsDir(context: Context): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    /** The unique APK filename of the current in-flight/just-finished download, if any. */
    fun storedApkName(context: Context): String? =
        prefs(context).getString(KEY_APK_NAME, null)

    /** Forgets the stored unique APK name (after a download completes / is discarded). */
    fun clearStoredApkName(context: Context) {
        prefs(context).edit().remove(KEY_APK_NAME).apply()
    }

    /**
     * Resolves the current update APK: the stored unique name, else the
     * legacy fixed name as a one-time fallback (a pre-v1.7 leftover is
     * untrustworthy — the concurrent-write corruption class — so it is
     * deleted as hygiene instead of being installed).
     */
    fun updateApkFile(context: Context): File {
        val dir = downloadsDir(context)
        val stored = storedApkName(context)
        if (stored != null && dir != null) return File(dir, stored)
        val legacy = if (dir != null) File(dir, APK_FILE_NAME) else File(APK_FILE_NAME)
        try {
            if (legacy.isFile) legacy.delete()
        } catch (e: Exception) {
            // ignore
        }
        return legacy
    }

    /**
     * Resolves the EXACT file a completed download wrote: the
     * DownloadManager's local URI for the download id first, the stored
     * unique filename as fallback. Never guesses from a constant.
     */
    fun completedFile(context: Context, downloadId: Long): File? {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val q = DownloadManager.Query().setFilterById(downloadId)
            dm.query(q).use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    if (idx != -1) {
                        val uri = Uri.parse(c.getString(idx))
                        if (uri.scheme == "file") {
                            val f = File(uri.path ?: "")
                            if (f.isFile) return f
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // fall through to the stored-name fallback
        }
        val f = updateApkFile(context)
        return if (f.isFile) f else null
    }

    fun pendingDownloadId(context: Context): Long =
        prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)

    fun clearPendingDownloadId(context: Context) {
        prefs(context).edit().remove(KEY_DOWNLOAD_ID).apply()
    }

    /**
     * Enqueue the APK download. Returns the DownloadManager id, or -1 on
     * failure. If a download is already in flight (pending/running/paused),
     * its id is returned and NOTHING is enqueued — exactly one download at
     * a time, so two streams can never interleave into one file.
     */
    fun downloadApk(context: Context, info: UpdateInfo): Long {
        val pending = pendingDownloadId(context)
        if (pending != -1L) {
            val state = downloadState(context, pending)
            if (state != null && UpdateDownloadPolicy.shouldReuseDownload(state.status)) {
                return pending
            }
            // Stale id (finished/failed/vanished without our receiver):
            // forget it before starting fresh.
            clearPendingDownloadId(context)
        }
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            // Unique file per download: kills the filename-collision class
            // entirely, even if the guard above ever missed.
            val apkName = "DropsReminder-update-${System.currentTimeMillis()}.apk"
            val req = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
                setTitle("New Medicine update")
                setDescription("Downloading v${info.versionName}…")
                // Visible while downloading AND after completion, so the
                // download is never a black box (the v1.2 "nothing happened"
                // report: VISIBILITY_VISIBLE alone left no trace).
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalFilesDir(
                    context, Environment.DIRECTORY_DOWNLOADS, apkName
                )
            }
            val id = dm.enqueue(req)
            prefs(context).edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_APK_NAME, apkName)
                .apply()
            id
        } catch (e: Exception) {
            -1L
        }
    }

    /** Cancels an in-flight update download and forgets it. */
    fun cancelDownload(context: Context, downloadId: Long) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(downloadId)
        } catch (e: Exception) {
            // ignore — best effort
        }
        clearPendingDownloadId(context)
    }

    /** Live snapshot of an in-flight download, or null when unknown. */
    data class DownloadState(
        /** One of DownloadManager.STATUS_*. */
        val status: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        /** DownloadManager.ERROR_* when status is STATUS_FAILED, else 0. */
        val reason: Int,
    )

    fun downloadState(context: Context, downloadId: Long): DownloadState? {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val q = DownloadManager.Query().setFilterById(downloadId)
            dm.query(q).use { c ->
                if (!c.moveToFirst()) return null
                DownloadState(
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    downloadedBytes = c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    ),
                    totalBytes = c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    ),
                    reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Deletes a partial/failed APK file so a retry starts clean, and forgets
     * its stored name.
     */
    fun deletePartialApk(context: Context) {
        try {
            val dir = downloadsDir(context)
            val stored = storedApkName(context)
            if (dir != null && stored != null) File(dir, stored).delete()
            // Legacy fixed name, in case a pre-v1.7 attempt left one behind.
            if (dir != null) {
                val legacy = File(dir, APK_FILE_NAME)
                if (legacy.name != stored) legacy.delete()
            }
        } catch (e: Exception) {
            // ignore
        }
        clearStoredApkName(context)
    }

    /**
     * Deletes stale update APKs, keeping the in-flight download's file and
     * the blocked-install file (retried from MainActivity.onResume).
     * Called on app start — after a successful update install the leftover
     * APK is tidied away here.
     */
    fun sweepStaleUpdateApks(context: Context) {
        try {
            val dir = downloadsDir(context) ?: return
            val keep = mutableSetOf<String>()
            storedApkName(context)?.let { keep.add(it) }
            prefs(context).getString(KEY_BLOCKED_APK, null)?.let { keep.add(it) }
            dir.listFiles { f ->
                f.isFile && f.name.startsWith("DropsReminder-update-") && f.name !in keep
            }?.forEach { f ->
                try {
                    f.delete()
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            // ignore — best effort hygiene
        }
    }

    fun installedVersionCode(context: Context): Int {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pi = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(context.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    @Suppress("DEPRECATION")
    fun installedVersionName(context: Context): String {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0)
                ).versionName
            } else {
                pm.getPackageInfo(context.packageName, 0).versionName
            }
        } catch (e: Exception) {
            "?"
        } ?: "?"
    }

    /**
     * Fires the package installer for [apkFile], or guides the user when blocked.
     *
     * Integrity gate FIRST: a corrupt download (truncated / interleaved
     * concurrent writes — the v1.6 INSTALL_FAILED_INVALID_APK report) is
     * deleted and never reaches the installer; the user gets the failure
     * dialog with Retry instead of Android's "package appears to be
     * invalid".
     */
    fun installApk(context: Context, apkFile: File) {
        if (!UpdateIntegrity.isValidApk(apkFile)) {
            try {
                apkFile.delete()
            } catch (e: Exception) {
                // ignore
            }
            deletePartialApk(context)
            sweepStaleUpdateApks(context)
            clearPendingDownloadId(context)
            if (context is Activity && !context.isFinishing) {
                UpdateProgressDialog.showCorruptFailure(context)
            } else {
                Toast.makeText(
                    context,
                    "Update file kharab mili — app khol kar dobara koshish karein.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                rememberBlocked(context, apkFile.name)
                if (context is Activity && !context.isFinishing) {
                    showUnknownSourcesDialog(context)
                } else {
                    Toast.makeText(
                        context,
                        "Allow \"Install unknown apps\" for New Medicine, then reopen the app.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return
            }
            val uri = ApkFileProvider.apkUri(apkFile.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Called from MainActivity.onResume: finishes a blocked install once allowed. */
    fun retryBlockedInstallIfAllowed(activity: Activity) {
        val name = prefs(activity).getString(KEY_BLOCKED_APK, null) ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            if (!activity.isFinishing) showUnknownSourcesDialog(activity)
            return
        }
        prefs(activity).edit().remove(KEY_BLOCKED_APK).apply()
        val dir = downloadsDir(activity) ?: return
        val file = File(dir, name)
        // The stored unique name is resolved the same way as every other
        // install path; a missing file means it was already cleaned up.
        if (file.isFile) installApk(activity, file)
    }

    private fun rememberBlocked(context: Context, fileName: String) {
        prefs(context).edit().putString(KEY_BLOCKED_APK, fileName).apply()
    }

    /** Opens the system "Install unknown apps" screen for this app. */
    fun openUnknownAppSettings(activity: Activity) {
        try {
            val i = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(activity, "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUnknownSourcesDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Allow app installs")
            .setMessage(
                "Android blocked the update install. Please allow \"Install unknown apps\" " +
                    "for New Medicine, then come back — the install will continue automatically."
            )
            .setPositiveButton("Open settings") { _, _ ->
                openUnknownAppSettings(activity)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
