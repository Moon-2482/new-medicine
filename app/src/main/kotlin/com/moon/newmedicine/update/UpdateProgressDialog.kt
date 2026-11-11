package com.moon.newmedicine.update

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.moon.newmedicine.R
import com.moon.newmedicine.core.update.UpdateFailureReason
import com.moon.newmedicine.core.update.UpdateInfo
import com.moon.newmedicine.core.update.UpdateProgress

/**
 * Visible update download: a modal dialog with a live % bar and a Cancel
 * button. This is the direct fix for the v1.2 "tapped Download & Install
 * and then nothing happened" report — the download is now impossible to
 * miss, and every failure explains itself with a Retry option.
 *
 * Polls DownloadManager every 500 ms on the main thread. On success the
 * install intent fires immediately from here (the manifest receiver remains
 * as a backup for downloads that finish while the dialog is gone).
 *
 * v1.7: the Download action is single-shot — a second tap while a download
 * dialog is already up is ignored (the v1.6 corruption class started with
 * two concurrent downloads of one file).
 */
object UpdateProgressDialog {

    /** True while our progress dialog is on screen; blocks double-taps. */
    private var dialogActive = false

    fun show(activity: Activity, info: UpdateInfo) {
        if (dialogActive) return
        // Unknown-apps guard BEFORE downloading: one clear dialog instead of
        // a silent dead end after the bytes arrive.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Ek ijazat chahiye")
                .setMessage(
                    "Update install karne ke liye \"Install unknown apps\" allow " +
                        "karna parega. Pehle download shuru kar dein — install " +
                        "ke waqt ijazat maang li jayegi."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    UpdateInstaller.openUnknownAppSettings(activity)
                    startDownload(activity, info)
                }
                .setNegativeButton("Pehle download karein") { _, _ ->
                    startDownload(activity, info)
                }
                .show()
            return
        }
        startDownload(activity, info)
    }

    /**
     * Shown when the downloaded file fails the integrity gate (truncated /
     * interleaved bytes — the v1.6 INSTALL_FAILED_INVALID_APK report).
     * Retry re-checks for the update and starts a fresh download.
     */
    fun showCorruptFailure(activity: Activity) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("Update file kharab mili")
            .setMessage(
                "Download mukammal nahi hui — file kharab hai, is liye " +
                    "install nahi ki. Dobara koshish karein."
            )
            .setPositiveButton("Retry") { _, _ ->
                UpdatePrompt.checkManual(activity)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDownload(activity: Activity, info: UpdateInfo) {
        if (dialogActive) return
        // downloadApk itself refuses to start a second concurrent download
        // (returns the in-flight id); re-attaching a progress dialog to it
        // is safe and shows the user what is happening.
        val downloadId = UpdateInstaller.downloadApk(activity, info)
        if (downloadId == -1L) {
            showFailure(activity, info, "Download shuru nahi ho saka.")
            return
        }

        val handler = Handler(Looper.getMainLooper())
        lateinit var dialog: AlertDialog

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(activity).apply {
            text = "Downloading v${info.versionName}…"
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(label)
        }

        var finished = false
        val poll = object : Runnable {
            override fun run() {
                if (finished || activity.isFinishing) {
                    // A5: a rotation (or a dead activity) while polling must
                    // not leak the dialog and brick dialogActive forever:
                    // stop polling and dismiss what we can.
                    finished = true
                    try {
                        dialog.dismiss()
                    } catch (e: Exception) {
                        // Already gone or never shown — dialogActive resets
                        // via the dismiss listener when possible.
                    }
                    return
                }
                val state = UpdateInstaller.downloadState(activity, downloadId)
                if (state == null) {
                    // Download vanished (cancelled elsewhere): close quietly.
                    finished = true
                    dialog.dismiss()
                    return
                }
                when (state.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        finished = true
                        dialog.dismiss()
                        // Resolve the EXACT completed file (never a guessed
                        // constant); installApk runs the integrity gate.
                        val file = UpdateInstaller.completedFile(activity, downloadId)
                        if (file != null) {
                            // A4: forget the download BEFORE installing — the
                            // manifest UpdateDownloadReceiver must no-op
                            // instead of double-firing the installer.
                            UpdateInstaller.clearPendingDownloadId(activity)
                            UpdateInstaller.clearStoredApkName(activity)
                            UpdateInstaller.installApk(activity, file)
                        } else {
                            showFailure(
                                activity, info,
                                "Update file nahi mili — dobara koshish karein.",
                            )
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        finished = true
                        dialog.dismiss()
                        showFailure(
                            activity, info,
                            UpdateFailureReason.forCode(state.reason),
                        )
                    }
                    else -> {
                        val p = UpdateProgress.percent(
                            state.downloadedBytes, state.totalBytes
                        )
                        if (p >= 0) {
                            bar.isIndeterminate = false
                            bar.progress = p
                            label.text = "Downloading v${info.versionName}… $p%"
                        } else {
                            bar.isIndeterminate = true
                            label.text = "Downloading v${info.versionName}…"
                        }
                        handler.postDelayed(this, 500)
                    }
                }
            }
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("Update download ho rahi hai")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                finished = true
                handler.removeCallbacks(poll)
                // Order matters: stop the download, delete its partial
                // file, then forget the id.
                UpdateInstaller.cancelDownload(activity, downloadId)
                UpdateInstaller.deletePartialApk(activity)
                UpdateInstaller.clearPendingDownloadId(activity)
            }
            .create()
        dialog.setOnDismissListener { dialogActive = false }
        dialogActive = true
        dialog.show()
        handler.post(poll)
    }

    private fun showFailure(activity: Activity, info: UpdateInfo, reason: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("Update download nakaam")
            .setMessage(reason)
            .setPositiveButton("Retry") { _, _ ->
                UpdateInstaller.deletePartialApk(activity)
                startDownload(activity, info)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
