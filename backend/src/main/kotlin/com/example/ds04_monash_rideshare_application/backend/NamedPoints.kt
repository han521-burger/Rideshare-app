package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.haversine

data class NamedPoint(val name: String, val lat: Double, val lng: Double)

// The approved non-campus hubs, as a view of APPROVED_POINTS (ApprovedPoints.kt) - the one place their
// names and coordinates are written down. The Android side's FIXED_POINTS (NamedLocations.kt) is checked
// against it by ApprovedPointsParityTest. Every ride's endpoints are always one of these 25 hubs or a
// campus, since CampusRoutePicker never produces anything else - that's what makes a nearest-point match
// a reliable way to turn a raw coordinate back into a readable location name for analytics.
val NAMED_FIXED_POINTS: List<NamedPoint> = APPROVED_POINTS.filter { !it.isCampus }.map { NamedPoint(it.name, it.lat, it.lng) }

// Points from before the current list - no longer offered by the app, kept only so analytics can
// still name trips that were created at them.
private val RETIRED_POINTS = listOf(
    NamedPoint("Melbourne CBD", -37.8136, 144.9631),
    NamedPoint("Oakleigh", -37.8988, 145.0906),
    NamedPoint("Chadstone", -37.8862, 145.0733),
    NamedPoint("Glen Waverley", -37.8811, 145.1628),
    NamedPoint("Carlton", -37.8000, 144.9667),
    NamedPoint("Brunswick", -37.7667, 144.9597),
    NamedPoint("Richmond", -37.8183, 144.9946),
    NamedPoint("St Kilda", -37.8676, 144.9808),
    NamedPoint("Malvern", -37.8578, 145.0295),
    NamedPoint("Camberwell", -37.8262, 145.0587),
    NamedPoint("Box Hill", -37.8192, 145.1225),
    NamedPoint("Doncaster", -37.7833, 145.1247),
    NamedPoint("Mount Waverley", -37.8767, 145.1297),
    NamedPoint("Springvale", -37.9500, 145.1500),
    NamedPoint("Dandenong", -37.9874, 145.2151),
    NamedPoint("Bentleigh", -37.9187, 145.0333),
    NamedPoint("Hampton", -37.9391, 145.0089),
    NamedPoint("Frankston", -38.1450, 145.1220),
    NamedPoint("Mornington", -38.2201, 145.0384),
    NamedPoint("Ringwood", -37.8142, 145.2295),
    // The old Monash Melbourne City / Peninsula pins, before they moved to the exact addresses.
    NamedPoint("Monash Melbourne City", -37.8241, 144.9481),
    NamedPoint("Monash Peninsula", -38.1439, 145.1290)
)

private val ALL_NAMED_POINTS: List<NamedPoint> by lazy {
    NAMED_FIXED_POINTS + MONASH_CAMPUSES.map { NamedPoint(it.name, it.lat, it.lng) } + RETIRED_POINTS
}

private const val NAMED_POINT_RADIUS_METERS = 500.0

// Best-effort label for analytics grouping - null if nothing known is close enough, which simply
// drops that coordinate out of the hotzone/route tally rather than mislabeling it.
fun nearestNamedPointLabel(lat: Double, lng: Double): String? =
    ALL_NAMED_POINTS
        .map { it.name to haversine(lat, lng, it.lat, it.lng) }
        .filter { (_, distance) -> distance <= NAMED_POINT_RADIUS_METERS }
        .minByOrNull { (_, distance) -> distance }
        ?.first
