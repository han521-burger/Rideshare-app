package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Auth.TEST_IDENTITIES
import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

// The Driver gate with the vehicle lookup under test control. The in-shell "Checking vehicle…" state
// replaced a blocking dialog, so these pin the behaviour that dialog used to guarantee by blocking input.
class DriverGateViewModelTest {
    private class DeferredApi : RideApiService by FakeRideApiService() {
        val vehicleLookups = mutableListOf<RideApiService.Callback<List<VehicleSummary>>>()
        override fun fetchMyVehicles(callback: RideApiService.Callback<List<VehicleSummary>>) { vehicleLookups += callback }
    }

    private val api = DeferredApi()
    private val vm = RideshareViewModel(api)
    private val vehicle = VehicleSummary("v1", 0, "Mazda", "CX-5", null, "3AA·BB4", "PETROL", 4, "SEDAN", "White")
    private var registrationRequired = 0
    private var driverActivated = 0

    @After fun clearSession() { AuthSession.currentUserId = null }

    private fun signedIn() = vm.signInAsTestUser(TEST_IDENTITIES[0])
    private fun chooseDriver() = vm.updateActiveMode(AppMode.DRIVER, { registrationRequired++ }, { driverActivated++ })

    @Test fun choosingDriverStartsOneCheckAndKeepsPassengerActive() {
        signedIn(); chooseDriver()
        assertTrue(vm.driverAccess.checking)
        assertEquals(AppMode.PASSENGER, vm.activeMode)
        assertEquals(1, api.vehicleLookups.size)
    }

    @Test fun repeatedDriverTapsWhileCheckingDoNotIssueMoreRequests() {
        signedIn(); chooseDriver(); chooseDriver(); chooseDriver()
        assertEquals(1, api.vehicleLookups.size)
        assertTrue(vm.driverAccess.checking)
    }

    @Test fun vehicleFoundActivatesDriverWithoutAskingForRegistration() {
        signedIn(); chooseDriver()
        api.vehicleLookups[0].onSuccess(listOf(vehicle))
        assertEquals(AppMode.DRIVER, vm.activeMode)
        assertFalse(vm.driverAccess.checking)
        assertEquals(1, driverActivated); assertEquals(0, registrationRequired)
        assertEquals(listOf(vehicle), vm.vehicles.data)
    }

    @Test fun noVehicleRequestsRegistrationAndNeverActivatesDriver() {
        signedIn(); chooseDriver()
        api.vehicleLookups[0].onSuccess(emptyList())
        assertEquals(1, registrationRequired); assertEquals(0, driverActivated)
        assertEquals(AppMode.PASSENGER, vm.activeMode)
        assertTrue(vm.driverAccess.needsRegistration)
        assertFalse(vm.driverAccess.checking)
    }

    @Test fun backOrCancelFromDriverRegistrationReturnsToPassengerWithNoBypass() {
        signedIn(); chooseDriver(); api.vehicleLookups[0].onSuccess(emptyList())
        vm.cancelDriverRegistration()
        assertEquals(AppMode.PASSENGER, vm.activeMode)
        assertFalse(vm.driverAccess.needsRegistration)
        assertEquals(0, driverActivated)
    }

    @Test fun registeringThenCheckingAgainActivatesDriver() {
        signedIn(); chooseDriver(); api.vehicleLookups[0].onSuccess(emptyList())
        // NavHost repeats the Driver switch once createVehicle succeeds.
        chooseDriver()
        assertEquals(2, api.vehicleLookups.size)
        api.vehicleLookups[1].onSuccess(listOf(vehicle))
        assertEquals(AppMode.DRIVER, vm.activeMode)
        assertEquals(1, driverActivated)
    }

    @Test fun choosingPassengerWhileCheckingCancelsAndALateResultIsIgnored() {
        signedIn(); chooseDriver()
        vm.updateActiveMode(AppMode.PASSENGER)
        assertFalse(vm.driverAccess.checking)
        api.vehicleLookups[0].onSuccess(listOf(vehicle))
        assertEquals(AppMode.PASSENGER, vm.activeMode)
        assertEquals(0, driverActivated); assertEquals(0, registrationRequired)
        assertNull(vm.driverModeError)
        // A late no-vehicle result must not push the user into registration either.
        chooseDriver(); vm.updateActiveMode(AppMode.PASSENGER)
        api.vehicleLookups[1].onSuccess(emptyList())
        assertEquals(0, registrationRequired); assertFalse(vm.driverAccess.needsRegistration)
    }

    @Test fun staleLookupCannotAffectANewerAttempt() {
        signedIn(); chooseDriver(); vm.updateActiveMode(AppMode.PASSENGER)
        chooseDriver()
        assertEquals(2, api.vehicleLookups.size)
        api.vehicleLookups[0].onSuccess(emptyList())
        assertTrue(vm.driverAccess.checking)
        assertEquals(0, registrationRequired)
        api.vehicleLookups[1].onSuccess(listOf(vehicle))
        assertEquals(AppMode.DRIVER, vm.activeMode); assertEquals(1, driverActivated)
    }

    @Test fun lookupFailureKeepsTheExistingMessageAndPassengerMode() {
        signedIn(); chooseDriver()
        api.vehicleLookups[0].onFailure(Exception("offline"))
        assertEquals("Couldn't check your vehicle. Please try Driver again.", vm.driverModeError)
        assertEquals(AppMode.PASSENGER, vm.activeMode)
        assertFalse(vm.driverAccess.checking)
        vm.clearDriverModeError()
        assertNull(vm.driverModeError)
        chooseDriver()
        assertEquals(2, api.vehicleLookups.size)
    }

    @Test fun signingOutDuringTheCheckCannotActivateDriverForTheNextUser() {
        signedIn(); chooseDriver()
        vm.signInAsTestUser(TEST_IDENTITIES[1])
        api.vehicleLookups[0].onSuccess(listOf(vehicle))
        assertEquals(AppMode.PASSENGER, vm.activeMode)
        assertEquals(0, driverActivated)
    }
}
