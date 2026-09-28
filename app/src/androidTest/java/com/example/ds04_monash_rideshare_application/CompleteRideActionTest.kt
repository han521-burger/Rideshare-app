package com.example.ds04_monash_rideshare_application

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.example.ds04_monash_rideshare_application.feature.rides.CompleteRideAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CompleteRideActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ownerMustConfirmAndCanDismiss() {
        var calls = 0
        compose.setContent { MaterialTheme { CompleteRideAction("ride", "owner", "owner", "CONFIRMED", false) { calls++ } } }
        compose.onNodeWithText("Complete ride").performClick()
        compose.onNodeWithText("Complete this ride?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Complete ride").performClick()
        compose.onAllNodesWithText("Complete ride")[1].performClick()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithText("Complete this ride?").assertDoesNotExist()
    }

    @Test fun passengerAndTerminalRidesHaveNoAction() {
        val user = mutableStateOf("passenger")
        val status = mutableStateOf("OPEN")
        compose.setContent { CompleteRideAction("ride", "owner", user.value, status.value, false) {} }
        compose.onNodeWithText("Complete ride").assertDoesNotExist()
        compose.runOnIdle { user.value = "owner" }
        compose.onNodeWithText("Complete ride").assertIsDisplayed()
        for (terminal in listOf("CANCELLED", "COMPLETED")) {
            compose.runOnIdle { status.value = terminal }
            compose.onNodeWithText("Complete ride").assertDoesNotExist()
        }
    }

    @Test fun inFlightDisablesConfirmationAndCompletion() {
        val busy = mutableStateOf(false)
        var calls = 0
        compose.setContent { CompleteRideAction("ride", "owner", "owner", "OPEN", busy.value) { calls++ } }
        compose.onNodeWithText("Complete ride").performClick()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithText("Complete ride").assertIsNotEnabled()
        compose.onNodeWithText("Complete ride").performClick()
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
