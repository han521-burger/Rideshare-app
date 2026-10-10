package com.example.ds04_monash_rideshare_application.feature.rides

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteRideEligibilityTest {
    @Test fun ownerCanCompleteActiveRides() {
        for (status in listOf("OPEN", "CONFIRMED")) assertTrue(canCompleteRide("owner", "owner", status))
    }
    @Test fun terminalAndUnknownStatesCannotComplete() {
        for (status in listOf("COMPLETED", "CANCELLED", "UNKNOWN")) assertFalse(canCompleteRide("owner", "owner", status))
    }
    @Test fun aRideCanOnlyBeCompletedOnceItHasStarted() {
        assertFalse(hasRideStarted(departureTime = 1_000L, now = 999L))
        assertTrue(hasRideStarted(departureTime = 1_000L, now = 1_000L))
        assertTrue(hasRideStarted(departureTime = 1_000L, now = 5_000L))
        // A caller that does not know the departure (the default) is treated as started, as before.
        assertTrue(hasRideStarted(0L, now = 1L))
        assertTrue(COMPLETE_AFTER_START_HINT.contains("once it has started"))
    }
    @Test fun nonOwnersCannotComplete() {
        for (user in listOf(null, "passenger", "other-driver")) {
            for (status in listOf("OPEN", "CONFIRMED")) assertFalse(canCompleteRide("owner", user, status))
        }
    }
}
