package com.example.whispertoinput

import kotlin.random.Random

/** Interval between check starts. New sessions, saves and network changes reset the schedule. */
internal class EndpointPollSchedule(private val random: () -> Double = { Random.nextDouble() }) {
    private var failures = 0

    fun nextDelayMillis(healthy: Boolean): Long {
        if (healthy) {
            failures = 0
            return (30_000 + random() * 30_000).toLong()
        }
        val base = 10_000L shl failures.coerceAtMost(2)
        failures = (failures + 1).coerceAtMost(2)
        // Downward jitter keeps even capped retries within 40 seconds.
        return (base * (0.9 + random() * 0.1)).toLong()
    }
}

/** An optional endpoint timing out must not stop checks while dictation is available. */
internal fun EndpointSelection.shouldPausePolling(): Boolean = selected == null && timedOut
