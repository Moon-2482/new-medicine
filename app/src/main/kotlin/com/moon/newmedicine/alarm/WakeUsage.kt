package com.moon.newmedicine.alarm

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Device-usage signal for morning wake detection.
 *
 * Reads the most recent `lastTimeUsed` across all packages (excluding this
 * app itself) from [UsageStatsManager]. Requires the special
 * PACKAGE_USAGE_STATS access, which the user grants in system Settings —
 * never at runtime. When access is denied every function degrades to null /
 * false and the caller falls back to the once-per-day window-start prompt.
 *
 * The data never leaves the device: it is read, compared to "now", and
 * discarded inside the wake-check evaluation.
 */
object WakeUsage {

    /** True when the user granted usage access to this app. */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Epoch millis of the most recent app usage on the device (any package
     * except this app), or null when unavailable (no access, no data, or the
     * query failed). Only usage since [sinceMillis] is considered.
     */
    fun lastDeviceUsedAt(context: Context, sinceMillis: Long): Long? {
        if (!hasUsageAccess(context)) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, sinceMillis, now)
                ?: return null
            var max = -1L
            for (s in stats) {
                if (s.packageName == context.packageName) continue
                if (s.lastTimeUsed > max) max = s.lastTimeUsed
            }
            if (max < 0) null else max
        } catch (e: Exception) {
            null
        }
    }

    /** Opens the system usage-access settings screen for this app. */
    fun openUsageAccessSettings(context: Context) {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // No usage-access settings on this device: nothing to open.
        }
    }
}
