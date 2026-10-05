package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.example.ds04_monash_rideshare_application.core.navigation.AppDestination
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.ui.BottomNavScaffold
import org.junit.Rule
import org.junit.Test

class ProfileShellTest {
    @get:Rule val compose = createComposeRule()

    private fun shell(mode: AppMode, checking: Boolean) = compose.setContent {
        BottomNavScaffold(mode, {}, AppDestination.Profile, {}, 0, null, 0, false, null, {}, {}, {}, "Amara",
            driverChecking = checking) { androidx.compose.material3.Text("Profile content") }
    }

    @Test fun profileUsesTheSharedBrandShell() {
        shell(AppMode.PASSENGER, checking = false)
        compose.onNodeWithText("Monash Rideshare").assertIsDisplayed()
        compose.onNodeWithText("Hi, Amara").assertIsDisplayed()
        compose.onNodeWithText("Passenger").assertIsSelected()
        compose.onNodeWithText("Driver").assertIsDisplayed()
        compose.onNodeWithText("Profile content").assertIsDisplayed()
    }

    @Test fun checkingKeepsPassengerSelectedAndShowsCheckingVehicleOnDriver() {
        shell(AppMode.PASSENGER, checking = true)
        compose.onNodeWithText("Passenger").assertIsSelected()
        // Visible text is decorative; TalkBack hears the segment's own name plus a "checking" state.
        compose.onNodeWithText("Checking vehicle…", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Driver")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Checking vehicle"))
    }

    @Test fun withoutCheckingTheDriverSegmentIsPlainAndNeverShowsCheckingText() {
        shell(AppMode.PASSENGER, checking = false)
        compose.onNodeWithText("Driver").assertIsDisplayed()
        compose.onNodeWithText("Checking vehicle…", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun driverModeShowsDriverSelectedAndNoCheckingText() {
        shell(AppMode.DRIVER, checking = false)
        compose.onNodeWithText("Driver").assertIsSelected()
        compose.onNodeWithText("Checking vehicle…", useUnmergedTree = true).assertDoesNotExist()
    }
}
