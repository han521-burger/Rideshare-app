package com.example.ds04_monash_rideshare_application.feature.requests

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import com.example.ds04_monash_rideshare_application.core.state.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RequestsPresentationTest {
    private fun request(id: String, seats: Int = 1, asap: Boolean = false,
        status: String = RequestStatus.PENDING, responded: Long? = null, passenger: String = "other") =
        RideRequestSummary(id, null, passenger, seats, status, null, 1, responded,
            -37.9, 145.1, -37.91, 145.13, asap, 1000, emptyList(), null, null, null, null)
    private fun ride(id: String, status: String = RideStatus.OPEN) = RideSummary(id, "driver", null,
        -37.9, 145.1, -37.91, 145.13, 4200.0, 600, 1000, 3, 3, null, status,
        1, false, null, emptyList(), null, null, null)

    private class Api : RideApiService by FakeRideApiService() {
        val own = mutableListOf<RideApiService.Callback<List<RideRequestSummary>>>()
        val incoming = mutableListOf<RideApiService.Callback<List<RideRequestSummary>>>()
        val offers = mutableListOf<RideApiService.Callback<List<RideSummary>>>()
        val browse = mutableListOf<RideApiService.Callback<List<RideSummary>>>()
        override fun fetchMyRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) { own += callback }
        override fun fetchIncomingRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) { incoming += callback }
        override fun fetchMyDriverRides(callback: RideApiService.Callback<List<RideSummary>>) { offers += callback }
        override fun fetchOpenRides(callback: RideApiService.Callback<List<RideSummary>>) { browse += callback }
        override fun fetchMyProfile(callback: RideApiService.Callback<UserAccountSummary>) { /* No unrelated lookup. */ }
        // Signs in as user "me" so the incoming-request selector has an identity to exclude.
        override fun signIn(email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
            val user = UserAccountSummary("me", "Me", "Me", email, null, null, true, null, null)
            callback.onSuccess(AuthResult("token-me", user))
        }
    }

    @After fun clearSession() { AuthSession.clear() }

    @Test fun actualPendingSelectorKeepsRecentExpiryButBadgeDoesNot() {
        val api = Api(); val vm = RideshareViewModel(api)
        val now = System.currentTimeMillis()
        vm.loadMyRequests()
        api.own.single().onSuccess(listOf(request("pending"),
            request("recent", status = RequestStatus.EXPIRED, responded = now - 3_600_000),
            request("old", status = RequestStatus.EXPIRED, responded = now - 172_800_000),
            request("null", status = RequestStatus.EXPIRED), request("accepted", status = RequestStatus.ACCEPTED)))
        assertEquals(listOf("pending", "recent"), vm.myPendingAndRecentlyExpiredRequests.map { it.requestId })
        assertEquals(listOf("pending"), vm.myPendingRequests.map { it.requestId })
    }

    @Test fun actualIncomingSelectorExcludesOwnAndNonPending() {
        val api = Api(); val vm = RideshareViewModel(api)
        vm.signIn("me@student.monash.edu", "password", keepSignedIn = false)
        vm.loadIncomingRequests()
        api.incoming.single().onSuccess(listOf(request("own", passenger = "me"), request("standalone"),
            request("attached").copy(rideId = "ride", ride = ride("ride")), request("expired", status = RequestStatus.EXPIRED)))
        assertEquals(listOf("standalone", "attached"), vm.incomingPendingRequests.map { it.requestId })
    }

    @Test fun lockedCopyIsContextual() {
        assertEquals("Waiting for driver", requestsStatus(RequestStatus.PENDING, false))
        assertEquals("Waiting", requestsStatus(RequestStatus.PENDING, true))
        assertEquals("Expired", requestsStatus(RequestStatus.EXPIRED, false))
        assertEquals("No driver accepted in time.", EXPIRED_SUPPORT)
        assertEquals("3 seats", seatFilterLabel(3))
        assertFalse(SEAT_FILTER_OPTIONS.any { seatFilterLabel(it).contains("+") })
    }

    @Test fun noDataStatesNeverPretendToBeSuccessfulEmpty() {
        assertEquals(RequestsContent.LOADING, requestsState(Resource<Any>(isLoading = true)).content)
        assertEquals(RequestsContent.ERROR, requestsState(Resource<Any>(errorMessage = "offline")).content)
        assertEquals(RequestsContent.UNAVAILABLE, requestsState(Resource<Any>()).content)
        assertEquals(RequestsContent.CONTENT, requestsState(Resource(data = emptyList<Any>())).content)
    }

    @Test fun cachedDataRetainsContentDuringRefreshAndFailureIncludingCachedEmpty() {
        for (data in listOf(emptyList(), listOf(request("cached")))) {
            val refreshing = requestsState(Resource(isLoading = true, data = data))
            assertEquals(RequestsContent.CONTENT, refreshing.content)
            assertEquals("Refreshing…", refreshing.notice)
            val failed = requestsState(Resource(data = data, errorMessage = "offline"))
            assertEquals(RequestsContent.CONTENT, failed.content)
            assertTrue(failed.failed)
            assertNotNull(failed.notice)
        }
    }

    @Test fun nullErrorLimitationIsNotInventedIntoFailure() {
        assertEquals(RequestsContent.UNAVAILABLE, requestsState(Resource<Any>(errorMessage = null)).content)
        assertNull(requestsState(Resource(data = listOf(request("cached")), errorMessage = null)).notice)
    }

    @Test fun exactThreeExcludesFourAndKeepsResponseOrder() {
        val requests = listOf(request("four", 4), request("three-b", 3), request("one"), request("three-a", 3))
        assertEquals(listOf("three-b", "three-a"), filterIncomingRequests(requests, 3, null, false, emptyMap()).map { it.requestId })
        assertEquals(requests, filterIncomingRequests(requests, null, null, false, emptyMap()))
    }

    @Test fun scheduleFiltersUseExactAsapFlag() {
        val requests = listOf(request("scheduled"), request("asap", asap = true))
        assertEquals(listOf("asap"), filterIncomingRequests(requests, null, true, false, emptyMap()).map { it.requestId })
        assertEquals(listOf("scheduled"), filterIncomingRequests(requests, null, false, false, emptyMap()).map { it.requestId })
    }

    @Test fun distanceSortKeepsUnknownAndFarAwayRequests() {
        val requests = listOf(request("unknown"), request("far"), request("near"))
        assertEquals(listOf("near", "far", "unknown"), filterIncomingRequests(requests, null, null, true,
            mapOf("near" to 100.0, "far" to 500_000.0)).map { it.requestId })
    }

    @Test fun summaryReflectsStateAndRouteDistanceIsNotProximityOrRanking() {
        assertEquals("Any time · Any seats · Distance sort off", filterSummary(null, null, false))
        assertEquals("ASAP · 3 seats · Distance sort on", filterSummary(true, 3, true))
        assertTrue(routeDistance(4200.0).endsWith("km route"))
        assertFalse(routeDistance(4200.0).contains("%"))
        assertFalse(routeDistance(4200.0).contains("away"))
    }

    @Test fun existingBrowseRetryOnlyCallsBrowseAndClearsPreviousData() {
        val api = Api(); val vm = RideshareViewModel(api)
        val retry = vm::loadOpenRides
        retry(); api.browse.last().onSuccess(listOf(ride("one")))
        retry()
        assertTrue(vm.openRides.isLoading); assertNull(vm.openRides.data)
        api.browse.last().onFailure(Exception("offline"))
        assertEquals(RequestsContent.ERROR, requestsState(vm.openRides).content)
        retry(); api.browse.last().onSuccess(emptyList())
        assertEquals(RequestsContent.CONTENT, requestsState(vm.openRides).content)
        assertEquals(3, api.browse.size)
        assertTrue(api.own.isEmpty() && api.offers.isEmpty() && api.incoming.isEmpty())
    }

    @Test fun ownRequestsAndOffersLoadersRetainTheirCachedData() {
        val api = Api(); val vm = RideshareViewModel(api)
        vm.loadMyRequests(); api.own.last().onSuccess(listOf(request("cached")))
        vm.loadMyRequests(); api.own.last().onFailure(Exception("offline"))
        assertEquals("cached", vm.myPendingAndRecentlyExpiredRequests.single().requestId)
        vm.loadMyDriverRides(); api.offers.last().onSuccess(listOf(ride("b"), ride("confirmed", RideStatus.CONFIRMED), ride("a")))
        vm.loadMyDriverRides(); api.offers.last().onFailure(Exception("offline"))
        // This is the unchanged NavHost OPEN-only projection, not a new presentation selector.
        assertEquals(listOf("b", "a"), vm.myDriverRides.data.orEmpty().filter { it.status == RideStatus.OPEN }.map { it.rideId })
    }

    @Test fun quietIncomingPollRetainsContentAndDoesNotShowSkeleton() {
        val api = Api(); val vm = RideshareViewModel(api)
        vm.loadIncomingRequests(); api.incoming.last().onSuccess(listOf(request("cached")))
        vm.loadIncomingRequests(showLoading = false, requireFresh = false)
        assertFalse(vm.incomingRequests.isLoading)
        assertEquals(RequestsContent.CONTENT, requestsState(vm.incomingRequests).content)
        api.incoming.last().onFailure(Exception("offline"))
        assertEquals("cached", vm.incomingPendingRequests.single().requestId)
        assertTrue(requestsState(vm.incomingRequests).failed)
    }
}
