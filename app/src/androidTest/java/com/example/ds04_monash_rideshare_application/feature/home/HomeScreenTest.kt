package com.example.ds04_monash_rideshare_application.feature.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import com.example.ds04_monash_rideshare_application.core.state.*
import com.example.ds04_monash_rideshare_application.feature.activity.activityColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun ride() = RideSummary("test-ride", "other", null, -37.8241, 144.9481,
        -37.9105, 145.1362, 1000.0, 300, 1, 3, 1, null, RideStatus.CONFIRMED,
        1, false, null, emptyList(), null, null, null)

    @Test fun wholeCardHasOneActionAndOpensSuppliedRide() {
        var opened = ""
        compose.setContent { MaterialTheme(colorScheme = activityColorScheme()) {
            HomeScreen(AppMode.PASSENGER, "me", listOf(ride()), Resource(data = emptyList()),
                Resource(data = emptyList()), {}, { opened = it })
        } }
        compose.onNodeWithText("Your confirmed rides").assertExists()
        compose.onNodeWithTag("home-ride-test-ride").assertHasClickAction().performClick()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.runOnIdle { assertEquals("test-ride", opened) }
    }

    @Test fun passengerLoadingThenErrorThenEmptyAndRetry() {
        val requests = mutableStateOf(Resource<List<RideRequestSummary>>(isLoading = true))
        var retries = 0
        compose.setContent { MaterialTheme(colorScheme = activityColorScheme()) {
            HomeScreen(AppMode.PASSENGER, "me", emptyList(), Resource(), requests.value, { retries++ }, {})
        } }
        compose.onNodeWithContentDescription("Loading your rides").assertExists()
        compose.onNodeWithTag("home-empty").assertDoesNotExist()
        compose.runOnIdle { requests.value = Resource(errorMessage = "offline") }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithTag("home-empty").assertDoesNotExist()
        compose.runOnIdle { requests.value = Resource(data = emptyList()) }
        compose.onNodeWithText("No confirmed rides yet").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun driverFailureAndCapacityUseCurrentRole() {
        val rides = mutableStateOf(Resource<List<RideSummary>>(errorMessage = "offline"))
        var retries = 0
        compose.setContent { MaterialTheme(colorScheme = activityColorScheme(dark = true)) {
            HomeScreen(AppMode.DRIVER, "me", emptyList(), rides.value, Resource(), { retries++ }, {})
        } }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries); rides.value = Resource(data = listOf(ride())) }
        compose.onNodeWithText("2 / 3 seats filled", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("home-empty").assertDoesNotExist()
    }
}

