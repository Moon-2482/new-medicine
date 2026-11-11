package com.moon.newmedicine.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.R
import com.moon.newmedicine.core.DoseStatus
import com.moon.newmedicine.core.NotificationSound
import com.moon.newmedicine.core.RingBehavior
import com.moon.newmedicine.core.RingStartFallback
import com.moon.newmedicine.core.RingStartPhase
import com.moon.newmedicine.core.ScheduleEngine
import com.moon.newmedicine.core.SelectedSound
import com.moon.newmedicine.repo.DropsRepository

/**
 * v2.1: makes a dose reminder ring like a REAL alarm — the user's selected
 * sound loops on the ALARM audio stream until he stops it, instead of the
 * one-shot notification ding.
 *
 * Started (via [ring]) from [AlarmReceiver] on every dose-reminder fire
 * (initial fire and each nag re-fire) while the dose is still PENDING. The
 * foreground notification is the usual dose reminder (same content and
 * YES / NO / Skip actions) posted on the silent `dose_alarm` channel —
 * this service owns the audio.
 *
 * v2.6: the ring RESPECTS THE PHONE'S RINGER MODE (the ALARM stream
 * bypasses it, so the service must decide explicitly — pure decision in
 * [ScheduleEngine.ringBehaviorFor], taken once at ring start):
 * NORMAL → the looping sound, exactly as before; VIBRATE → no sound,
 * the vibrator loops the usual pattern instead; SILENT → visual only.
 * Every stop condition below ends all three forms alike.
 *
 * v2.7: HARDWARE-BUTTON SILENCE. While a ring episode is sounding,
 * pressing the POWER button (screen off or on — dynamic receiver,
 * those broadcasts cannot be manifest-registered) or ANY VOLUME button
 * (a watched stream's volume changes — settings ContentObserver +
 * snapshot compare, pure decision in [ScheduleEngine]) silences ONLY
 * the sound: the player stops, the looping vibrator carries on for the
 * rest of the episode (when the vibration toggle is on, in every
 * ringer mode), the foreground notification stays exactly as it is,
 * and the dose stays PENDING — silencing is not answering. The next
 * nag / re-fire rings audibly again; the silence applies per episode.
 * Platform limits are stated plainly in TEST-REPORT §29.
 *
 * v2.8: THE RING IS SACRED (live failure 2026-10-23, TEST-REPORT
 * §30). On v2.7 an evening dose produced no output at all in the
 * background: the receiver's fail-loud catch covers only a REFUSED
 * start, and this service's start path carried no guard — any failure
 * inside it after the start was accepted was invisible and final.
 * Now: `startRinging` runs under the phase guard
 * ([ScheduleEngine.ringStartFallback]) — a failure before the
 * foreground post falls back to the plain dose reminder, a failure
 * after it keeps the ring; the silence triggers register LAST, in
 * their own guard, so they can never prevent or kill the ring; the
 * trigger callbacks and the stop path are guarded too — silence is an
 * add-on, the reminder is the product.
 *
 * v2.10: CALL DEFERRAL (user order 2026-10-29 — the reminder drowned
 * out his phone calls). The receiver's RING branch never starts this
 * service during a call; for the race where a call starts MID-RING,
 * the episode runs a call-state poll ([ScheduleEngine
 * .CALL_POLL_MILLIS]): on a detected call the episode stops
 * ENTIRELY — player released, vibrator cancelled, foreground
 * notification removed WITHOUT the leave-reminder repost — and the
 * reminder is re-armed at now + the "call ke baad" gap. Detection is
 * AudioManager.getMode() (no permission): cellular, WhatsApp/VoIP
 * and ringing-but-unanswered all count. The Settings test ring is
 * manual and deliberately exempt.
 *
 * Stop conditions (whichever comes first):
 *  1. YES / NO / Skip tapped (notification action — [DoseActionReceiver]
 *     stops it — or answered in-app on the Dose / hero screens).
 *  2. The app is opened (MainActivity.onResume stops it).
 *  3. The ringing notification is dismissed (its delete intent stops it).
 *  4. A hard cap of [NotificationSound.RING_MAX_MILLIS] (2 minutes).
 * On stop paths 2–4 the dose is usually still PENDING, so a silent copy of
 * today's reminder is left in the tray (same as pre-v2.1 behavior).
 *
 * If `startForegroundService` is refused (background-start restrictions),
 * [ring] returns false and the caller falls back to the plain
 * notification — a sound failure must never swallow the reminder itself.
 */
