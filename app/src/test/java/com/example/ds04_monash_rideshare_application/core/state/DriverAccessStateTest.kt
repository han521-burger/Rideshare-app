package com.example.ds04_monash_rideshare_application.core.state

import org.junit.Assert.*
import org.junit.Test

class DriverAccessStateTest {
    @Test fun newSessionStartsAsPassenger() { assertEquals(AppMode.PASSENGER, DriverAccessState().mode) }
    @Test fun lookupNeverActivatesDriverEarly() {
        val state = DriverAccessState().beginCheck()
        assertTrue(state.checking); assertEquals(AppMode.PASSENGER,state.mode)
    }
    @Test fun noVehicleRequiresRegistration() {
        val state = DriverAccessState().beginCheck().resolve(false)
        assertTrue(state.needsRegistration); assertEquals(AppMode.PASSENGER,state.mode)
    }
    @Test fun cancelRegistrationKeepsPassenger() {
        val state = DriverAccessState().resolve(false).cancel()
        assertFalse(state.needsRegistration); assertEquals(AppMode.PASSENGER,state.mode)
    }
    @Test fun confirmedRegistrationOrReturningVehicleActivatesDriver() {
        for (state in listOf(DriverAccessState(), DriverAccessState().resolve(false))) {
            val result = state.beginCheck().resolve(true)
            assertEquals(AppMode.DRIVER,result.mode); assertFalse(result.needsRegistration)
        }
    }
    @Test fun lookupFailureReturnsToPassenger() {
        val state = DriverAccessState().beginCheck().cancel()
        assertFalse(state.checking); assertEquals(AppMode.PASSENGER,state.mode)
    }
}
