package com.example.ds04_monash_rideshare_application.feature.home

import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import com.example.ds04_monash_rideshare_application.core.state.*
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import org.junit.Assert.*
import org.junit.Test

class HomePresentationTest {
    private fun ride(id: String = "ride", driver: String = "other", time: Long = 1,
        status: String = RideStatus.CONFIRMED) = RideSummary(id, driver, null,
        -37.8811, 145.1628, -37.9105, 145.1362, 1000.0, 300, time, 3, 1,
        null, status, 1, false, null, emptyList(), null, null, null)
    private fun request(id: String, ride: RideSummary?, status: String = RequestStatus.ACCEPTED) =
        RideRequestSummary(id, ride?.rideId, "me", 1, status, null, 1, null,
            null, null, null, null, false, null, emptyList(), null, ride, null, null)
    private class DeferredApi : RideApiService by FakeRideApiService() {
        val requests = mutableListOf<RideApiService.Callback<List<RideRequestSummary>>>()
        val rides = mutableListOf<RideApiService.Callback<List<RideSummary>>>()
        override fun fetchMyRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) { requests += callback }
        override fun fetchMyDriverRides(callback: RideApiService.Callback<List<RideSummary>>) { rides += callback }
    }
    private fun state(mode: AppMode, resource: Resource<List<RideSummary>>) =
        homeContent(mode, "me", resource.data.orEmpty(), resource, resource)

    @Test fun passengerUsesActualViewModelDeduplicationOrderingAndNoFutureCutoff() {
        val api = DeferredApi()
        val vm = RideshareViewModel(api)
        val early = ride("past", time = 1)
        val later = ride("later", time = Long.MAX_VALUE)
        vm.loadMyRequests(); vm.loadMyDriverRides()
        api.requests.single().onSuccess(listOf(request("a", later), request("b", early),
            request("duplicate", early), request("pending", ride("pending"), RequestStatus.PENDING),
            request("completed", ride("completed", status = RideStatus.COMPLETED)), request("missing", null)))
        api.rides.single().onSuccess(listOf(ride("self", driver = "me")))
        val result = homeContent(AppMode.PASSENGER, "me", vm.confirmedRides, vm.myRequests, vm.myDriverRides)
        assertEquals(listOf("past", "later"), (result as HomeContent.Rides).rides.map { it.rideId })
    }
    @Test fun passengerSelfDrivenOnlyIsEmpty() {
        assertEquals(HomeContent.Empty, state(AppMode.PASSENGER, Resource(data = listOf(ride(driver = "me")))))
    }
    @Test fun driverFiltersConfirmedAndPreservesResponseOrder() {
        val rides = listOf(ride("later", time = 100), ride("open", status = RideStatus.OPEN),
            ride("past", time = 1), ride("cancelled", status = RideStatus.CANCELLED),
            ride("completed", status = RideStatus.COMPLETED), ride("expired", status = RideStatus.EXPIRED))
        assertEquals(listOf("later", "past"), (state(AppMode.DRIVER, Resource(data = rides)) as HomeContent.Rides).rides.map { it.rideId })
    }
    @Test fun successfulEmptyInBothModes() {
        AppMode.entries.forEach { assertEquals(HomeContent.Empty, state(it, Resource(data = emptyList()))) }
    }
    @Test fun initialResourceIsNotEmpty() {
        AppMode.entries.forEach { assertEquals(HomeContent.Loading, state(it, Resource())) }
    }
    @Test fun loadingWithOrWithoutCachedDataIsNotEmpty() {
        AppMode.entries.forEach { mode ->
            listOf(null, emptyList(), listOf(ride())).forEach { data ->
                assertEquals(HomeContent.Loading, state(mode, Resource(isLoading = true, data = data)))
            }
        }
    }
    @Test fun failureWithOrWithoutCachedDataIsNotEmpty() {
        AppMode.entries.forEach { mode ->
            listOf(null, emptyList(), listOf(ride())).forEach { data ->
                assertEquals(HomeContent.Error, state(mode, Resource(data = data, errorMessage = "offline")))
            }
        }
    }
    @Test fun irrelevantResourceCannotHideCurrentRoleContent() {
        assertTrue(homeContent(AppMode.PASSENGER, "me", listOf(ride()), Resource(data = emptyList<Any>()),
            Resource(isLoading = true)) is HomeContent.Rides)
        assertTrue(homeContent(AppMode.DRIVER, "me", emptyList(), Resource<Any>(errorMessage = "offline"),
            Resource(data = listOf(ride()))) is HomeContent.Rides)
    }
    @Test fun existingPassengerLoaderRetriesWithoutFetchingDriverRides() {
        val api = DeferredApi(); val vm = RideshareViewModel(api)
        vm.loadMyRequests(); api.requests[0].onFailure(Exception("offline"))
        assertEquals(HomeContent.Error, homeContent(AppMode.PASSENGER, "me", vm.confirmedRides, vm.myRequests, vm.myDriverRides))
        vm.loadMyRequests()
        assertTrue(vm.myRequests.isLoading); assertTrue(api.rides.isEmpty())
        api.requests[1].onSuccess(listOf(request("accepted", ride())))
        assertTrue(homeContent(AppMode.PASSENGER, "me", vm.confirmedRides, vm.myRequests, vm.myDriverRides) is HomeContent.Rides)
    }
    @Test fun existingDriverLoaderRetriesWithoutFetchingPassengerRequests() {
        val api = DeferredApi(); val vm = RideshareViewModel(api)
        vm.loadMyDriverRides(); api.rides[0].onFailure(Exception("offline"))
        assertEquals(HomeContent.Error, homeContent(AppMode.DRIVER, "me", vm.confirmedRides, vm.myRequests, vm.myDriverRides))
        vm.loadMyDriverRides()
        assertTrue(vm.myDriverRides.isLoading); assertTrue(api.requests.isEmpty())
        api.rides[1].onSuccess(emptyList())
        assertEquals(HomeContent.Empty, homeContent(AppMode.DRIVER, "me", vm.confirmedRides, vm.myRequests, vm.myDriverRides))
    }
    @Test fun copyIsConfirmedContextWithoutPriorityClaims() {
        assertEquals("Your confirmed rides", HOME_TITLE)
        assertEquals("No confirmed rides yet", HOME_EMPTY_TITLE)
        assertEquals("Rides you have joined", homeSubtitle(AppMode.PASSENGER))
        assertEquals("Rides you are driving", homeSubtitle(AppMode.DRIVER))
        AppMode.entries.forEach { mode ->
            val copy = listOf(HOME_TITLE, HOME_EMPTY_TITLE, homeSubtitle(mode), homeEmptyMessage(mode)).joinToString()
            assertFalse(copy.contains("upcoming", true)); assertFalse(copy.contains("next ride", true))
        }
    }
    @Test fun routesUseExistingApproximateFormatterIncludingCoordinateFallback() {
        val r = ride()
        assertEquals("Near Glen Waverley" to "Near Monash Clayton", homeRoute(r))
        val unknown = r.copy(originLat = 0.0, originLng = 0.0)
        assertEquals(describeLocation(0.0, 0.0), homeRoute(unknown).first)
    }
    @Test fun rideCapacityAlreadyExcludesDriverAndProgressIsBounded() {
        assertEquals(HomeSeats(2, 3), homeSeats(ride()))
        assertEquals("2 / 3 seats filled", homeSeats(ride()).label)
        assertEquals(HomeSeats(0, 0), homeSeats(ride().copy(seatsTotal = 0, seatsAvailable = 0)))
        assertEquals(0f, homeSeats(ride().copy(seatsTotal = 0)).fraction)
        assertEquals(HomeSeats(3, 3), homeSeats(ride().copy(seatsAvailable = -1)))
        assertEquals(HomeSeats(0, 3), homeSeats(ride().copy(seatsAvailable = 4)))
    }
    @Test fun messageLessFailuresRemainErrorsEvenWithCachedEmptyData() {
        val api = DeferredApi(); val vm = RideshareViewModel(api)
        vm.loadMyRequests(); vm.loadMyDriverRides()
        api.requests[0].onSuccess(emptyList()); api.rides[0].onSuccess(emptyList())
        vm.loadMyRequests(); vm.loadMyDriverRides()
        api.requests[1].onFailure(Exception()); api.rides[1].onFailure(Exception())
        AppMode.entries.forEach { mode ->
            assertEquals(HomeContent.Error, homeContent(mode, "me", vm.confirmedRides, vm.myRequests, vm.myDriverRides))
        }
    }
}
