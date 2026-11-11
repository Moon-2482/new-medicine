package com.moon.newmedicine.update

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.moon.newmedicine.R
import com.moon.newmedicine.core.update.UpdateCheckResult
import com.moon.newmedicine.core.update.UpdateInfo

/**
 * Update check UI. All checks run on a background thread and are silent on
 * failure — the user only ever sees a dialog when an update is actually
 * available (or an explicit result for a manual check).
 */
object UpdatePrompt {

    /** Automatic check on app start. Silent unless an update is available. */
    fun checkOnStart(activity: Activity) {
        Thread({
            val result = UpdateFetcher.check(UpdateInstaller.installedVersionCode(activity))
            if (result is UpdateCheckResult.Available) {
                activity.runOnUiThread {
                    if (!activity.isFinishing) showUpdateDialog(activity, result.info)
                }
            }
        }, "drops-update-check").apply { isDaemon = true }.start()
    }

    /** Manual check from Settings: always reports the outcome. */
    fun checkManual(activity: Activity) {
        Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread({
            val result = UpdateFetcher.check(UpdateInstaller.installedVersionCode(activity))
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                when (result) {
                    is UpdateCheckResult.Available -> showUpdateDialog(activity, result.info)
                    is UpdateCheckResult.UpToDate ->
                        Toast.makeText(activity, "You have the latest version.", Toast.LENGTH_SHORT).show()
                    is UpdateCheckResult.Failed ->
                        Toast.makeText(
                            activity,
                            "Update check failed — are you online?",
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }, "drops-update-check-manual").apply { isDaemon = true }.start()
    }

    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        val message = buildString {
            append("Version ${info.versionName} is available.")
            if (info.notes.isNotBlank()) {
                append("\n\nWhat's new:\n")
                append(info.notes)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(message)
            .setPositiveButton("Download & Install") { _, _ ->
                UpdateProgressDialog.show(activity, info)
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
