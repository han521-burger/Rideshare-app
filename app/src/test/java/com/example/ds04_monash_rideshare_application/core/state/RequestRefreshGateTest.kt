package com.example.ds04_monash_rideshare_application.core.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRefreshGateTest {
    @Test fun ordinaryPollingDoesNotOverlapOrQueue() {
        val gate = RequestRefreshGate()
        assertTrue(gate.start(false))
        repeat(5) { assertFalse(gate.start(false)) }
        assertFalse(gate.finish())
        assertTrue(gate.start(false))
    }

    @Test fun mutationDuringFetchRequiresOneFreshResponse() {
        val gate = RequestRefreshGate()
        assertTrue(gate.start(false))
        repeat(5) { assertFalse(gate.start(true)) }
        assertFalse(gate.start(false))
        assertTrue(gate.finish())
        assertTrue(gate.start(true))
        assertFalse(gate.finish())
    }

    @Test fun failedFetchStillAllowsQueuedRefreshToFinish() {
        val gate = RequestRefreshGate()
        assertTrue(gate.start(true))
        assertFalse(gate.start(true))
        // Both callback outcomes use finish: an old failure cannot swallow the fresh load.
        assertTrue(gate.finish())
        assertTrue(gate.start(true))
        assertFalse(gate.finish())
        assertTrue(gate.start(true))
    }

    @Test fun mutationDuringFollowUpGetsAnotherFreshFetch() {
        val gate = RequestRefreshGate()
        assertTrue(gate.start(true))
        assertFalse(gate.start(true))
        assertTrue(gate.finish())
        assertTrue(gate.start(true))
        assertFalse(gate.start(true))
        assertTrue(gate.finish())
        assertTrue(gate.start(true))
        assertFalse(gate.finish())
    }
}

