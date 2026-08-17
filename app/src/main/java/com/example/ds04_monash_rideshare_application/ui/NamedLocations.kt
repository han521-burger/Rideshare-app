package com.example.ds04_monash_rideshare_application.ui

import com.yandex.mapkit.geometry.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NamedLocation(val name: String, val point: Point)

// Same locations the backend's seed data uses (backend/Database.kt) - shared between MapScreen's
// quick-jump buttons and describeLocation() below, so "near X" labels always match a place you
// can actually jump to on the map.
val NAMED_LOCATIONS = listOf(
    NamedLocation("Melbourne CBD", Point(-37.8136, 144.9631)),
    NamedLocation("Oakleigh", Point(-37.8988, 145.0906)),
    NamedLocation("Chadstone", Point(-37.8862, 145.0733)),
    NamedLocation("Glen Waverley", Point(-37.8811, 145.1628)),
    NamedLocation("Monash Clayton", Point(-37.9105, 145.1362))
)

private const val NEARBY_THRESHOLD_METERS = 1500.0

// No live reverse-geocoding (would need Yandex's separate Search Kit, not wired up here) - this
// is a best-effort label against the handful of named places this trial actually knows about,
// falling back to coordinates rounded to something readable rather than a raw double.
fun describeLocation(lat: Double, lng: Double): String {
    val nearest = NAMED_LOCATIONS.minByOrNull { haversineMeters(lat, lng, it.point.latitude, it.point.longitude) }
    if (nearest != null && haversineMeters(lat, lng, nearest.point.latitude, nearest.point.longitude) <= NEARBY_THRESHOLD_METERS) {
        return "Near ${nearest.name}"
    }
    return "%.4f, %.4f".format(lat, lng)
}

fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadius * c
}
