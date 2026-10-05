package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import com.example.ds04_monash_rideshare_application.core.navigation.RideshareNavHost
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import com.example.ds04_monash_rideshare_application.ui.theme.Ds04_monash_rideshare_applicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

// Drives the real RideshareNavHost against an in-memory fake API (no backend, no seeded data) to pin
// the existing vehicle entry paths and the Driver gate end to end.
class VehicleEntryPathsTest {
    @get:Rule val compose = createComposeRule()

    private class Api : RideApiService by FakeRideApiService() {
        var vehicles: List<VehicleSummary> = emptyList()
        // The saved ("Keep me signed in") session is validated through the profile lookup on launch.
        override fun fetchMyProfile(callback: RideApiService.Callback<UserAccountSummary>) {
            callback.onSuccess(UserAccountSummary("fake-user-001", "Test Student", "Test", "test.student@student.monash.edu",
                null, null, true, null, null))
        }
        override fun fetchMyVehicles(callback: RideApiService.Callback<List<VehicleSummary>>) { callback.onSuccess(vehicles) }
        override fun createVehicle(draft: CreateVehicleDraft, callback: RideApiService.Callback<VehicleSummary>) {
            val v = VehicleSummary("v-new", draft.year, draft.make, draft.model, draft.variant, draft.registrationMasked,
                draft.fuelType, draft.seatCapacity, draft.vehicleType, draft.colour)
            vehicles = listOf(v)
            callback.onSuccess(v)
        }
    }

    private val api = Api()
    private val vm = RideshareViewModel(api)
    private val mazda = VehicleSummary("v1", 0, "Mazda", "CX-5", null, "3AA·BB4", "PETROL", 4, "SEDAN", "White")

    @After fun clearSession() { AuthSession.clear() }

    // Signs in through the supported saved-session route: a remembered session makes Splash validate it
    // (profile lookup) and go straight to Home, the same path a "Keep me signed in" user takes.
    private fun launch(initial: List<VehicleSummary>) {
        api.vehicles = initial
        AuthSession.init(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        AuthSession.start("test-token", "fake-user-001", remember = true)
        compose.setContent { Ds04_monash_rideshare_applicationTheme { RideshareNavHost(vm) } }
        waitForText("Activity")
    }

    private fun waitForText(text: String) = compose.waitUntil(8_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForTag(tag: String) = compose.waitUntil(8_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun fillAndRegister() {
        compose.onNodeWithText("Car brand").performTextInput("Toyota")
        compose.onNodeWithText("Car model").performTextInput("Corolla")
        compose.onNodeWithText("Registration plate").performTextInput("abc123")
        compose.onNodeWithText("Colour").performTextInput("Blue")
        compose.onNodeWithText("Hatchback").performScrollTo().performClick()
        compose.onNodeWithText("Petrol").performScrollTo().performClick()
        compose.onNodeWithText("Register vehicle").performClick()
    }

    @Test fun driverWithoutVehicleShowsInformationalHeroNoBypassAndBackReturnsToPassenger() {
        launch(emptyList())
        compose.onNodeWithText("Driver").performClick()
        waitForTag("vehicle-driver-hero")
        compose.onNodeWithText("Register your vehicle to start driving").assertIsDisplayed()
        compose.onNodeWithTag("vehicle-intro-neutral").assertDoesNotExist()
        compose.onNodeWithText("Activity").assertDoesNotExist() // pushed screen: no bottom navigation
        compose.runOnIdle { assertEquals(AppMode.PASSENGER, vm.activeMode) }
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Activity")
        compose.onNodeWithText("Passenger").assertIsSelected()
        compose.runOnIdle {
            assertEquals(AppMode.PASSENGER, vm.activeMode)
            assertFalse(vm.driverAccess.needsRegistration)
        }
    }

    @Test fun cancelOnTheDriverGateAlsoReturnsToPassenger() {
        launch(emptyList())
        compose.onNodeWithText("Driver").performClick()
        waitForTag("vehicle-driver-hero")
        compose.onNodeWithText("Cancel").performClick()
        waitForText("Activity")
        compose.onNodeWithText("Passenger").assertIsSelected()
        compose.runOnIdle { assertEquals(AppMode.PASSENGER, vm.activeMode) }
    }

    @Test fun registeringFromTheDriverGateActivatesDriverAndLandsOnHome() {
        launch(emptyList())
        compose.onNodeWithText("Driver").performClick()
        waitForTag("vehicle-driver-hero")
        fillAndRegister()
        waitForText("Activity")
        compose.onNodeWithText("Driver").assertIsSelected()
        compose.runOnIdle {
            assertEquals(AppMode.DRIVER, vm.activeMode)
            assertEquals(1, api.vehicles.size)
            assertEquals("ABC123", api.vehicles[0].registrationMasked)
        }
    }

    @Test fun profileRegisterVehicleShowsNeutralIntroNeverTheDriverHeroAndDoesNotActivateDriver() {
        launch(emptyList())
        compose.onNodeWithText("Profile").performClick()
        waitForText("Your details")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Register vehicle"))
        compose.onNodeWithText("Register vehicle").performClick()
        waitForTag("vehicle-intro-neutral")
        compose.onNodeWithTag("vehicle-driver-hero").assertDoesNotExist()
        fillAndRegister()
        waitForTag("vehicle-summary")
        // Ordinary registration stays on the summary and does not switch modes.
        compose.runOnIdle { assertEquals(AppMode.PASSENGER, vm.activeMode) }
        compose.onNodeWithTag("vehicle-handoff").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Your details")
        compose.onNodeWithText("Passenger").assertIsSelected()
    }

    @Test fun profileManageVehicleOpensTheSummaryWithoutTheDriverHero() {
        launch(listOf(mazda))
        compose.onNodeWithText("Profile").performClick()
        waitForText("Your details")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manage vehicle"))
        compose.onNodeWithText("Manage vehicle").performClick()
        waitForTag("vehicle-summary")
        compose.onNodeWithTag("vehicle-driver-hero").assertDoesNotExist()
        compose.onNodeWithText("Mazda CX-5").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Your details")
    }

    @Test fun requestsOfferRideWithNoVehicleOpensVehiclesWithoutDriverGateContext() {
        launch(listOf(mazda))
        compose.onNodeWithText("Driver").performClick()
        compose.waitUntil(8_000) { vm.activeMode == AppMode.DRIVER }
        // The saved vehicle disappears; the Profile tab reloads vehicles on entry, as in the real app.
        api.vehicles = emptyList()
        compose.onNodeWithText("Profile").performClick()
        compose.waitUntil(8_000) { vm.vehicles.data?.isEmpty() == true }
        compose.onNodeWithText("Requests").performClick()
        waitForText("Requests")
        compose.waitUntil(8_000) { compose.onAllNodesWithContentDescription("Offer a ride").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Offer a ride").performClick()
        waitForTag("vehicle-intro-neutral")
        compose.onNodeWithTag("vehicle-driver-hero").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithContentDescription("Offer a ride").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(AppMode.DRIVER, vm.activeMode) }
    }
}
