package com.moon.newmedicine.ui

import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.R
import com.moon.newmedicine.update.UpdateInstaller
import com.moon.newmedicine.update.UpdatePrompt

/**
 * Host activity: bottom navigation between the four framework Fragments,
 * plus the permission banners (notifications / exact alarms) above them.
 */
class MainActivity : Activity() {

    private lateinit var bannerContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bannerContainer = findViewById(R.id.banner_container)

        findViewById<Button>(R.id.nav_main).setOnClickListener { showFragment(MainFragment(), "main") }
        findViewById<Button>(R.id.nav_dose).setOnClickListener { showFragment(DoseFragment.forDose(-1L), "dose") }
        findViewById<Button>(R.id.nav_history).setOnClickListener { showFragment(HistoryFragment(), "history") }
        findViewById<Button>(R.id.nav_settings).setOnClickListener { showFragment(SettingsFragment(), "settings") }

        handleIntent(intent)

        // In-app self-update: silent background check; a dialog appears only
        // when a newer version is actually available. Also tidy away stale
        // update APKs left by previous downloads (keeps the in-flight and
        // blocked-install files).
        // A13: sweepStaleUpdateApks lists the downloads dir — disk I/O, so
        // it runs on a background thread, never on the main thread.
        Thread({
            UpdateInstaller.sweepStaleUpdateApks(this)
        }, "drops-update-sweep").apply { isDaemon = true }.start()
        UpdatePrompt.checkOnStart(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // v2.1: opening the app silences a ringing dose alarm (the silent
        // reminder stays in the tray if the dose is still pending).
        if (com.moon.newmedicine.alarm.ReminderAlarmService.isRinging()) {
            com.moon.newmedicine.alarm.ReminderAlarmService.stop(this, leaveReminder = true)
        }
        refreshBanners()
        // Finish a self-update install that was blocked on "unknown apps".
        UpdateInstaller.retryBlockedInstallIfAllowed(this)
    }

    private fun handleIntent(intent: Intent) {
        val doseId = intent.getLongExtra("doseId", -1L)
        when {
            doseId != -1L ->
                showFragment(DoseFragment.forDose(doseId), "dose")
            intent.getBooleanExtra("openSettings", false) ->
                showFragment(SettingsFragment(), "settings")
            fragmentManager.findFragmentById(R.id.fragment_container) == null ->
                showFragment(MainFragment(), "main")
        }
    }

    private fun showFragment(fragment: Fragment, tag: String) {
        fragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commit()
    }

    /**
     * A13: the capability checks below cross into system services
     * (canScheduleExactAlarms is an IPC) — they run on a background thread
     * and only the view building happens on the main thread.
     */
    private fun refreshBanners() {
        val app = application as DropsApp
        Thread({
            val notifOn = try {
                app.notifications.areNotificationsEnabled()
            } catch (e: Exception) {
                true
            }
            val alarmOk = try {
                app.alarms.canScheduleExact()
            } catch (e: Exception) {
                true
            }
            runOnUiThread { if (!isFinishing) renderBanners(notifOn, alarmOk) }
        }, "drops-banners").apply { isDaemon = true }.start()
    }

    private fun renderBanners(notifOn: Boolean, alarmOk: Boolean) {
        val app = application as DropsApp
        bannerContainer.removeAllViews()
        var any = false
        if (!notifOn) {
            addBanner(
                text = getString(R.string.banner_notif),
                action = getString(R.string.banner_notif_action),
            ) {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(i)
            }
            any = true
        }
        if (!alarmOk) {
            addBanner(
                text = getString(R.string.banner_alarm),
                action = getString(R.string.banner_alarm_action),
            ) { app.alarms.openExactAlarmSettings() }
            any = true
        }
        bannerContainer.visibility = if (any) View.VISIBLE else View.GONE
    }

    private fun addBanner(text: String, action: String, onAction: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(resources.getColor(R.color.accent, null))
            setPadding(24, 16, 24, 16)
        }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(resources.getColor(R.color.text_primary, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = Button(this).apply {
            this.text = action
            setOnClickListener { onAction() }
        }
        row.addView(tv)
        row.addView(btn)
        bannerContainer.addView(row)
    }
}
