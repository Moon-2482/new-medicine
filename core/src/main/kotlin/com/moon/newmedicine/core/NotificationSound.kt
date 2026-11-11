package com.moon.newmedicine.core

/**
 * Alarm-grade reminder sound selection (pure, unit-testable).
 *
 * The reminder channel must ring on the ALARM audio stream (which plays
 * even when the phone is on silent/vibrate) instead of the short,
 * easily-missed NOTIFICATION ding. Android's ringtone-type constants can't
 * live in the pure core module, so the preference order is expressed as
 * plain Ints and resolved through an injected `(Int) -> T?` lookup: the
 * caller passes the platform constants (TYPE_ALARM, TYPE_NOTIFICATION,
 * TYPE_RINGTONE) and a `RingtoneManager::getDefaultUri`-style resolver.
 */
object NotificationSound {

    /**
     * Returns the first non-null result of applying [resolve] to each type
     * in [typesInPreferenceOrder], or null when every type resolves to null
     * (or the order is empty). Generic over the URI type so the pure core
     * module never touches Android classes.
     */
    fun <T : Any> firstAvailable(
        typesInPreferenceOrder: IntArray,
        resolve: (type: Int) -> T?,
    ): T? {
        for (type in typesInPreferenceOrder) {
            val value = resolve(type)
            if (value != null) return value
        }
        return null
    }

    /**
     * Overload for already-resolved candidates (e.g. a bundled resource URI
     * that always exists, followed by the lazily-resolved system default):
     * returns the first non-null candidate, or null when every candidate
     * is null (or the list is empty).
     */
    fun <T : Any> firstAvailable(candidates: List<T?>): T? {
        for (candidate in candidates) {
            if (candidate != null) return candidate
        }
        return null
    }

    /**
     * Config version tracked for the `dose_reminders` notification channel.
     * Bumped to `alarm-v2` when the channel sound moved from the
     * NOTIFICATION stream to the ALARM stream, and to `alarm-v3` when the
     * bundled in-APK alarm tone became the first sound candidate (the
     * device's default alarm tone proved unreliable on the user's phone).
     * Android never updates an existing channel's sound, so a changed hash
     * forces delete+recreate and the user's phone actually gets the new
     * alarm sound.
     */
    fun channelConfigHash(soundEnabled: Boolean, vibrationEnabled: Boolean): String =
        "$soundEnabled:$vibrationEnabled:alarm-v3"

    /**
     * v2.1: how long the dose-reminder ring may sound before it stops
     * itself (2 minutes). The user stops it sooner by answering YES / NO /
     * Skip, opening the app, or dismissing the ringing notification.
     */
    const val RING_MAX_MILLIS: Long = 2L * 60 * 1000

    /**
     * v2.1: resolves the persisted reminder-sound choice (the raw
     * `reminderSoundUri` settings value) into what the ringing service
     * should play. Resolution order: a stored URI wins; null / blank means
     * "no choice stored" → the bundled in-APK alarm tone. A stored URI that
     * fails to load at play time falls back to the bundled tone at the call
     * site (the service), so a dead picker URI can never silence a reminder.
     */
    fun selectSound(storedUri: String?): SelectedSound =
        if (storedUri.isNullOrBlank()) SelectedSound.BuiltIn
        else SelectedSound.Custom(storedUri)
}

/** v2.1: outcome of resolving the stored reminder-sound choice (pure). */
sealed interface SelectedSound {
    /** Nothing stored — play the bundled in-APK alarm tone. */
    object BuiltIn : SelectedSound

    /** A user-chosen sound URI (system alarm default or ringtone picker). */
    data class Custom(val uriString: String) : SelectedSound
}
