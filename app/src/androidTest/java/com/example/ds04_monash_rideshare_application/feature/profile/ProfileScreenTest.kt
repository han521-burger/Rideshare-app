package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.example.ds04_monash_rideshare_application.Repository.Remote.FavoriteDriverSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.VehicleSummary
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.state.RideshareUiState
import com.example.ds04_monash_rideshare_application.feature.activity.activityColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ProfileScreenTest {
    @get:Rule val compose = createComposeRule()

    private val ui = RideshareUiState(userId = "u1", email = "test.student@student.monash.edu", displayName = "Test Student", preferredName = "Test")
    private val account = UserAccountSummary("u1", "Test Student", "Test", "test.student@student.monash.edu", null, null, true, "Mei Lin", "0400 123 456")
    private val vehicle = VehicleSummary("v1", 0, "Mazda", "CX-5", null, "3AA·BB4", "PETROL", 4, "SEDAN", "White")

    private data class Saved(val display: String, val preferred: String, val passenger: Boolean, val name: String?, val phone: String?)

    private fun show(
        mode: AppMode = AppMode.PASSENGER,
        vehicle: VehicleSummary? = this.vehicle,
        favorites: List<FavoriteDriverSummary> = emptyList(),
        dark: Boolean = false,
        fontScale: Float = 1f,
        onSave: (String, String, Boolean, String?, String?, (String?) -> Unit) -> Unit = { _, _, _, _, _, done -> done(null) },
        onRemove: (String) -> Unit = {},
        onOpenVehicles: () -> Unit = {},
        onSignOut: () -> Unit = {}
    ) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            MaterialTheme(colorScheme = activityColorScheme(dark)) {
                ProfileScreen(ui, Resource(data = account), Resource(data = favorites), onSave, onRemove, vehicle,
                    onOpenVehicles, onSignOut, activeMode = mode)
            }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test fun identityShowsNeutralMonashContextAndDemotedAccountId() {
        show()
        compose.onAllNodesWithText("Test Student").onFirst().assertIsDisplayed()
        compose.onNodeWithText("test.student@student.monash.edu").assertIsDisplayed()
        compose.onNodeWithText("Monash account").assertIsDisplayed()
        compose.onNodeWithText("Verified Monash account", substring = true).assertDoesNotExist()
        scrollTo("Account ID · u1")
        compose.onNodeWithTag("profile-account-id").assertIsDisplayed()
    }

    @Test fun registeredVehicleShowsStructuredSummaryAndManage() {
        var opened = 0
        show(onOpenVehicles = { opened++ })
        scrollTo("Manage vehicle")
        compose.onNodeWithText("Mazda CX-5").assertIsDisplayed()
        compose.onNodeWithContentDescription("Plate 3AA·BB4").assertIsDisplayed()
        compose.onNodeWithText("3 for passengers").assertIsDisplayed()
        compose.onNodeWithText("Manage vehicle").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun readyToOfferOnlyInDriverModeWithVehicle() {
        show(mode = AppMode.PASSENGER)
        scrollTo("Manage vehicle")
        compose.onNodeWithText("Ready to offer rides").assertDoesNotExist()
    }

    @Test fun driverModeWithVehicleShowsReadyToOfferRides() {
        show(mode = AppMode.DRIVER)
        scrollTo("Manage vehicle")
        compose.onNodeWithText("Ready to offer rides").assertIsDisplayed()
    }

    @Test fun noVehicleShowsInformationalEmptyStateWithRegisterAction() {
        var opened = 0
        show(vehicle = null, onOpenVehicles = { opened++ })
        scrollTo("Register vehicle")
        compose.onNodeWithText("No vehicle registered").assertIsDisplayed()
        compose.onNodeWithText("Register vehicle").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithText("Manage vehicle").assertDoesNotExist()
    }

    @Test fun savePassesTrimmedValuesAndShowsSavedFeedback() {
        var saved: Saved? = null
        show(onSave = { d, p, pa, n, ph, done -> saved = Saved(d, p, pa, n, ph); done(null) })
        scrollTo("Save changes")
        compose.onNodeWithTag("profile-save").performClick()
        compose.runOnIdle { assertEquals(Saved("Test Student", "Test", true, "Mei Lin", "0400 123 456"), saved) }
        compose.onNodeWithText("Saved.").assertIsDisplayed()
    }

    @Test fun saveErrorIsShownAsTextAndButtonReenables() {
        show(onSave = { _, _, _, _, _, done -> done("Couldn't save your profile. Please try again.") })
        scrollTo("Save changes")
        compose.onNodeWithTag("profile-save").performClick()
        compose.onNodeWithText("Couldn't save your profile. Please try again.").assertIsDisplayed()
        compose.onNodeWithTag("profile-save").assertIsEnabled()
    }

    @Test fun savingDisablesTheButtonAndShowsSavingLabel() {
        var finish: ((String?) -> Unit)? = null
        show(onSave = { _, _, _, _, _, done -> finish = done })
        scrollTo("Save changes")
        compose.onNodeWithTag("profile-save").performClick()
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        compose.onNodeWithTag("profile-save").assertIsNotEnabled()
        compose.runOnIdle { finish?.invoke(null) }
        compose.onNodeWithText("Saved.").assertIsDisplayed()
    }

    @Test fun blankEmergencyFieldsAreSentAsNull() {
        var saved: Saved? = null
        compose.setContent { MaterialTheme(colorScheme = activityColorScheme()) {
            ProfileScreen(ui, Resource(data = account.copy(emergencyContactName = null, emergencyContactPhone = null)), Resource(data = emptyList()),
                { d, p, pa, n, ph, done -> saved = Saved(d, p, pa, n, ph); done(null) }, {}, vehicle, {}, {})
        } }
        scrollTo("Save changes")
        compose.onNodeWithTag("profile-save").performClick()
        compose.runOnIdle { assertNull(saved?.name); assertNull(saved?.phone) }
    }

    @Test fun favoritesEmptyThenPopulatedWithAccessibleRemove() {
        show()
        scrollTo("No favorite drivers yet.")
        compose.onNodeWithText("No favorite drivers yet.").assertIsDisplayed()
    }

    @Test fun favoriteRemoveCallsExistingCallback() {
        var removed = ""
        show(favorites = listOf(FavoriteDriverSummary("d1", "Priya Shankar", null, 0L)), onRemove = { removed = it })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Priya Shankar"))
        compose.onNodeWithContentDescription("Remove Priya Shankar from favorite drivers").performClick()
        compose.runOnIdle { assertEquals("d1", removed) }
    }

    @Test fun signOutIsReachable() {
        var out = 0
        show(onSignOut = { out++ })
        scrollTo("Sign out")
        compose.onNodeWithText("Sign out").performClick()
        compose.runOnIdle { assertEquals(1, out) }
    }

    @Test fun passengerSwitchRowTogglesAndDoesNotClip() {
        show()
        compose.onNodeWithTag("profile-passenger-switch").assertIsDisplayed().performClick()
        compose.onNodeWithText("Lets other students find your seat requests when they offer a ride.").assertIsDisplayed()
    }

    @Test fun largeTextKeepsEverythingReachable() {
        show(fontScale = 2f, mode = AppMode.DRIVER)
        compose.onNodeWithText("Available as passenger").assertIsDisplayed()
        for (text in listOf("Manage vehicle", "Contact phone", "Save changes", "Sign out")) {
            scrollTo(text)
            compose.onNodeWithText(text).assertIsDisplayed()
        }
    }

    @Test fun darkSchemeRenders() {
        show(dark = true, mode = AppMode.DRIVER)
        compose.onNodeWithText("Monash account").assertIsDisplayed()
        scrollTo("Ready to offer rides")
        compose.onNodeWithText("Ready to offer rides").assertIsDisplayed()
    }

    @Test fun phoneFieldTakesTypedDigitsIntoTheSavedValue() {
        var saved: Saved? = null
        compose.setContent { MaterialTheme(colorScheme = activityColorScheme()) {
            ProfileScreen(ui, Resource(data = account.copy(emergencyContactPhone = null)), Resource(data = emptyList()),
                { d, p, pa, n, ph, done -> saved = Saved(d, p, pa, n, ph); done(null) }, {}, vehicle, {}, {})
        } }
        scrollTo("Contact phone")
        compose.onNodeWithText("Contact phone").performTextInput("0400 999 111")
        scrollTo("Save changes")
        compose.onNodeWithTag("profile-save").performClick()
        compose.runOnIdle { assertEquals("0400 999 111", saved?.phone) }
    }
}
