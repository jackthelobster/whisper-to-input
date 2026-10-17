package com.example.whispertoinput

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPollScheduleTest {
    @Test fun healthySelectionKeepsPollingDespiteOtherEndpointTimeout() {
        val selected = AppSettings()
        assertFalse(EndpointSelection(selected, ProbeResult.Timeout, ProbeResult.Ready).shouldPausePolling())
        assertFalse(EndpointSelection(selected, ProbeResult.Ready, ProbeResult.Timeout).shouldPausePolling())
    }

    @Test fun onlyUnselectedTimeoutPausesPolling() {
        assertTrue(EndpointSelection(null, ProbeResult.Timeout, ProbeResult.Unreachable).shouldPausePolling())
        assertTrue(EndpointSelection(null, ProbeResult.Unreachable, ProbeResult.Timeout).shouldPausePolling())
        assertFalse(EndpointSelection(null, ProbeResult.Unreachable).shouldPausePolling())
        assertFalse(EndpointSelection(AppSettings(), ProbeResult.Ready).shouldPausePolling())
    }

    @Test fun failuresBackOffAndSuccessResets() {
        val schedule = EndpointPollSchedule { 0.5 }
        assertEquals(9500L, schedule.nextDelayMillis(false))
        assertEquals(19000L, schedule.nextDelayMillis(false))
        repeat(5) { assertEquals(38000L, schedule.nextDelayMillis(false)) }
        assertEquals(45000L, schedule.nextDelayMillis(true))
        assertEquals(9500L, schedule.nextDelayMillis(false))
    }

    @Test fun jitterStaysWithinBounds() {
        for (sample in listOf(0.0, 0.99999)) {
            val schedule = EndpointPollSchedule { sample }
            assertTrue(schedule.nextDelayMillis(true) in 30000L..60000L)
            assertTrue(schedule.nextDelayMillis(false) in 9000L..10000L)
            assertTrue(schedule.nextDelayMillis(false) in 18000L..20000L)
            repeat(5) { assertTrue(schedule.nextDelayMillis(false) in 36000L..40000L) }
        }
    }
}
