package com.example.ds04_monash_rideshare_application.feature.activity

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import com.example.ds04_monash_rideshare_application.core.state.Resource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ActivityScreenTest {
    @get:Rule val compose = createComposeRule()
    private val impact = DashboardSummary(7, 12.6, 5.8, emptyList())
    private val counts = RequestFunnelCounts(1, 2, 0, 0, 3, 4)
    private val analytics = AnalyticsSummary(counts, counts, null, emptyList(), List(7) { 0 }, null, null)
    private fun trip(id: String, driver: String) = RideSummary(id, driver, null, -37.91, 145.13,
        -37.88, 145.04, 1000.0, 300, 1L, 4, 2, null, "COMPLETED", 1L, false, null,
        emptyList(), null, null, null)

    @Test fun suppliedImpactAndLeaderboardCallback() {
        var opened = 0
        compose.setContent { ActivityScreen("me", Resource(data = impact), Resource(data = analytics),
            Resource(data = emptyList()), { opened++ }, {}) }
        compose.onNodeWithTag("impact-co2").assertTextEquals("${decimal(12.6)} kg")
        compose.onNodeWithTag("impact-trips").assertTextEquals("7")
        compose.onNodeWithTag("impact-fuel").assertTextEquals("${decimal(5.8)} L")
        compose.onNodeWithText("View leaderboard").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun unavailableAnalyticsKeepsHistoryAndFiltersOnlyHistory() {
        var opened = ""
        var retried = 0
        compose.setContent { ActivityScreen("me", Resource(data = impact), Resource(errorMessage = "offline"),
            Resource(data = listOf(trip("drive", "me"), trip("ride", "other"))), {}, { opened = it },
            onRetryAnalytics = { retried++ }) }
        compose.onNodeWithTag("activity-list").performScrollToNode(hasText("Analytics temporarily unavailable"))
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
        compose.onNodeWithTag("activity-list").performScrollToNode(hasText("Driving"))
        compose.onNodeWithText("Driving").performClick()
        compose.onNodeWithTag("activity-list").performScrollToNode(hasTestTag("history-drive"))
        compose.onNodeWithTag("history-drive").performClick()
        compose.runOnIdle { assertEquals("drive", opened) }
        compose.onNodeWithTag("history-ride").assertDoesNotExist()
        compose.onNodeWithTag("activity-list").performScrollToNode(hasText("Riding"))
        compose.onNodeWithText("Riding").performClick()
        compose.onNodeWithTag("activity-list").performScrollToNode(hasTestTag("history-ride"))
        compose.onNodeWithTag("history-drive").assertDoesNotExist()
        compose.onNodeWithTag("activity-list").performScrollToIndex(0)
        compose.onNodeWithTag("impact-trips").assertTextEquals("7")
    }

    @Test fun bothFunnelsIncludeExpired() {
        val driving = mutableStateOf(true)
        compose.setContent { ActivityTheme { OutcomeCard(counts, driving.value) } }
        compose.onNodeWithText("Driving insights").assertExists()
        compose.onNodeWithText("Expired").assertExists()
        compose.runOnIdle { driving.value = false }
        compose.onNodeWithText("Riding insights").assertExists()
        compose.onNodeWithText("Expired").assertExists()
    }

    @Test fun nullMetricsAreInsufficient() {
        compose.setContent { ActivityScreen("me", Resource(data = impact), Resource(data = analytics),
            Resource(data = emptyList()), {}, {}) }
        compose.onNodeWithTag("activity-list").performScrollToNode(hasText("Driving insights"))
        compose.onNodeWithTag("activity-list").performScrollToNode(hasText("More decision data needed for response time."))
        compose.onNodeWithText("More decision data needed for response time.").assertExists()
        compose.onNodeWithTag("activity-list").performScrollToNode(hasText("More trip data needed to identify a frequent route."))
        compose.onNodeWithText("More trip data needed to identify a frequent route.").assertExists()
        compose.onNodeWithTag("activity-list").performScrollToNode(hasText("More trip data needed for occupancy."))
        compose.onNodeWithText("More trip data needed for occupancy.").assertExists()
    }

    @Test fun loadingDoesNotInventImpactValues() {
        compose.setContent { ActivityScreen("me", Resource(isLoading = true), Resource(isLoading = true),
            Resource(data = emptyList()), {}, {}) }
        compose.onNodeWithText("Loading your impact").assertExists()
        compose.onNodeWithText("Loading analytics").assertExists()
        compose.onNodeWithTag("impact-co2").assertDoesNotExist()
    }
}
