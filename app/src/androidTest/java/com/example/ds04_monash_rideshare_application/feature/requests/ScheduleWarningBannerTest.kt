package com.example.ds04_monash_rideshare_application.feature.requests

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.feature.activity.activityColorScheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// The schedule warning is a banner on an ACCEPTED request, only for the passenger it is about, and it is not an error: the
// request keeps its ACCEPTED presentation and a CONFLICTED request keeps its own "Schedule conflict" banner.
class ScheduleWarningBannerTest {
    @get:Rule val compose = createComposeRule()
    private val warning = "Your previous ride finished at 2:50 PM. You may not have enough time to reach your next ride at 2:55 PM."

    private fun request(status: String) = RideRequestSummary(
        "req-1", null, "me", 1, status, null, 1, 2, -37.8241, 144.9481, -37.9105, 145.1362, false, 1,
        emptyList(), null, null, null, null
    )

    private val status = mutableStateOf(RequestStatus.ACCEPTED)
    private val viewer = mutableStateOf("me")
    private val scheduleWarning = mutableStateOf<String?>(warning)

    private fun show() {
        compose.setContent {
            MaterialTheme(colorScheme = activityColorScheme()) {
                RequestDetailScreen(
                    request = request(status.value), currentUserId = viewer.value, myOpenRidesForAccept = emptyList(),
                    isActionInFlight = false, actionErrorMessage = null, onAcceptAttached = {}, onAcceptStandalone = {},
                    onCreateRideAndAccept = {}, onDecline = {}, onCancel = {}, onNavigateBack = {},
                    scheduleWarning = scheduleWarning.value
                )
            }
        }
    }

    @Test fun anAcceptedRequestShowsTheWarningToItsPassengerAndStaysAccepted() {
        show()
        compose.onNodeWithText("Schedule warning").assertIsDisplayed()
        compose.onNodeWithText(warning).assertIsDisplayed()
        // Still presented as accepted - nothing about the status changed.
        compose.onNodeWithText("Accepted").assertIsDisplayed()
        compose.onAllNodesWithText("Schedule conflict").let { assertTrue(it.fetchSemanticsNodes().isEmpty()) }
    }

    @Test fun theDriverOfTheRideNeverSeesThePassengersWarning() {
        viewer.value = "driver"
        show()
        compose.onNodeWithText("Schedule warning").assertDoesNotExist()
        compose.onNodeWithText(warning).assertDoesNotExist()
    }

    @Test fun onlyAnAcceptedRequestCarriesTheWarning() {
        show()
        compose.onNodeWithText("Schedule warning").assertIsDisplayed()
        for (other in listOf(RequestStatus.PENDING, RequestStatus.CANCELLED, RequestStatus.COMPLETED, RequestStatus.EXPIRED)) {
            compose.runOnIdle { status.value = other }
            compose.onNodeWithText("Schedule warning").assertDoesNotExist()
        }
    }

    @Test fun aConflictedRequestKeepsItsOwnBannerAndGetsNoWarning() {
        status.value = RequestStatus.CONFLICTED
        scheduleWarning.value = null
        show()
        // The status label and the banner title both read "Schedule conflict".
        assertTrue(compose.onAllNodesWithText("Schedule conflict").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Schedule warning").assertDoesNotExist()
    }
}
