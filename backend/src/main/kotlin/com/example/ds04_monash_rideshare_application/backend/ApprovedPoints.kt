package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.haversine
import java.util.Locale

// THE source of truth for every approved pickup/drop-off point: a permanent machine id, the display
// name, and the coordinates. Nothing else in the backend keeps its own copy of these:
//  * NAMED_FIXED_POINTS (NamedPoints.kt) and MONASH_CAMPUSES (MonashCampuses.kt) are views derived
//    from this list, so they cannot drift from it;
//  * the travel-time matrix (travel-times.json) is keyed by these ids;
//  * requests store these ids (RideRequests.originPointId / destinationPointId).
// The Android app keeps its own copy of the names and coordinates (ui/NamedLocations.kt, FIXED_POINTS and
// MONASH_CAMPUSES) because the two modules cannot import each other; ApprovedPointsParityTest in the app
// module fails if the two ever disagree.
//
// IDs are permanent: never rename or reuse one, because stored requests and the travel-time matrix refer
// to them. To add a point: add it here AND in the app's NamedLocations.kt, then regenerate the travel-time
// matrix (docs/TRAVEL_TIMES.md); the matrix completeness test fails until you do.
data class ApprovedPoint(val id: String, val name: String, val lat: Double, val lng: Double, val isCampus: Boolean)

val APPROVED_POINTS: List<ApprovedPoint> = listOf(
    // Approved non-campus hubs (stations, shopping centres).
    ApprovedPoint("huntingdale-station", "Huntingdale Station", -37.9112, 145.1029, isCampus = false),
    ApprovedPoint("clayton-station", "Clayton Station", -37.9246, 145.1203, isCampus = false),
    ApprovedPoint("oakleigh-station", "Oakleigh Station", -37.9002, 145.0886, isCampus = false),
    ApprovedPoint("chadstone-shopping-centre", "Chadstone Shopping Centre", -37.8866, 145.0829, isCampus = false),
    ApprovedPoint("brandon-park-shopping-centre", "Brandon Park Shopping Centre", -37.9006, 145.1640, isCampus = false),
    ApprovedPoint("waverley-gardens-shopping-centre", "Waverley Gardens Shopping Centre", -37.9318, 145.1907, isCampus = false),
    ApprovedPoint("glen-waverley-station", "Glen Waverley Station", -37.8795, 145.1621, isCampus = false),
    ApprovedPoint("springvale-station", "Springvale Station", -37.9497, 145.1530, isCampus = false),
    ApprovedPoint("westall-station", "Westall Station", -37.9384, 145.1385, isCampus = false),
    ApprovedPoint("caulfield-station", "Caulfield Station", -37.8775, 145.0425, isCampus = false),
    ApprovedPoint("carnegie-station", "Carnegie Station", -37.8875, 145.0583, isCampus = false),
    ApprovedPoint("malvern-central", "Malvern Central", -37.8581, 145.0290, isCampus = false),
    ApprovedPoint("south-yarra-station", "South Yarra Station", -37.8384, 144.9922, isCampus = false),
    ApprovedPoint("ikea-richmond", "IKEA Richmond", -37.8283, 144.9978, isCampus = false),
    ApprovedPoint("royal-park-station", "Royal Park Station", -37.7810, 144.9520, isCampus = false),
    ApprovedPoint("parkville-station", "Parkville Station", -37.7990, 144.9590, isCampus = false),
    ApprovedPoint("barkly-square", "Barkly Square", -37.7713, 144.9606, isCampus = false),
    ApprovedPoint("melbourne-central", "Melbourne Central", -37.8102, 144.9628, isCampus = false),
    ApprovedPoint("southern-cross-station", "Southern Cross Station", -37.8184, 144.9525, isCampus = false),
    ApprovedPoint("the-district-docklands", "The District Docklands", -37.8126, 144.9373, isCampus = false),
    ApprovedPoint("footscray-station", "Footscray Station", -37.8011, 144.9031, isCampus = false),
    ApprovedPoint("frankston-station", "Frankston Station", -38.1428, 145.1258, isCampus = false),
    ApprovedPoint("leawarra-station", "Leawarra Station", -38.1522, 145.1389, isCampus = false),
    ApprovedPoint("bayside-shopping-centre", "Bayside Shopping Centre", -38.1420, 145.1240, isCampus = false),
    ApprovedPoint("karingal-hub", "Karingal Hub", -38.1517, 145.1608, isCampus = false),
    // The 5 Monash campuses. Every trip has at least one of these at an end (RoutePolicy.kt).
    ApprovedPoint("monash-clayton", "Monash Clayton", -37.9105, 145.1362, isCampus = true),
    ApprovedPoint("monash-caulfield", "Monash Caulfield", -37.8774, 145.0449, isCampus = true),
    ApprovedPoint("monash-parkville", "Monash Parkville", -37.7834, 144.9587, isCampus = true),
    ApprovedPoint("monash-peninsula", "Monash Peninsula", -38.1525, 145.1335, isCampus = true),
    ApprovedPoint("monash-melbourne-city", "Monash Melbourne City – 750 Collins St", -37.8216, 144.9471, isCampus = true)
)

private val POINTS_BY_ID: Map<String, ApprovedPoint> = APPROVED_POINTS.associateBy { it.id }

fun approvedPointById(id: String): ApprovedPoint? = POINTS_BY_ID[id]

// The approved point this coordinate is (the nearest one within the snap radius), or null.
fun resolveApprovedPoint(lat: Double, lng: Double): ApprovedPoint? =
    APPROVED_POINTS
        .map { it to haversine(lat, lng, it.lat, it.lng) }
        .filter { (_, meters) -> meters <= SchedulingConfig.APPROVED_POINT_SNAP_RADIUS_METERS }
        .minByOrNull { (_, meters) -> meters }
        ?.first

// A request endpoint as the duplicate rules compare it: a canonical approved-point id.
//
// Production creation (RoutePolicy.kt) only ever accepts approved points, so every NEW row has real ids.
// legacyPointRef exists solely so rows written before approved points were enforced can still be read
// and compared: a coordinate that is not an approved point is keyed by its coordinates rounded to ~1 m
// and carries no pointId. Never use it to accept a new request.
data class PointRef(val key: String, val pointId: String?)

fun ApprovedPoint.toRef() = PointRef(id, id)

fun legacyPointRef(lat: Double, lng: Double): PointRef {
    resolveApprovedPoint(lat, lng)?.let { return it.toRef() }
    return PointRef("raw:" + String.format(Locale.ROOT, "%.5f,%.5f", lat, lng), null)
}
