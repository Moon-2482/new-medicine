package com.moon.newmedicine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moon.newmedicine.DropsApp

/**
 * Rebuilds the alarm table after the OS wipes or shifts it: device boot,
 * clock changes, timezone changes, package replacement and date changes.
 * The database is the source of truth, so this just rolls the day over
 * (finalizing stale days), reconciles alarms from persisted dose state, and
 * re-arms the morning wake-detection chain.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            // A2: the manifest declares "android.intent.action.TIME_SET".
            // Intent.ACTION_TIME_CHANGED's VALUE is exactly that string
            // (AOSP quirk: the constant was renamed, the wire value kept —
            // verified in android.jar's ConstantValue), so the old branch
            // already matched at runtime. The literal is used deliberately
            // so the manifest<->receiver contract is textually obvious and
            // the AuditRegressionTest manifest guard stays green.
            "android.intent.action.TIME_SET",
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_DATE_CHANGED -> { /* handled below */ }
            else -> return
        }
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext as DropsApp
                val repo = app.repository
                val now = System.currentTimeMillis()
                // A1: each startup step runs in its own try/catch (mirroring
                // DropsApp.onCreate) — a throwing rollover must never block
                // the reconcile or the wake-chain re-arm again.
                try {
                    repo.ensureDayRollover(now)
                } catch (t: Throwable) {
                    Log.w("BootReceiver", "ensureDayRollover failed; continuing to reconcileAlarms", t)
                }
                try {
                    repo.reconcileAlarms(now)
                } catch (t: Throwable) {
                    Log.w("BootReceiver", "reconcileAlarms failed", t)
                }
                try {
                    repo.rearmWakeChain(now)
                } catch (t: Throwable) {
                    Log.w("BootReceiver", "rearmWakeChain failed", t)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
