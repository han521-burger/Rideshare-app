package com.example.ds04_monash_rideshare_application.feature.requests

import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.requestStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val EXPIRED_SUPPORT = "No driver accepted in time."
internal val SEAT_FILTER_OPTIONS = listOf(1, 2, 3)

internal enum class RequestsContent { LOADING, ERROR, CONTENT, UNAVAILABLE }
internal data class RequestsState(val content: RequestsContent, val notice: String? = null, val failed: Boolean = false)

// Metadata only: lifecycle/ownership selection remains in the ViewModel/NavHost.
internal fun requestsState(resource: Resource<*>): RequestsState = when {
    resource.data != null -> RequestsState(RequestsContent.CONTENT, when {
        resource.errorMessage != null -> "Couldn’t refresh. Showing the last loaded data."
        resource.isLoading -> "Refreshing…"
        else -> null
    }, resource.errorMessage != null)
    resource.isLoading -> RequestsState(RequestsContent.LOADING)
    resource.errorMessage != null -> RequestsState(RequestsContent.ERROR)
    // Initial state and a message-less failure cannot be distinguished by this Resource.
    else -> RequestsState(RequestsContent.UNAVAILABLE)
}

internal fun requestsStatus(status: String, incoming: Boolean) = when (status) {
    RequestStatus.PENDING -> if (incoming) "Waiting" else "Waiting for driver"
    RequestStatus.EXPIRED -> "Expired"
    else -> requestStatusLabel(status)
}

internal fun seatFilterLabel(seats: Int?) = when (seats) {
    null -> "Any seats"
    3 -> "3 seats"
    else -> seats.toString()
}

internal fun filterSummary(schedule: Boolean?, seats: Int?, distanceActive: Boolean): String =
    "${when (schedule) { true -> "ASAP"; false -> "Scheduled"; null -> "Any time" }} · " +
        "${if (seats == null) "Any seats" else "$seats ${if (seats == 1) "seat" else "seats"}"} · " +
        if (distanceActive) "Distance sort on" else "Distance sort off"

// Existing user-selected narrowing/sorting, not request eligibility or recommendation ranking.
internal fun filterIncomingRequests(requests: List<RideRequestSummary>, seats: Int?, schedule: Boolean?,
    sortByDistance: Boolean, distances: Map<String, Double>): List<RideRequestSummary> {
    val filtered = requests.filter {
        (seats == null || it.seatsRequested == seats) && (schedule == null || it.isAsap == schedule)
    }
    return if (sortByDistance) filtered.sortedBy { distances[it.requestId] ?: Double.MAX_VALUE } else filtered
}

internal fun requestsDate(time: Long) = SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault()).format(Date(time))
internal fun requestTime(request: RideRequestSummary) = request.ride?.let { requestsDate(it.departureTime) }
    ?: if (request.isAsap) "ASAP" else request.desiredDepartureTime?.let(::requestsDate) ?: "No time set"
internal fun routeDistance(meters: Double) = String.format(Locale.getDefault(), "%.1f km route", meters / 1000.0)
internal fun drivingDistance(meters: Double, seconds: Long) =
    String.format(Locale.getDefault(), "%.1f km · %d min drive", meters / 1000.0, seconds / 60)

internal fun requestPlaces(request: RideRequestSummary): Pair<String, String>? {
    request.ride?.let { return describeLocation(it.originLat, it.originLng) to describeLocation(it.destinationLat, it.destinationLng) }
    val lat = request.originLat ?: return null
    val lng = request.originLng ?: return null
    val destLat = request.destinationLat ?: return null
    val destLng = request.destinationLng ?: return null
    return describeLocation(lat, lng) to describeLocation(destLat, destLng)
}
