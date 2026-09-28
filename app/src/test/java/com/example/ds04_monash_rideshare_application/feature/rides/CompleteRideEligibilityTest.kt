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
    @Test fun nonOwnersCannotComplete() {
        for (user in listOf(null, "passenger", "other-driver")) {
            for (status in listOf("OPEN", "CONFIRMED")) assertFalse(canCompleteRide("owner", user, status))
        }
    }
}
