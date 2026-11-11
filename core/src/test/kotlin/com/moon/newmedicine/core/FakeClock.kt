package com.moon.newmedicine.core

/** Deterministic clock for tests: simulated time, no waiting hours. */
class FakeClock(var timeMillis: Long) : Clock {
    override fun nowMillis(): Long = timeMillis
    fun advance(millis: Long) {
        timeMillis += millis
    }

    fun set(millis: Long) {
        timeMillis = millis
    }
}
