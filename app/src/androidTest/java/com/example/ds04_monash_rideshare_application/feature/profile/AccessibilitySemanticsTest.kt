package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertCountEquals
import com.example.ds04_monash_rideshare_application.Repository.Remote.FavoriteDriverSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.VehicleSummary
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.state.RideshareUiState
import com.example.ds04_monash_rideshare_application.feature.activity.activityColorScheme
import org.junit.Rule
import org.junit.Test

// The semantics TalkBack depends on for the controls the approved design calls out. This pins roles,
// states and live regions; a live TalkBack session is a separate manual check.
class AccessibilitySemanticsTest {
    @get:Rule val compose = createComposeRule()

    private val ui = RideshareUiState(userId = "u1", email = "test.student@student.monash.edu", displayName = "Test Student", preferredName = "Test")
    private val account = UserAccountSummary("u1", "Test Student", "Test", "test.student@student.monash.edu", null, null, true, null, null)
    private val vehicle = VehicleSummary("v1", 0, "Mazda", "CX-5", null, "3AA·BB4", "PETROL", 4, "SEDAN", "White")

    private fun role(r: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, r)

    private fun profile(mode: AppMode = AppMode.DRIVER) = compose.setContent {
        MaterialTheme(colorScheme = activityColorScheme()) {
            ProfileScreen(ui, Resource(data = account),
                Resource(data = listOf(FavoriteDriverSummary("d1", "Priya Shankar", null, 0L))),
                { _, _, _, _, _, done -> done(null) }, {}, vehicle, {}, {}, activeMode = mode)
        }
    }

    private fun vehicles(v: List<VehicleSummary> = emptyList(), saveError: ((String?) -> Unit) -> Unit = {}) = compose.setContent {
        MaterialTheme(colorScheme = activityColorScheme()) {
            VehiclesScreen(Resource(data = v), false, { _, done -> saveError(done) }, { _, _, done -> saveError(done) }, {})
        }
    }

    @Test fun passengerAvailabilityIsOneSwitchWithOnOffState() {
        profile()
        compose.onNodeWithTag("profile-passenger-switch").assert(role(Role.Switch)).assertIsOn().performClick()
        compose.onNodeWithTag("profile-passenger-switch").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Off))
        // The row is the only toggle: the nested visual Switch adds no second click target.
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(1)
    }

    @Test fun profileActionsAreButtonsWithClickActions() {
        profile()
        for (text in listOf("Manage vehicle", "Sign out")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assert(role(Role.Button)).assertHasClickAction()
        }
        compose.onNodeWithTag("profile-save").assert(role(Role.Button)).assertHasClickAction()
    }

    @Test fun favoriteRemoveNamesTheDriver() {
        profile()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Priya Shankar"))
        compose.onNodeWithContentDescription("Remove Priya Shankar from favorite drivers").assert(role(Role.Button)).assertHasClickAction()
    }

    @Test fun registerVehicleIsAButtonInTheNoVehicleState() {
        compose.setContent { MaterialTheme(colorScheme = activityColorScheme()) {
            ProfileScreen(ui, Resource(data = account), Resource(data = emptyList()), { _, _, _, _, _, _ -> }, {}, null, {}, {})
        } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Register vehicle"))
        compose.onNodeWithText("Register vehicle").assert(role(Role.Button)).assertHasClickAction()
    }

    @Test fun vehicleChoicesAreRadioButtonsAndSelectionIsExposed() {
        vehicles()
        compose.onNodeWithText("Hatchback").performScrollTo().assert(role(Role.RadioButton)).performClick()
        compose.onNodeWithText("Hatchback").assertIsSelected()
        compose.onNodeWithText("Diesel").performScrollTo().assert(role(Role.RadioButton)).performClick()
        compose.onNodeWithText("Diesel").assertIsSelected()
    }

    @Test fun seatStepperButtonsAreNamedAndTheValueIsAnnounced() {
        vehicles()
        compose.onNodeWithContentDescription("Decrease seats").performScrollTo().assert(role(Role.Button)).assertHasClickAction()
        compose.onNodeWithContentDescription("Increase seats").assert(role(Role.Button)).assertHasClickAction()
        compose.onNodeWithContentDescription("5 seats").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun formActionsAreButtons() {
        vehicles(listOf(vehicle))
        compose.onNodeWithText("Edit vehicle").assert(role(Role.Button))
        compose.onNodeWithText("Edit vehicle").performClick()
        compose.onNodeWithText("Save changes").assert(role(Role.Button))
        compose.onNodeWithText("Cancel").assert(role(Role.Button))
        compose.onNodeWithContentDescription("Back").assert(role(Role.Button))
    }

    @Test fun validationErrorIsExposedOnTheFieldNotJustAsColour() {
        vehicles()
        compose.onNodeWithText("Register vehicle").performClick()
        compose.onNodeWithText("Enter a car brand (up to 64 characters).").assertExists()
        compose.onAllNodes(hasSetTextAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)).assertCountEquals(1)
    }

    @Test fun saveFailureBannerIsAnAssertiveLiveRegion() {
        var done: ((String?) -> Unit)? = null
        vehicles(listOf(vehicle)) { done = it }
        compose.onNodeWithText("Edit vehicle").performClick()
        compose.onNodeWithText("Save changes").performClick()
        compose.runOnIdle { done?.invoke("Registration plate must contain 1–16 characters") }
        compose.onNodeWithTag("vehicle-save-error").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }
}