class ReminderAlarmService : Service() {

    companion object {
        private const val TAG = "DropsRing"
        private const val ACTION_RING = "com.moon.newmedicine.RING_START"
        private const val ACTION_STOP = "com.moon.newmedicine.RING_STOP"
        private const val EXTRA_SOUND_URI = "ringSoundUri"
        private const val EXTRA_TEST = "ringTest"
        private const val EXTRA_LEAVE_REMINDER = "ringLeaveReminder"
        /** v2.10: the "call ke baad" gap for the mid-ring poll, resolved by [ring] off-main. */
        private const val EXTRA_CALL_GAP = "ringCallGapMillis"

        /**
         * v2.6: the Settings vibration toggle, resolved by [ring] /
         * [ringTest] off the main thread (like the sound choice) so a
         * VIBRATE-mode ring can honor it without DB I/O in onStartCommand.
         */
        private const val EXTRA_VIBRATION_ENABLED = "ringVibrationEnabled"
        /** Stable id of the Settings test ring (mirrors the helper's test id). */
        private const val TEST_NOTIFICATION_ID = 900_003
        private const val STOP_REQUEST_CODE = 0x6A4E20

        /** Dose the current ring belongs to (-1 = none / test ring). */
        @Volatile
        private var ringingDoseId: Long = -1L

        /** True while a ring is (believed to be) sounding in this process. */
        @Volatile
        private var ringing: Boolean = false

        fun isRinging(): Boolean = ringing

        /**
         * Starts the ring for [doseId]. MUST be called off the main thread
         * (reads the sound-choice setting). Returns false when the
         * foreground-service start was refused — the caller then posts the
         * plain reminder instead.
         */
        fun ring(context: Context, doseId: Long): Boolean {
            val app = context.applicationContext as? DropsApp
            val stored = try {
                app?.repository?.getRawSetting(DropsRepository.KEY_REMINDER_SOUND)
            } catch (e: Exception) {
                null
            }
            val intent = Intent(context, ReminderAlarmService::class.java).apply {
                action = ACTION_RING
                putExtra(AlarmScheduler.EXTRA_DOSE_ID, doseId)
                if (stored != null) putExtra(EXTRA_SOUND_URI, stored)
                putExtra(EXTRA_VIBRATION_ENABLED, vibrationEnabled(app))
                putExtra(EXTRA_CALL_GAP, callDeferGap(app))
            }
            return startRingService(context, intent)
        }

        /**
         * Starts the Settings "Test alarm sound" ring with the selected
         * sound. Same contract as [ring].
         */
        fun ringTest(context: Context): Boolean {
            val app = context.applicationContext as? DropsApp
            val stored = try {
                app?.repository?.getRawSetting(DropsRepository.KEY_REMINDER_SOUND)
            } catch (e: Exception) {
                null
            }
            val intent = Intent(context, ReminderAlarmService::class.java).apply {
                action = ACTION_RING
                putExtra(EXTRA_TEST, true)
                if (stored != null) putExtra(EXTRA_SOUND_URI, stored)
                putExtra(EXTRA_VIBRATION_ENABLED, vibrationEnabled(app))
            }
            return startRingService(context, intent)
        }

        /**
         * v2.6: the Settings vibration toggle for the ring intent. Read
         * failure defaults to true — matching [com.moon.newmedicine.core.DoseSettings]'s
         * own default: failing toward alerting, never toward silence.
         */
        private fun vibrationEnabled(app: DropsApp?): Boolean = try {
            app?.repository?.getSettings()?.vibrationEnabled ?: true
        } catch (e: Exception) {
            true
        }

        /**
         * v2.10: the "call ke baad" gap for the mid-ring poll. Read
         * failure defaults to the 1-minute default — matching
         * [com.moon.newmedicine.core.DoseSettings]'s own default.
         */
        private fun callDeferGap(app: DropsApp?): Long = try {
            ScheduleEngine.sanitizeCallDeferGap(
                app?.repository?.getSettings()?.callDeferGapMillis
                    ?: ScheduleEngine.DEFAULT_CALL_DEFER_GAP_MILLIS
            )
        } catch (e: Exception) {
            ScheduleEngine.DEFAULT_CALL_DEFER_GAP_MILLIS
        }

        private fun startRingService(context: Context, intent: Intent): Boolean =
            try {
                context.startForegroundService(intent)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService refused; caller falls back to plain reminder", t)
                false
            }

        /**
         * Stops the ring (if any). [leaveReminder] = true keeps a silent
         * copy of the dose reminder in the tray (app-opened / dismissed /
         * cap paths); action paths pass false because the action handlers
         * manage the tray entry themselves.
         */
        fun stop(context: Context, leaveReminder: Boolean) {
            if (!ringing) return
            val intent = Intent(context, ReminderAlarmService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_LEAVE_REMINDER, leaveReminder)
            }
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                // Background-start restrictions (e.g. from a receiver):
                // a hard stopService still runs the cleanup in onDestroy.
                try {
                    context.stopService(Intent(context, ReminderAlarmService::class.java))
                } catch (t2: Throwable) {
                    Log.w(TAG, "stopService failed", t2)
                }
            }
        }

        /** Stops the ring only when it belongs to [doseId] (stale-alarm path). */
        fun stopIfRinging(context: Context, doseId: Long) {
            if (ringing && ringingDoseId == doseId) stop(context, leaveReminder = false)
        }

        /** v2.4: true while the ring sounding right now belongs to [doseId]. */
        fun isRingingFor(doseId: Long): Boolean = ringing && ringingDoseId == doseId
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var capRunnable: Runnable? = null
    private var currentNotificationId: Int = -1
    private var currentTest: Boolean = false

    // v2.7 hardware-silence state — strictly per ring episode: every
    // field is reset in startRinging and torn down in stopRinging /
    // onDestroy, so a later episode snapshots fresh and can be
    // silenced again.
    /** True while this episode's MediaPlayer is (believed) playing. */
    private var sounding = false

    /** True once a hardware trigger has silenced this episode's sound. */
    private var soundSilenced = false

    /** The Settings vibration toggle for this episode (intent extra). */
    private var episodeVibrationEnabled = true
    private var screenReceiver: BroadcastReceiver? = null
    private var volumeObserver: ContentObserver? = null
    private var volumeSnapshot: Map<Int, Int>? = null

    // v2.10: the mid-ring call poll — re-checks the call state every
    // CALL_POLL_MILLIS while a real dose ring sounds. Per-episode
    // state like the hardware-silence fields above: started in
    // startRinging, torn down in stopRinging / onDestroy.
    private var callPollRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopRinging(
                    leaveReminder = intent.getBooleanExtra(EXTRA_LEAVE_REMINDER, false),
                )
                ACTION_RING -> startRinging(
                    doseId = intent.getLongExtra(AlarmScheduler.EXTRA_DOSE_ID, -1L),
                    test = intent.getBooleanExtra(EXTRA_TEST, false),
                    storedSoundUri = intent.getStringExtra(EXTRA_SOUND_URI),
                    vibrationEnabled = intent.getBooleanExtra(EXTRA_VIBRATION_ENABLED, true),
                    callGapMillis = intent.getLongExtra(
                        EXTRA_CALL_GAP, ScheduleEngine.DEFAULT_CALL_DEFER_GAP_MILLIS
                    ),
                )
                else -> stopSelf()
            }
        } catch (t: Throwable) {
            // v2.8: last-resort guard. startRinging has its own phase
            // guard; this covers everything around it. A dose fire must
            // never end in silence: if a ring start died here, surface
            // the plain reminder exactly like a refused start would.
            Log.e(TAG, "onStartCommand failed; falling back so the dose is not lost", t)
            if (intent?.action == ACTION_RING &&
                !intent.getBooleanExtra(EXTRA_TEST, false)
            ) {
                postPlainReminderFallback(
                    intent.getLongExtra(AlarmScheduler.EXTRA_DOSE_ID, -1L),
                )
            }
            detachAndStop()
        }
        return START_NOT_STICKY
    }

    private fun startRinging(
        doseId: Long,
        test: Boolean,
        storedSoundUri: String?,
        vibrationEnabled: Boolean,
        callGapMillis: Long,
    ) {
        // v2.8: the whole start runs under the phase guard
        // (ScheduleEngine.ringStartFallback). Until the foreground
        // notification is posted, a failure falls back to the plain
        // reminder; after it, a failure must never tear the ring down.
        var phase = RingStartPhase.BEFORE_FOREGROUND
        var audioManager: AudioManager? = null
        try {
            audioManager = startRingingEstablished(
                doseId, test, storedSoundUri, vibrationEnabled,
            ) {
                phase = RingStartPhase.AFTER_FOREGROUND
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ring start failed in phase $phase; applying the fallback", t)
            when (ScheduleEngine.ringStartFallback(phase, test)) {
                RingStartFallback.POST_PLAIN_REMINDER -> {
                    postPlainReminderFallback(doseId)
                    detachAndStop()
                }
                RingStartFallback.STOP_QUIETLY -> detachAndStop()
                RingStartFallback.CONTINUE_RING -> armCap()
            }
            return
        }
        // v2.8: the hardware-silence triggers register LAST — after
        // the ring (foreground + alert + content + cap) is fully
        // established — and inside their own guard, on top of the
        // per-registration guards inside registerSilenceTriggers.
        // A failure here loses only the hardware silence (the v2.6
        // behavior); it can never prevent or kill the ring.
        val am = audioManager
        if (am != null) {
            try {
                registerSilenceTriggers(am)
            } catch (t: Throwable) {
                Log.w(TAG, "silence triggers unavailable this episode; ring unaffected", t)
            }
        }
        // v2.10: the mid-ring call poll — also LAST, after everything
        // above. While a real dose ring sounds, the call state is
        // re-checked every CALL_POLL_MILLIS; a call that starts
        // mid-ring stops EVERYTHING (sound, vibration, notification —
        // a full stop, unlike v2.7's hardware silence) and defers the
        // reminder to now + gap. The Settings test ring is manual and
        // deliberately exempt (user order: it rings even in a call).
        if (am != null && !test && doseId > 0) {
            try {
                startCallPoll(am, doseId, callGapMillis)
            } catch (t: Throwable) {
                Log.w(TAG, "call poll unavailable this episode; ring unaffected", t)
            }
        }
    }

    /**
     * The pre-v2.8 start body, unchanged in its steps and their order:
     * teardown, foreground post (immediately), the ringer-mode alert,
     * the content swap, the cap. [onForegroundPosted] fires the moment
     * the foreground post lands so the caller's phase guard knows the
     * reminder is already visible from that point on. Returns the
     * AudioManager the alert branch used, so the caller can arm the
     * hardware-silence triggers against the same instance.
     */
    private fun startRingingEstablished(
        doseId: Long,
        test: Boolean,
        storedSoundUri: String?,
        vibrationEnabled: Boolean,
        onForegroundPosted: () -> Unit,
    ): AudioManager {
        // Tear down any previous ring first: never two players. A new dose
        // replaces the old ring; a same-dose re-fire restarts cleanly
        // (fresh sound + fresh 2-minute cap).
        releasePlayer()
        stopVibrator()
        cancelCap()
        unregisterSilenceTriggers()
        ringing = true
        ringingDoseId = if (test) -1L else doseId
        currentTest = test
        currentNotificationId = if (test) TEST_NOTIFICATION_ID else doseId.toInt()
        // v2.7: fresh hardware-silence state for this episode.
        sounding = false
        soundSilenced = false
        episodeVibrationEnabled = vibrationEnabled

        val app = application as DropsApp
        val helper = app.notificationHelper

        // 1) Go foreground IMMEDIATELY — API 31+ kills the process when a
        //    foreground service does not call startForeground at once.
        //    A placeholder is swapped for the real reminder content below.
        val placeholder = if (test) {
            helper.buildTestAlarmNotification(stopPendingIntent())
        } else {
            helper.buildRingPlaceholder(stopPendingIntent())
        }
        startForegroundWithType(currentNotificationId, placeholder)
        onForegroundPosted()

        // 2) Alert in the form the phone's RINGER MODE allows (v2.6,
        //    decided once, here at ring start — no database needed: the
        //    sound choice and the vibration toggle were resolved by the
        //    caller into intent extras). The ALARM-stream sound bypasses
        //    the ringer mode, so NORMAL keeps the exact pre-v2.6
        //    behavior; VIBRATE gets a looping vibrator and NO player;
        //    SILENT gets neither — the foreground notification above is
        //    the whole alert, and the nag bookkeeping in AlarmReceiver
        //    treats this as a normal ring episode either way.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (ScheduleEngine.ringBehaviorFor(audioManager.ringerMode)) {
            RingBehavior.SOUND_AND_VIBRATE -> {
                startPlayer(storedSoundUri)
                sounding = player != null
            }
            RingBehavior.VIBRATE_ONLY -> if (vibrationEnabled) startVibrator()
            RingBehavior.VISUAL_ONLY -> Unit
        }

        // 2b) v2.7 armed the hardware-silence triggers here; v2.8 arms
        //     them in startRinging, AFTER this whole body has succeeded
        //     — an add-on must never sit inside the ring's own start
        //     path (TEST-REPORT §30).

        // 3) Swap in the full reminder content (DB reads off the main
        //    thread). If the dose was answered in the meantime, stop.
        if (!test && doseId > 0) {
            val notifId = currentNotificationId
            Thread({
                try {
                    val dose = app.repository.getDose(doseId)
                    if (dose == null || !ringing || currentNotificationId != notifId) {
                        return@Thread
                    }
                    if (dose.status != DoseStatus.PENDING) {
                        runOnMain { stopRinging(leaveReminder = false) }
                        return@Thread
                    }
                    val now = System.currentTimeMillis()
                    val settings = app.repository.getSettings()
                    val overdue = ScheduleEngine.isOverdue(dose, now, settings)
                    val session = app.repository.getSession(dose.sessionId)
                    val real = helper.buildAlarmReminder(
                        dose, session, overdue, stopPendingIntent(),
                    )
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(notifId, real)
                } catch (t: Throwable) {
                    Log.w(TAG, "reminder content swap failed; placeholder stays", t)
                }
            }, "drops-ring-content").apply { isDaemon = true }.start()
        }

        // 4) Hard cap: the ring never sounds longer than RING_MAX_MILLIS.
        armCap()
        return audioManager
    }

    /** v2.8: (re-)arms the hard 2-minute cap for the live episode. */
    private fun armCap() {
        cancelCap()
        val cap = Runnable { stopRinging(leaveReminder = true) }
        capRunnable = cap
        handler.postDelayed(cap, NotificationSound.RING_MAX_MILLIS)
    }

    /** v2.8: detaches any foreground state and stops; never throws. */
    private fun detachAndStop() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            // Never became foreground — nothing to detach.
        }
        stopSelf()
    }

    /**
     * v2.8: the in-service twin of the receiver's refused-start
     * fallback — posts the PLAIN dose reminder (on the mode-aware
     * channel the helper picks for dose reminders) when a ring start
     * failed inside this service, so the dose still produces
     * user-visible output. DB reads run off the main thread, like the
     * content swap; a dose that is no longer PENDING posts nothing.
     */
    private fun postPlainReminderFallback(doseId: Long) {
        if (doseId <= 0) return
        val app = application as? DropsApp ?: return
        Thread({
            try {
                val dose = app.repository.getDose(doseId) ?: return@Thread
                if (dose.status != DoseStatus.PENDING) return@Thread
                // v2.10: a failure DURING a call must not surface
                // anything — defer exactly like the receiver's RING
                // branch instead of posting the plain reminder.
                val inCall = try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am != null && ScheduleEngine.isCallActive(am.mode)
                } catch (t: Throwable) {
                    false
                }
                val now = System.currentTimeMillis()
                if (inCall) {
                    val gap = ScheduleEngine.sanitizeCallDeferGap(
                        app.repository.getSettings().callDeferGapMillis
                    )
                    val recheckAt = ScheduleEngine.nextCallRecheckAt(now, gap)
                    app.alarms.scheduleCallDeferred(doseId, recheckAt)
                    app.repository.setRawSetting(
                        ScheduleEngine.callDeferKey(doseId), now.toString()
                    )
                    Log.i(TAG, "ring start failed during a call; dose $doseId deferred to $recheckAt")
                    return@Thread
                }
                val settings = app.repository.getSettings()
                val overdue = ScheduleEngine.isOverdue(dose, now, settings)
                app.repository.notifications.showDoseReminder(
                    dose, app.repository.getSession(dose.sessionId), overdue,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "plain-reminder fallback also failed for dose $doseId", t)
            }
        }, "drops-ring-fallback").apply { isDaemon = true }.start()
    }

    private fun startForegroundWithType(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(id, notification)
        }
    }

    private fun startPlayer(storedSoundUri: String?) {
        val selection = NotificationSound.selectSound(storedSoundUri)
        player = try {
            when (selection) {
                is SelectedSound.Custom -> MediaPlayer().apply {
                    setAudioAttributes(alarmAudioAttributes())
                    setDataSource(this@ReminderAlarmService, Uri.parse(selection.uriString))
                    isLooping = true
                    prepare()
                    start()
                }
                SelectedSound.BuiltIn -> newBundledPlayer()
            }
        } catch (t: Throwable) {
            // A stored URI that no longer resolves must never silence the
            // reminder — fall back to the bundled tone.
            Log.w(TAG, "selected sound failed to play; using bundled tone", t)
            try {
                newBundledPlayer()
            } catch (t2: Throwable) {
                Log.w(TAG, "bundled tone failed to play", t2)
                null
            }
        }
    }

    private fun newBundledPlayer(): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(alarmAudioAttributes())
        resources.openRawResourceFd(R.raw.alarm_tone).use { afd ->
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        isLooping = true
        prepare()
        start()
    }

    private fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /**
     * v2.6: the VIBRATE-mode ring — the notification channels' pattern
     * (0, 600, 400, 600) as a repeating waveform (repeat index 0 loops
     * the whole pattern for the entire ring episode, until a stop
     * condition cancels it via [stopVibrator]).
     */
    private fun startVibrator() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        vibrator = vib
        try {
            vib.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600), 0),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "vibrator failed to start", t)
        }
    }

    /** v2.6: cancels the VIBRATE-mode ring, if one is running. */
    private fun stopVibrator() {
        try {
            vibrator?.cancel()
        } catch (t: Throwable) {
            // Cancelling must never crash the stop path.
        }
        vibrator = null
    }

    // ------------------------------------- hardware silence (v2.7)

    /**
     * v2.7: arms this episode's hardware-silence triggers:
     *  - a dynamic [BroadcastReceiver] for ACTION_SCREEN_OFF and
     *    ACTION_SCREEN_ON (a power press produces one of the two;
     *    these broadcasts cannot be received from the manifest, so
     *    registration is dynamic-only, per episode);
     *  - a [ContentObserver] on the system settings: the watched
     *    streams' volumes are snapshotted NOW (the ring has just
     *    started — the comparison never sees a pre-ring state) and any
     *    later change to a watched stream is a volume-button press.
     * Registration failures only lose the corresponding trigger — the
     * ring itself is never affected.
     */
    private fun registerSilenceTriggers(audioManager: AudioManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON ->
                        silenceSound("power button")
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            screenReceiver = receiver
        } catch (t: Throwable) {
            Log.w(TAG, "screen receiver registration failed; power silence unavailable", t)
        }

        try {
            volumeSnapshot = readWatchedVolumes(audioManager)
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = onVolumeSettingsChanged()
            }
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
            volumeObserver = observer
        } catch (t: Throwable) {
            Log.w(TAG, "volume observer registration failed; volume silence unavailable", t)
        }
    }

    /** Tears down both triggers. Idempotent; called on every stop path. */
    private fun unregisterSilenceTriggers() {
        screenReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (t: Throwable) {
                // Already unregistered — teardown must never crash.
            }
        }
        screenReceiver = null
        volumeObserver?.let { observer ->
            try {
                contentResolver.unregisterContentObserver(observer)
            } catch (t: Throwable) {
                // Same reasoning as above.
            }
        }
        volumeObserver = null
        volumeSnapshot = null
    }

    /** Fresh volumes of exactly the watched streams (pure set in the engine). */
    private fun readWatchedVolumes(audioManager: AudioManager): Map<Int, Int> =
        ScheduleEngine.SILENCE_WATCHED_VOLUME_STREAMS.associateWith { stream ->
            try {
                audioManager.getStreamVolume(stream)
            } catch (t: Throwable) {
                -1
            }
        }

    private fun onVolumeSettingsChanged() {
        // v2.8: this callback lands on the main thread from a
        // system-wide settings observer — an unexpected throw here
        // would crash the process mid-episode and take the ring (and
        // its foreground notification) down with it. Guarded: the
        // worst case is losing one volume-silence check.
        try {
            if (!ringing) return
            val snapshot = volumeSnapshot ?: return
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (ScheduleEngine.volumeChangedSince(snapshot, readWatchedVolumes(audioManager))) {
                silenceSound("volume button")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "volume-silence check failed; ring continues", t)
        }
    }

    /**
     * v2.7: the shared silence action for both hardware triggers.
     * Stops ONLY the sound (player released, episode marked silenced)
     * and hands the rest of the episode to the looping vibrator when
     * the vibration toggle is on — in EVERY ringer mode, per the
     * user's order ("notification jaari rahe, vibration jaari rahe,
     * sirf sound band"). The foreground notification, the 2-minute
     * cap and every other stop condition are untouched; the dose
     * stays PENDING and the nag bookkeeping in AlarmReceiver is
     * unaffected — the next nag / re-fire rings audibly again.
     */
    private fun silenceSound(source: String) {
        // v2.8: reached from main-thread callbacks (screen receiver,
        // volume observer) — guarded for the same reason as
        // onVolumeSettingsChanged: a throw here must never crash the
        // process and kill the ring it was only meant to quieten.
        try {
            if (!ScheduleEngine.shouldSilenceSound(sounding, soundSilenced)) return
            soundSilenced = true
            sounding = false
            releasePlayer()
            Log.i(TAG, "ring sound silenced by $source; vibration + notification continue")
            if (episodeVibrationEnabled) startVibrator()
        } catch (t: Throwable) {
            Log.w(TAG, "silence action failed ($source); ring continues", t)
        }
    }

    private fun stopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, ReminderAlarmService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_LEAVE_REMINDER, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ------------------------------------- mid-ring call poll (v2.10)

    /**
     * v2.10: arms the mid-ring call poll. While this episode's ring
     * sounds, the phone's call state is re-checked every
     * [ScheduleEngine.CALL_POLL_MILLIS]; a call that starts mid-ring
     * ends the whole episode via [stopForCall]. Uses
     * AudioManager.getMode() — no permission needed — so cellular,
     * WhatsApp/VoIP and ringing-but-unanswered are all caught. A poll
     * failure (or an unreadable mode) fails toward NOT in a call: the
     * ring simply continues to its cap.
     */
    private fun startCallPoll(audioManager: AudioManager, doseId: Long, gapMillis: Long) {
        cancelCallPoll()
        val poll = object : Runnable {
            override fun run() {
                if (!ringing || ringingDoseId != doseId) return
                val inCall = try {
                    ScheduleEngine.isCallActive(audioManager.mode)
                } catch (t: Throwable) {
                    false
                }
                if (inCall) {
                    stopForCall(doseId, gapMillis)
                    return
                }
                handler.postDelayed(this, ScheduleEngine.CALL_POLL_MILLIS)
            }
        }
        callPollRunnable = poll
        handler.postDelayed(poll, ScheduleEngine.CALL_POLL_MILLIS)
    }

    /** Tears down the mid-ring call poll. Idempotent; called on every stop path. */
    private fun cancelCallPoll() {
        callPollRunnable?.let { handler.removeCallbacks(it) }
        callPollRunnable = null
    }

    /**
     * v2.10: a call started while the reminder was ringing — the FULL
     * stop. Unlike v2.7's hardware silence (sound only; vibration and
     * notification carry on), NOTHING may disturb the call: the player
     * is released, the vibrator cancelled, the foreground notification
     * removed WITHOUT the silent leave-reminder repost (a tray entry
     * during a call is exactly what the user forbade), the poll and
     * the silence triggers torn down. The dose stays PENDING; the
     * deferred re-fire is armed at now + gap so the reminder lands
     * after the call.
     */
    private fun stopForCall(doseId: Long, gapMillis: Long) {
        Log.i(TAG, "call started mid-ring; stopping everything and deferring dose $doseId")
        ringing = false
        ringingDoseId = -1L
        sounding = false
        soundSilenced = false
        releasePlayer()
        stopVibrator()
        cancelCap()
        cancelCallPoll()
        unregisterSilenceTriggers()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            // Never became foreground — nothing to detach.
        }
        val app = application as? DropsApp
        if (app != null) {
            Thread({
                try {
                    val now = System.currentTimeMillis()
                    val recheckAt = ScheduleEngine.nextCallRecheckAt(now, gapMillis)
                    app.alarms.scheduleCallDeferred(doseId, recheckAt)
                    app.repository.setRawSetting(
                        ScheduleEngine.callDeferKey(doseId), now.toString()
                    )
                    Log.i(TAG, "dose $doseId reminder deferred to $recheckAt (call started mid-ring)")
                } catch (t: Throwable) {
                    Log.e(TAG, "call-defer arming failed for dose $doseId", t)
                }
            }, "drops-call-defer").apply { isDaemon = true }.start()
        }
        stopSelf()
    }

    private fun stopRinging(leaveReminder: Boolean) {
        // v2.8: the stop path is guarded end-to-end — a failure while
        // tearing an episode down must still end with the service
        // detached and stopped (the finally), never with a wedged
        // foreground service or a process crash.
        try {
            stopRingingBody(leaveReminder)
        } catch (t: Throwable) {
            Log.e(TAG, "stopRinging failed; forcing the stop", t)
        } finally {
            detachAndStop()
        }
    }

    private fun stopRingingBody(leaveReminder: Boolean) {
        val wasRinging = ringing
        val doseId = ringingDoseId
        val wasTest = currentTest
        ringing = false
        ringingDoseId = -1L
        sounding = false
        soundSilenced = false
        releasePlayer()
        stopVibrator()
        cancelCap()
        cancelCallPoll()
        unregisterSilenceTriggers()
        if (wasRinging && leaveReminder && !wasTest && doseId > 0) {
            // The dose is (usually) still PENDING: leave today's silent,
            // persistent reminder in the tray, exactly as before v2.1.
            // v2.3: unless the dose was snoozed in the meantime — then the
            // silent "Snoozed" note owns the tray and this repost (which
            // races the chooser's pick) must not clobber it.
            val app = application as? DropsApp
            if (app != null) {
                Thread({
                    try {
                        val snoozedUntil = app.repository.getSnoozeUntil(doseId)
                        if (snoozedUntil != null &&
                            snoozedUntil > System.currentTimeMillis()
                        ) {
                            return@Thread
                        }
                        val dose = app.repository.getDose(doseId)
                        if (dose != null && dose.status == DoseStatus.PENDING) {
                            val now = System.currentTimeMillis()
                            val settings = app.repository.getSettings()
                            val overdue = ScheduleEngine.isOverdue(dose, now, settings)
                            app.repository.notifications.showDoseReminder(
                                dose, app.repository.getSession(dose.sessionId), overdue,
                            )
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "leave-reminder repost failed", t)
                    }
                }, "drops-ring-leave").apply { isDaemon = true }.start()
            }
        }
        // The service stop itself lives in stopRinging's finally
        // (detachAndStop) so it runs even when this body fails midway.
    }

    private fun releasePlayer() {
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (t: Throwable) {
            // Releasing a half-built player must never crash the stop path.
        }
        player = null
    }

    private fun cancelCap() {
        capRunnable?.let { handler.removeCallbacks(it) }
        capRunnable = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    override fun onDestroy() {
        // Reached via stopService() fallback stops: same cleanup as
        // stopRinging, minus the tray repost (action paths own the tray).
        ringing = false
        ringingDoseId = -1L
        sounding = false
        soundSilenced = false
        releasePlayer()
        stopVibrator()
        cancelCap()
        cancelCallPoll()
        unregisterSilenceTriggers()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            // Already detached — nothing to remove.
        }
        super.onDestroy()
    }
}
