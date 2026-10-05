package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateVehicleDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.VehicleSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.feature.activity.activityColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class VehiclesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val vehicle = VehicleSummary("v1", 0, "Mazda", "CX-5", null, "3AA·BB4", "PETROL", 4, "SEDAN", "White")

    private var added: CreateVehicleDraft? = null
    private var updated: Pair<String, CreateVehicleDraft>? = null
    private var back = 0
    private var finish: ((String?) -> Unit)? = null

    private fun show(
        vehicles: Resource<List<VehicleSummary>> = Resource(data = emptyList()),
        saving: Boolean = false,
        driverSetup: Boolean = false,
        driverChecking: Boolean = false,
        dark: Boolean = false,
        fontScale: Float = 1f
    ) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            MaterialTheme(colorScheme = activityColorScheme(dark)) {
                VehiclesScreen(vehicles, saving,
                    onAddVehicle = { draft, done -> added = draft; finish = done },
                    onUpdateVehicle = { id, draft, done -> updated = id to draft; finish = done },
                    onNavigateBack = { back++ }, driverSetup = driverSetup, driverChecking = driverChecking)
            }
        }
    }

    private fun fillNew() {
        compose.onNodeWithText("Car brand").performTextInput("Toyota")
        compose.onNodeWithText("Car model").performTextInput("Corolla")
        compose.onNodeWithText("Registration plate").performTextInput("abc123")
        compose.onNodeWithText("Colour").performTextInput("Blue")
    }

    @Test fun newRegistrationShowsNeutralIntroTitleAndSections() {
        show()
        compose.onNodeWithText("Register your vehicle").assertIsDisplayed()
        compose.onNodeWithTag("vehicle-intro-neutral").assertIsDisplayed()
        compose.onNodeWithTag("vehicle-driver-hero").assertDoesNotExist()
        for (section in listOf("Vehicle details", "Vehicle type", "Total seating capacity", "Fuel type")) {
            compose.onNodeWithText(section).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Register vehicle").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun driverGateShowsInformationalHeroInsteadOfNeutralIntro() {
        show(driverSetup = true)
        compose.onNodeWithTag("vehicle-driver-hero").assertIsDisplayed()
        compose.onNodeWithText("Register your vehicle to start driving").assertIsDisplayed()
        compose.onNodeWithText("Not now? Back returns you to Passenger mode.").assertIsDisplayed()
        compose.onNodeWithTag("vehicle-intro-neutral").assertDoesNotExist()
    }

    @Test fun backAndCancelBothLeaveWithoutRegisteringWhenThereIsNoVehicle() {
        show(driverSetup = true)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(2, back); assertNull(added) }
    }

    @Test fun registerWithEmptyFormShowsFirstErrorOnItsFieldAndDoesNotSubmit() {
        show()
        compose.onNodeWithText("Register vehicle").performClick()
        compose.onNodeWithText("Enter a car brand (up to 64 characters).").assertIsDisplayed()
        compose.onNodeWithText("Enter a car model (up to 64 characters).").assertDoesNotExist()
        compose.runOnIdle { assertNull(added) }
    }

    @Test fun missingTypeThenFuelErrorsAttachToTheChoiceGroups() {
        show()
        fillNew()
        compose.onNodeWithText("Register vehicle").performClick()
        compose.onNodeWithText("Choose a vehicle type.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SUV").performScrollTo().performClick()
        compose.onNodeWithText("Register vehicle").performClick()
        compose.onNodeWithText("Choose a fuel type.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertNull(added) }
    }

    @Test fun validRegistrationSubmitsTrimmedUppercaseDraftWithDefaultFiveSeats() {
        show()
        fillNew()
        compose.onNodeWithText("Hatchback").performScrollTo().performClick()
        compose.onNodeWithText("Hatchback").assertIsSelected()
        compose.onNodeWithText("Sedan").assertIsNotSelected()
        compose.onNodeWithText("Plug-in Hybrid").performScrollTo().performClick()
        compose.onNodeWithText("Register vehicle").performClick()
        compose.runOnIdle {
            assertEquals(CreateVehicleDraft(0, "Toyota", "Corolla", null, "ABC123", 5, "PHEV", "HATCHBACK", "Blue"), added)
        }
    }

    @Test fun seatStepperClampsAtTwoAndTwelveAndUpdatesPassengerCopy() {
        show()
        compose.onNodeWithText("Up to 4 passenger seats can be offered.").performScrollTo().assertIsDisplayed()
        repeat(3) { compose.onNodeWithContentDescription("Decrease seats").performScrollTo().performClick() }
        compose.onNodeWithContentDescription("2 seats").assertIsDisplayed()
        compose.onNodeWithContentDescription("Decrease seats").assertIsNotEnabled()
        compose.onNodeWithText("Up to 1 passenger seats can be offered.").assertIsDisplayed()
        repeat(10) { compose.onNodeWithContentDescription("Increase seats").performClick() }
        compose.onNodeWithContentDescription("12 seats").assertIsDisplayed()
        compose.onNodeWithContentDescription("Increase seats").assertIsNotEnabled()
        compose.onNodeWithText("Up to 11 passenger seats can be offered.").assertIsDisplayed()
    }

    @Test fun registrationFailureShowsServerMessageAndKeepsTheForm() {
        show()
        fillNew()
        compose.onNodeWithText("Sedan").performScrollTo().performClick()
        compose.onNodeWithText("Petrol").performScrollTo().performClick()
        compose.onNodeWithText("Register vehicle").performClick()
        compose.runOnIdle { finish?.invoke("Registration plate must contain 1–16 characters") }
        compose.onNodeWithText("Couldn’t register your vehicle").assertIsDisplayed()
        compose.onNodeWithText("Registration plate must contain 1–16 characters").assertIsDisplayed()
        compose.onNodeWithText("Register vehicle").assertIsEnabled()
    }

    @Test fun savingDisablesFieldsAndShowsRegisteringLabel() {
        show(saving = true)
        compose.onNodeWithText("Registering…").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithText("Car brand").assertIsNotEnabled()
    }

    @Test fun existingVehicleOpensReadOnlySummaryThenEditThenCancelReturnsToSummary() {
        show(vehicles = Resource(data = listOf(vehicle)))
        compose.onNodeWithText("My vehicle").assertIsDisplayed()
        compose.onNodeWithTag("vehicle-summary").assertIsDisplayed()
        compose.onNodeWithText("Mazda CX-5").assertIsDisplayed()
        compose.onNodeWithText("Save changes").assertDoesNotExist()
        compose.onNodeWithText("Edit vehicle").performClick()
        compose.onNodeWithText("Save changes").assertIsDisplayed()
        compose.onNodeWithTag("vehicle-intro-neutral").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("vehicle-summary").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, back) }
    }

    @Test fun cancelDiscardsUnsavedEditsAndErrorsSoReopeningStartsFromTheSavedVehicle() {
        show(vehicles = Resource(data = listOf(vehicle)))
        compose.onNodeWithText("Edit vehicle").performClick()
        compose.onNodeWithText("Car brand").performTextInput("Zzz")
        compose.onNodeWithText("Car model").performTextClearance()
        compose.onNodeWithText("Save changes").performClick()
        compose.onNodeWithText("Enter a car model (up to 64 characters).").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Edit vehicle").performClick()
        compose.onNodeWithText("MazdaZzz").assertDoesNotExist()
        compose.onNodeWithText("Mazda").assertIsDisplayed()
        compose.onNodeWithText("CX-5").assertIsDisplayed()
        compose.onNodeWithText("Enter a car model (up to 64 characters).").assertDoesNotExist()
        compose.runOnIdle { assertNull(updated) }
    }

    @Test fun editSavesWithExistingYearVariantAndStoredPlateUnchanged() {
        show(vehicles = Resource(data = listOf(vehicle)))
        compose.onNodeWithText("Edit vehicle").performClick()
        compose.onNodeWithText("Petrol").assertIsSelected()
        compose.onNodeWithText("Save changes").performClick()
        compose.runOnIdle {
            assertEquals("v1" to CreateVehicleDraft(0, "Mazda", "CX-5", null, "3AA·BB4", 4, "PETROL", "SEDAN", "White"), updated)
        }
    }

    @Test fun editingShowsSavingLabel() {
        show(vehicles = Resource(data = listOf(vehicle)), saving = true)
        compose.onNodeWithText("Edit vehicle").performClick()
        compose.onNodeWithText("Saving…").assertIsDisplayed()
    }

    @Test fun loadingShowsPlaceholderWithTextAlternativeAndNoActions() {
        show(vehicles = Resource(isLoading = true))
        compose.onNodeWithContentDescription("Loading your vehicle").assertIsDisplayed()
        compose.onNodeWithText("Register vehicle").assertDoesNotExist()
    }

    @Test fun loadErrorMessageIsShownAsBanner() {
        show(vehicles = Resource(data = emptyList(), errorMessage = "offline"))
        compose.onNodeWithText("Couldn’t load your vehicle").assertIsDisplayed()
        compose.onNodeWithText("offline").assertIsDisplayed()
    }

    @Test fun driverHandOffBannerShowsWhileSwitchingAndBlocksEdit() {
        show(vehicles = Resource(data = listOf(vehicle)), driverSetup = true, driverChecking = true)
        compose.onNodeWithTag("vehicle-handoff").assertIsDisplayed()
        compose.onNodeWithText("Switching you to Driver mode…").assertIsDisplayed()
        compose.onNodeWithText("Edit vehicle").assertIsNotEnabled()
    }

    @Test fun ordinaryVehicleManagementNeverShowsDriverHandOff() {
        show(vehicles = Resource(data = listOf(vehicle)), driverSetup = false, driverChecking = false)
        compose.onNodeWithTag("vehicle-handoff").assertDoesNotExist()
        compose.onNodeWithText("Edit vehicle").assertIsEnabled()
    }

    @Test fun largeTextKeepsActionsReachableAndChipsWrap() {
        show(fontScale = 2f)
        compose.onNodeWithText("Register vehicle").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Plug-in Hybrid").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Increase seats").performScrollTo().assertIsDisplayed()
    }

    @Test fun darkSchemeRenders() {
        show(dark = true, driverSetup = true)
        compose.onNodeWithTag("vehicle-driver-hero").assertIsDisplayed()
        compose.onNodeWithText("Register vehicle").assertIsDisplayed()
    }
}
