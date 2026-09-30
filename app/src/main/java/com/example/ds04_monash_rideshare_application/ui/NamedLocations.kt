package com.example.ds04_monash_rideshare_application.ui

import com.yandex.mapkit.geometry.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NamedLocation(val name: String, val point: Point, val isCampus: Boolean = false)

// The 5 Monash campuses - every ride/request must have one endpoint at one of these (see
// CampusRoutePicker.kt and the backend's matching MonashCampuses.kt validation).
val MONASH_CAMPUSES = listOf(
    NamedLocation("Monash Clayton", Point(-37.9105, 145.1362), isCampus = true),
    NamedLocation("Monash Caulfield", Point(-37.8774, 145.0449), isCampus = true),
    NamedLocation("Monash Parkville", Point(-37.7834, 144.9587), isCampus = true),
    NamedLocation("Monash Peninsula", Point(-38.1439, 145.1290), isCampus = true),
    NamedLocation("Monash Melbourne City", Point(-37.8241, 144.9481), isCampus = true)
)

// Non-campus pickup/drop-off points a postcode search can recommend (nearest of these to the
// geocoded postcode - see PostcodeGeocoder.kt). Spread around the 5 campuses above so a postcode
// search has reasonable coverage across Melbourne, not just near Clayton.
val FIXED_POINTS = listOf(
    NamedLocation("Melbourne CBD", Point(-37.8136, 144.9631)),
    NamedLocation("Oakleigh", Point(-37.8988, 145.0906)),
    NamedLocation("Chadstone", Point(-37.8862, 145.0733)),
    NamedLocation("Glen Waverley", Point(-37.8811, 145.1628)),
    NamedLocation("Carlton", Point(-37.8000, 144.9667)),
    NamedLocation("Brunswick", Point(-37.7667, 144.9597)),
    NamedLocation("Richmond", Point(-37.8183, 144.9946)),
    NamedLocation("St Kilda", Point(-37.8676, 144.9808)),
    NamedLocation("Malvern", Point(-37.8578, 145.0295)),
    NamedLocation("Camberwell", Point(-37.8262, 145.0587)),
    NamedLocation("Box Hill", Point(-37.8192, 145.1225)),
    NamedLocation("Doncaster", Point(-37.7833, 145.1247)),
    NamedLocation("Mount Waverley", Point(-37.8767, 145.1297)),
    NamedLocation("Springvale", Point(-37.9500, 145.1500)),
    NamedLocation("Dandenong", Point(-37.9874, 145.2151)),
    NamedLocation("Bentleigh", Point(-37.9187, 145.0333)),
    NamedLocation("Hampton", Point(-37.9391, 145.0089)),
    NamedLocation("Frankston", Point(-38.1450, 145.1220)),
    NamedLocation("Mornington", Point(-38.2201, 145.0384)),
    NamedLocation("Ringwood", Point(-37.8142, 145.2295))
)

// Same locations the backend's seed data uses (backend/Database.kt) - shared between MapScreen's
// quick-jump buttons and describeLocation() below, so "near X" labels always match a place you
// can actually jump to on the map.
val NAMED_LOCATIONS = MONASH_CAMPUSES + FIXED_POINTS

// The nearest fixed (non-campus) point to an arbitrary geocoded location - what a postcode search
// recommends. Never recommends a campus itself; campuses are chosen explicitly in the picker.
fun nearestFixedPoint(lat: Double, lng: Double): NamedLocation =
    FIXED_POINTS.minBy { haversineMeters(lat, lng, it.point.latitude, it.point.longitude) }

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
