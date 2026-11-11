package com.moon.newmedicine

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.moon.newmedicine.alarm.AlarmScheduler
import com.moon.newmedicine.data.DropsDatabase
import com.moon.newmedicine.notify.NotificationHelper
import com.moon.newmedicine.repo.DropsRepository

/**
 * Application entry point. Lazily builds the data + scheduling +
 * notification layer (single instances shared by the UI and the receivers)
 * and, on a background thread, rolls the day over, reconciles alarms from
 * the database, and re-arms the morning wake chain on every process start.
 *
 * Also registers a best-effort ACTION_USER_PRESENT receiver (dynamic only —
 * the broadcast cannot be received from the manifest): when the user unlocks
 * the phone while the process is alive, one wake evaluation runs immediately
 * instead of waiting for the next alarm tick. The exact-alarm chain remains
 * the reliable path; this is only a latency improvement.
 */
class DropsApp : Application() {

    val repository: DropsRepository by lazy { DropsRepository(this) }
    val database: DropsDatabase by lazy { repository.db }
    val alarmScheduler: AlarmScheduler by lazy { repository.alarms }
    val notificationHelper: NotificationHelper by lazy { repository.notifications }

    // Short aliases consumed by the UI layer.
    val alarms: AlarmScheduler get() = alarmScheduler
    val notifications: NotificationHelper get() = notificationHelper

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            Thread({
                try {
                    repository.runWakeCheck(System.currentTimeMillis(), force = false)
                } catch (t: Throwable) {
                    Log.w("DropsApp", "USER_PRESENT wake check failed", t)
                }
            }, "drops-user-present").apply { isDaemon = true }.start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) {
                // API 33+ requires an explicit exported-ness flag.
                registerReceiver(userPresentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(userPresentReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w("DropsApp", "USER_PRESENT receiver registration failed", e)
        }
        Thread({
            val now = System.currentTimeMillis()
            // v1.10: each startup step runs in its own try/catch. A throwing
            // step (e.g. day rollover on a weird device state) must never
            // silently skip the later steps — especially reconcileAlarms,
            // which hosts all one-time migrations.
            try {
                repository.ensureDayRollover(now)
            } catch (t: Throwable) {
                Log.w("DropsApp", "startup ensureDayRollover failed; continuing to reconcileAlarms", t)
            }
            try {
                repository.reconcileAlarms(now)
            } catch (t: Throwable) {
                Log.w("DropsApp", "startup reconcileAlarms failed", t)
            }
            try {
                repository.rearmWakeChain(now)
            } catch (t: Throwable) {
                Log.w("DropsApp", "startup rearmWakeChain failed", t)
            }
        }, "drops-startup").apply { isDaemon = true }.start()
    }
}
