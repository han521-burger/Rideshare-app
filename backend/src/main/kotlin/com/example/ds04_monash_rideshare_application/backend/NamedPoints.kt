package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.haversine

data class NamedPoint(val name: String, val lat: Double, val lng: Double)

// Mirrors the Android side's FIXED_POINTS (NamedLocations.kt) - kept in sync by hand, same reason
// MonashCampuses.kt duplicates the 5 campuses rather than importing an Android module. Every
// ride's endpoints are always one of these 25 points or a campus, since CampusRoutePicker never
// produces anything else - that's what makes a simple nearest-point match a reliable way to turn
// a raw coordinate back into a readable location name for analytics.
val NAMED_FIXED_POINTS = listOf(
    NamedPoint("Huntingdale Station", -37.9112, 145.1029),
    NamedPoint("Clayton Station", -37.9246, 145.1203),
    NamedPoint("Oakleigh Station", -37.9002, 145.0886),
    NamedPoint("Chadstone Shopping Centre", -37.8866, 145.0829),
    NamedPoint("Brandon Park Shopping Centre", -37.9006, 145.1640),
    NamedPoint("Waverley Gardens Shopping Centre", -37.9318, 145.1907),
    NamedPoint("Glen Waverley Station", -37.8795, 145.1621),
    NamedPoint("Springvale Station", -37.9497, 145.1530),
    NamedPoint("Westall Station", -37.9384, 145.1385),
    NamedPoint("Caulfield Station", -37.8775, 145.0425),
    NamedPoint("Carnegie Station", -37.8875, 145.0583),
    NamedPoint("Malvern Central", -37.8581, 145.0290),
    NamedPoint("South Yarra Station", -37.8384, 144.9922),
    NamedPoint("IKEA Richmond", -37.8283, 144.9978),
    NamedPoint("Royal Park Station", -37.7810, 144.9520),
    NamedPoint("Parkville Station", -37.7990, 144.9590),
    NamedPoint("Barkly Square", -37.7713, 144.9606),
    NamedPoint("Melbourne Central", -37.8102, 144.9628),
    NamedPoint("Southern Cross Station", -37.8184, 144.9525),
    NamedPoint("The District Docklands", -37.8126, 144.9373),
    NamedPoint("Footscray Station", -37.8011, 144.9031),
    NamedPoint("Frankston Station", -38.1428, 145.1258),
    NamedPoint("Leawarra Station", -38.1522, 145.1389),
    NamedPoint("Bayside Shopping Centre", -38.1420, 145.1240),
    NamedPoint("Karingal Hub", -38.1517, 145.1608)
)

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
