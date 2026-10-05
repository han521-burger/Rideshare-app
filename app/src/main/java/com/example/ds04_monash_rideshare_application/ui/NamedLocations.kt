package com.example.ds04_monash_rideshare_application.ui

import com.yandex.mapkit.geometry.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NamedLocation(val name: String, val point: Point, val isCampus: Boolean = false)

// The 5 Monash campuses - every ride/request must have one endpoint at one of these (see
// CampusRoutePicker.kt and the backend's matching MonashCampuses.kt validation). Kept in sync by
// hand with the backend's MONASH_CAMPUSES.
val MONASH_CAMPUSES = listOf(
    NamedLocation("Monash Clayton", Point(-37.9105, 145.1362), isCampus = true),
    NamedLocation("Monash Caulfield", Point(-37.8774, 145.0449), isCampus = true),
    NamedLocation("Monash Parkville", Point(-37.7834, 144.9587), isCampus = true),
    NamedLocation("Monash Peninsula", Point(-38.1525, 145.1335), isCampus = true),
    NamedLocation("Monash Melbourne City – 750 Collins St", Point(-37.8216, 144.9471), isCampus = true)
)

// The approved non-campus pickup/drop-off points - the only places a ride can start or end
// besides a campus. A postcode/suburb search recommends the nearest of these (see
// ApprovedPointSearch.kt). Kept in sync by hand with the backend's NAMED_FIXED_POINTS.
val FIXED_POINTS = listOf(
    NamedLocation("Huntingdale Station", Point(-37.9112, 145.1029)),
    NamedLocation("Clayton Station", Point(-37.9246, 145.1203)),
    NamedLocation("Oakleigh Station", Point(-37.9002, 145.0886)),
    NamedLocation("Chadstone Shopping Centre", Point(-37.8866, 145.0829)),
    NamedLocation("Brandon Park Shopping Centre", Point(-37.9006, 145.1640)),
    NamedLocation("Waverley Gardens Shopping Centre", Point(-37.9318, 145.1907)),
    NamedLocation("Glen Waverley Station", Point(-37.8795, 145.1621)),
    NamedLocation("Springvale Station", Point(-37.9497, 145.1530)),
    NamedLocation("Westall Station", Point(-37.9384, 145.1385)),
    NamedLocation("Caulfield Station", Point(-37.8775, 145.0425)),
    NamedLocation("Carnegie Station", Point(-37.8875, 145.0583)),
    NamedLocation("Malvern Central", Point(-37.8581, 145.0290)),
    NamedLocation("South Yarra Station", Point(-37.8384, 144.9922)),
    NamedLocation("IKEA Richmond", Point(-37.8283, 144.9978)),
    NamedLocation("Royal Park Station", Point(-37.7810, 144.9520)),
    NamedLocation("Parkville Station", Point(-37.7990, 144.9590)),
    NamedLocation("Barkly Square", Point(-37.7713, 144.9606)),
    NamedLocation("Melbourne Central", Point(-37.8102, 144.9628)),
    NamedLocation("Southern Cross Station", Point(-37.8184, 144.9525)),
    NamedLocation("The District Docklands", Point(-37.8126, 144.9373)),
    NamedLocation("Footscray Station", Point(-37.8011, 144.9031)),
    NamedLocation("Frankston Station", Point(-38.1428, 145.1258)),
    NamedLocation("Leawarra Station", Point(-38.1522, 145.1389)),
    NamedLocation("Bayside Shopping Centre", Point(-38.1420, 145.1240)),
    NamedLocation("Karingal Hub", Point(-38.1517, 145.1608))
)

// Points from before the current list (FIXED_POINTS above). No longer offered anywhere - kept
// only so trips created at them still get a readable "Near X" label instead of raw coordinates.
private val RETIRED_POINTS = listOf(
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
    NamedLocation("Ringwood", Point(-37.8142, 145.2295)),
    // The old Monash Melbourne City / Peninsula pins, before they moved to the exact addresses.
    NamedLocation("Monash Melbourne City", Point(-37.8241, 144.9481)),
    NamedLocation("Monash Peninsula", Point(-38.1439, 145.1290))
)

// Every place a ride can currently start or end - what MapScreen's quick-jump buttons show.
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
    val nearest = (NAMED_LOCATIONS + RETIRED_POINTS).minByOrNull { haversineMeters(lat, lng, it.point.latitude, it.point.longitude) }
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
