package com.example.ds04_monash_rideshare_application.feature.requests

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import com.example.ds04_monash_rideshare_application.core.navigation.AppDestination
import com.example.ds04_monash_rideshare_application.core.state.*
import com.example.ds04_monash_rideshare_application.core.ui.BottomNavScaffold
import com.example.ds04_monash_rideshare_application.feature.activity.activityColorScheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RequestsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val mode = mutableStateOf(AppMode.PASSENGER)
    private val own = mutableStateOf(Resource<List<RideRequestSummary>>(data = emptyList()))
    private val incoming = mutableStateOf(Resource<List<RideRequestSummary>>(data = emptyList()))
    private val browse = mutableStateOf(Resource<List<RideSummary>>(data = emptyList()))
    private val offers = mutableStateOf(Resource<List<RideSummary>>(data = emptyList()))
    private var openedRequest = ""
    private var openedRide = ""
    private var creates = 0
    private var offerCreates = 0
    private var searches = 0
    private var retries = 0
    private fun ride(id: String = "ride", status: String = RideStatus.OPEN) = RideSummary(id, "driver", null,
        -37.8241, 144.9481, -37.9105, 145.1362, 4200.0, 600, 1, 3, 3,
        null, status, 1, false, null, emptyList(), null, null, null)
    private fun request(id: String = "ask", status: String = RequestStatus.PENDING, seats: Int = 1, asap: Boolean = false) =
        RideRequestSummary(id, null, "other", seats, status, "Can meet near campus", 1, 2,
            -37.8241, 144.9481, -37.9105, 145.1362, asap, 1, emptyList(), null, null, null, null)

    private fun show(dark: Boolean = false, scale: Float = 1f) {
        compose.setContent {
            MaterialTheme(colorScheme = activityColorScheme(dark)) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    RequestsScreen(mode.value, own.value.data.orEmpty(), incoming.value.data.orEmpty(), browse.value,
                        offers.value.data.orEmpty().filter { it.status == RideStatus.OPEN }, own.value, incoming.value, offers.value,
                        onRetryOpenRides = { retries++ }, onOpenRequest = { openedRequest = it }, onOpenRide = { openedRide = it },
                        onFindRide = { searches++ }, onRequestRide = { creates++ }, onOfferRide = { offerCreates++ })
                }
            }
        }
    }
    private fun right() = compose.onNodeWithTag("requests-tab-1").performClick()

    @Test fun pendingCardOwnsOneDetailAction() {
        own.value = Resource(data = listOf(request()))
        show()
        compose.onNodeWithText("Waiting for driver", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("request-ask").assertHasClickAction().performClick()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("request-ask")), useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals("ask", openedRequest) }
    }

    @Test fun pendingEmptyHasOnlyFabCreateAndExpiredHasSeparateRecovery() {
        show()
        compose.onNodeWithText("No pending requests").assertExists()
        compose.onNodeWithContentDescription("Request a ride").performClick()
        compose.runOnIdle { assertEquals(1, creates); own.value = Resource(data = listOf(request(status = RequestStatus.EXPIRED))) }
        compose.onNodeWithText("Expired", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(EXPIRED_SUPPORT, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Request again").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, creates); assertEquals("", openedRequest) }
        compose.onNodeWithTag("request-ask").performClick()
        compose.runOnIdle { assertEquals("ask", openedRequest) }
    }

    @Test fun browseLoadingErrorRetryEmptyAndSearch() {
        browse.value = Resource(isLoading = true)
        show(); right()
        compose.onNodeWithTag("requests-loading").assertExists()
        compose.onNodeWithTag("requests-empty").assertDoesNotExist()
        compose.onNodeWithTag("requests-create").assertDoesNotExist()
        compose.runOnIdle { browse.value = Resource(errorMessage = "offline") }
        compose.onNodeWithText("Couldn’t load available rides").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries); browse.value = Resource(data = emptyList()) }
        compose.onNodeWithText("No rides available to join").assertExists()
        compose.onNodeWithText("Search by route and time").performClick()
        compose.runOnIdle { assertEquals(1, searches) }
    }

    @Test fun browseCardHasOnlyRideNavigationAndRouteDistance() {
        browse.value = Resource(data = listOf(ride()))
        show(); right()
        compose.onNodeWithText(routeDistance(4200.0), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ride-ride").performClick()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("ride-ride")), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Request a seat").assertDoesNotExist()
        compose.runOnIdle { assertEquals("ride", openedRide) }
    }

    @Test fun driverOffersOnlyOpenAndFabUsesOfferCallback() {
        mode.value = AppMode.DRIVER
        offers.value = Resource(data = listOf(ride(), ride("confirmed", RideStatus.CONFIRMED)))
        show()
        compose.onNodeWithText("Open for requests", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ride-confirmed").assertDoesNotExist()
        compose.onNodeWithTag("ride-ride").performClick()
        compose.onNodeWithContentDescription("Offer a ride").performClick()
        compose.runOnIdle { assertEquals("ride", openedRide); assertEquals(1, offerCreates); assertEquals(0, creates); offers.value = Resource(data = emptyList()) }
        compose.onNodeWithText("No open offers").assertExists()
        right(); compose.onNodeWithTag("requests-create").assertDoesNotExist()
    }

    @Test fun incomingDisclosureRetainsFiltersAndExactThreeExcludesFour() {
        mode.value = AppMode.DRIVER
        incoming.value = Resource(data = listOf(request("three", seats = 3, asap = true), request("four", seats = 4)))
        show(); right()
        compose.onNodeWithTag("seat-filter-3").performScrollTo().performClick()
        compose.onNode(hasText("ASAP") and isSelectable()).performScrollTo().performClick()
        compose.onNodeWithTag("filter-disclosure").performClick()
        compose.onNodeWithText("ASAP · 3 seats · Distance sort off").assertExists()
        compose.onNodeWithTag("request-three").performScrollTo().performClick()
        compose.onNodeWithTag("request-four").assertDoesNotExist()
        compose.onNodeWithText("Accept").assertDoesNotExist()
        compose.onNodeWithText("Decline").assertDoesNotExist()
        compose.runOnIdle { assertEquals("three", openedRequest) }
        compose.onNodeWithTag("filter-disclosure").performScrollTo().performClick()
        compose.onNodeWithTag("seat-filter-3").assertIsSelected()
    }

    @Test fun incomingTrueEmptyAndFilteredEmptyAreDifferent() {
        mode.value = AppMode.DRIVER; show(); right()
        compose.onNodeWithText("No incoming requests").performScrollTo().assertExists()
        compose.runOnIdle { incoming.value = Resource(data = listOf(request())) }
        compose.onNodeWithTag("seat-filter-3").performScrollTo().performClick()
        compose.onNodeWithText("No requests match these filters").performScrollTo().assertExists()
    }

    @Test fun incomingAttachedAndStandaloneBothNavigate() {
        mode.value = AppMode.DRIVER
        incoming.value = Resource(data = listOf(request("attached").copy(rideId = "ride", ride = ride()), request("standalone")))
        show(); right(); compose.onNodeWithTag("filter-disclosure").performClick()
        for (id in listOf("attached", "standalone")) {
            compose.onNodeWithTag("request-$id").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(id, openedRequest) }
        }
    }

    @Test fun sharedSubtabIndexSurvivesModeChange() {
        show(); right()
        compose.runOnIdle { mode.value = AppMode.DRIVER }
        compose.onNodeWithTag("requests-tab-1").assertIsSelected()
        compose.onNodeWithTag("filter-disclosure").assertExists()
        compose.onNodeWithTag("requests-create").assertDoesNotExist()
        compose.onNodeWithTag("requests-tab-0").performClick()
        compose.runOnIdle { mode.value = AppMode.PASSENGER }
        compose.onNodeWithTag("requests-tab-0").assertIsSelected()
        compose.onNodeWithContentDescription("Request a ride").assertExists()
    }

    @Test fun cachedRequestsRemainVisibleOnRefreshFailure() {
        own.value = Resource(data = listOf(request()))
        show()
        compose.runOnIdle { own.value = own.value.copy(isLoading = true) }
        compose.onNodeWithTag("request-ask").assertExists()
        compose.onNodeWithTag("requests-loading").assertDoesNotExist()
        compose.runOnIdle { own.value = own.value.copy(isLoading = false, errorMessage = "offline") }
        compose.onNodeWithTag("request-ask").assertExists()
        compose.onNodeWithTag("requests-notice").assertExists()
        compose.onNodeWithTag("requests-empty").assertDoesNotExist()
    }

    @Test fun darkLargeTextKeepsTabsAndCardOperable() {
        own.value = Resource(data = listOf(request()))
        show(dark = true, scale = 2f)
        compose.onNodeWithTag("request-ask").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("ask", openedRequest) }
        right(); compose.onNodeWithText("Search by route and time").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, searches) }
    }

    // The banner floats over the page like a phone notification: the content never moves when it
    // appears or goes away, so dismissing it can't leave a blank gap.
    @Test fun bannerFloatsOverContentWithoutMovingIt() {
        val banner = mutableStateOf<RideRequestSummary?>(request().copy(rideId = "ride", ride = ride()))
        compose.setContent {
            BottomNavScaffold(AppMode.DRIVER, {}, AppDestination.Requests, {}, 2, banner.value, 2, false,
                null, {}, {}, {}, "Amara") { padding ->
                Box(Modifier.padding(padding).fillMaxSize().testTag("reserved-content")) { Text("Requests content") }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("global-request-banner").assertIsDisplayed()
        val withBanner = compose.onNodeWithTag("reserved-content").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { banner.value = null }
        compose.waitForIdle()
        compose.onNodeWithTag("global-request-banner").assertDoesNotExist()
        assertEquals(withBanner, compose.onNodeWithTag("reserved-content").fetchSemanticsNode().boundsInRoot.top)
    }
    @Test fun largeTextBannerKeepsContentAndAllAttachedActionsReachable() {
        val banner = mutableStateOf(request())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                BottomNavScaffold(AppMode.DRIVER, {}, AppDestination.Requests, {}, 2, banner.value, 2, false,
                    null, {}, {}, {}, "Amara") { padding ->
                    Box(Modifier.padding(padding).fillMaxSize().testTag("reserved-content")) { Text("Requests content") }
                }
            }
        }
        compose.onNodeWithText("2 WAITING").assertIsDisplayed()
        compose.onNodeWithText("Review matching request").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reserved-content").assertHeightIsAtLeast(96.dp)
        compose.runOnIdle { banner.value = request().copy(rideId = "ride", ride = ride()) }
        for (action in listOf("Review", "Accept", "Decline")) compose.onNodeWithText(action).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reserved-content").assertHeightIsAtLeast(96.dp)
    }
}
