package com.example.ds04_monash_rideshare_application.feature.home

import com.example.ds04_monash_rideshare_application.Repository.Remote.RideStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.ui.describeLocation

internal const val HOME_TITLE = "Your confirmed rides"
internal const val HOME_EMPTY_TITLE = "No confirmed rides yet"
internal fun homeSubtitle(mode: AppMode) = if (mode == AppMode.PASSENGER) "Rides you have joined" else "Rides you are driving"
internal fun homeEmptyMessage(mode: AppMode) = if (mode == AppMode.PASSENGER)
    "Use Requests to browse rides and join one." else "Use Requests to offer a ride or accept a request."

internal sealed interface HomeContent {
    data object Loading : HomeContent
    data object Error : HomeContent
    data object Empty : HomeContent
    data class Rides(val rides: List<RideSummary>) : HomeContent
}

// Passenger derivation stays in ViewModel.confirmedRides. Driver order remains API order.
internal fun homeContent(mode: AppMode, userId: String?, confirmedRides: List<RideSummary>,
    myRequests: Resource<*>, myDriverRides: Resource<List<RideSummary>>): HomeContent {
    val resource = if (mode == AppMode.PASSENGER) myRequests else myDriverRides
    if (resource.isLoading) return HomeContent.Loading
    if (resource.errorMessage != null) return HomeContent.Error
    // Initial Resource is not a successful empty response.
    if (resource.data == null) return HomeContent.Loading
    val rides = if (mode == AppMode.PASSENGER) confirmedRides.filter { it.driverId != userId }
        else myDriverRides.data.orEmpty().filter { it.status == RideStatus.CONFIRMED }
    return if (rides.isEmpty()) HomeContent.Empty else HomeContent.Rides(rides)
}

internal data class HomeSeats(val filled: Int, val total: Int) {
    val fraction: Float get() = if (total > 0) filled.toFloat() / total else 0f
    val label: String get() = "$filled / $total seats filled"
}

// Ride.seatsTotal already excludes the driver; do not subtract the driver again.
internal fun homeSeats(ride: RideSummary): HomeSeats {
    val total = ride.seatsTotal.coerceAtLeast(0)
    return HomeSeats((total - ride.seatsAvailable).coerceIn(0, total), total)
}

internal fun homeRoute(ride: RideSummary) =
    describeLocation(ride.originLat, ride.originLng) to describeLocation(ride.destinationLat, ride.destinationLng)

