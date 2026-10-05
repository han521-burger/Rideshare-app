package com.example.ds04_monash_rideshare_application.core.location

import com.example.ds04_monash_rideshare_application.ui.APPROVED_POINTS
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import com.example.ds04_monash_rideshare_application.ui.haversineMeters

enum class LocationInputKind { EMPTY, POSTCODE, PLACE }

fun locationInputKind(input: String): LocationInputKind = when {
    input.isBlank() -> LocationInputKind.EMPTY
    input.trim().matches(Regex("[0-9]{4}")) -> LocationInputKind.POSTCODE
    else -> LocationInputKind.PLACE
}

data class ApprovedPointDistance(val location: NamedLocation, val distanceMeters: Double)

// Pure geographic ranking over the canonical dataset; geocoder coordinates never become endpoints.
// Campuses are included (campus-to-campus trips); `excluded` drops the trip's own campus.
fun nearestApprovedPoints(
    latitude: Double,
    longitude: Double,
    points: List<NamedLocation> = APPROVED_POINTS,
    excluded: NamedLocation? = null,
    limit: Int = 3
): List<ApprovedPointDistance> {
    if (limit <= 0 || !latitude.isFinite() || !longitude.isFinite()) return emptyList()
    return points.asSequence()
        .filter { excluded == null || haversineMeters(it.point.latitude, it.point.longitude,
            excluded.point.latitude, excluded.point.longitude) > 1.0 }
        .map { ApprovedPointDistance(it, haversineMeters(latitude, longitude, it.point.latitude, it.point.longitude)) }
        .sortedWith(compareBy<ApprovedPointDistance> { it.distanceMeters }.thenBy { it.location.name })
        .take(limit).toList()
}

fun approvedRouteEndpoints(campus: NamedLocation, approvedPoint: NamedLocation, fromMonash: Boolean) =
    if (fromMonash) campus to approvedPoint else approvedPoint to campus
