package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.haversine

data class CampusPoint(val name: String, val lat: Double, val lng: Double)

// The 5 Monash campuses - kept in sync by hand with the Android side's MONASH_CAMPUSES
// (NamedLocations.kt). Every ride/request must have one endpoint within CAMPUS_RADIUS_METERS of
// one of these; see requireMonashEndpoint below.
val MONASH_CAMPUSES = listOf(
    CampusPoint("Monash Clayton", -37.9105, 145.1362),
    CampusPoint("Monash Caulfield", -37.8774, 145.0449),
    CampusPoint("Monash Parkville", -37.7834, 144.9587),
    CampusPoint("Monash Peninsula", -38.1525, 145.1335),
    CampusPoint("Monash Melbourne City – 750 Collins St", -37.8216, 144.9471)
)

// The app only ever sends a campus's exact coordinates, so this just absorbs rounding. It must
// stay small: several approved points sit close to a campus (e.g. Leawarra Station ~0.5 km from
// Peninsula, Southern Cross Station near 750 Collins St) and must not count as the campus itself.
private const val CAMPUS_RADIUS_METERS = 300.0

fun isNearMonashCampus(lat: Double, lng: Double): Boolean =
    MONASH_CAMPUSES.any { haversine(lat, lng, it.lat, it.lng) <= CAMPUS_RADIUS_METERS }

// Every ride/request must run between a Monash campus and somewhere else - never two non-campus
// points. Throws ValidationException (400) if neither end qualifies.
fun requireMonashEndpoint(originLat: Double, originLng: Double, destinationLat: Double, destinationLng: Double) {
    if (!isNearMonashCampus(originLat, originLng) && !isNearMonashCampus(destinationLat, destinationLng)) {
        throw ValidationException("One end of the trip must be a Monash campus")
    }
}
