package com.moon.newmedicine.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-declared receiver: fires when the DownloadManager finishes our
 * update APK download and hands the file to [UpdateInstaller.installApk].
 *
 * Must be exported=true: the DOWNLOAD_COMPLETE broadcast is sent by the
 * system DownloadManager (a different UID), and on API 31+ a non-exported
 * receiver never sees it — the v1.2 "downloaded but nothing happened"
 * report.
 *
 * v1.7: resolves the EXACT completed file (DM local URI first, stored
 * unique name as fallback) — never a guessed constant — and the integrity
 * gate inside [UpdateInstaller.installApk] rejects corrupt files before
 * the installer sees them (the v1.6 INSTALL_FAILED_INVALID_APK report).
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L || id != UpdateInstaller.pendingDownloadId(context)) return
        UpdateInstaller.clearPendingDownloadId(context)
        // Only install a fully-downloaded file; a failed download leaves no
        // partial APK behind to confuse a retry.
        val state = UpdateInstaller.downloadState(context, id)
        if (state?.status != DownloadManager.STATUS_SUCCESSFUL) {
            UpdateInstaller.deletePartialApk(context)
            return
        }
        val file = UpdateInstaller.completedFile(context, id)
        UpdateInstaller.clearStoredApkName(context)
        if (file != null) {
            UpdateInstaller.installApk(context, file)
        }
        // else: nothing to install — the next app start re-offers the update.
    }
}
