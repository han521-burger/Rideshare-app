package com.example.ds04_monash_rideshare_application.backend

data class CampusPoint(val name: String, val lat: Double, val lng: Double)

// The 5 Monash campuses, as a view of APPROVED_POINTS (ApprovedPoints.kt), where their names and
// coordinates are written down once. Whether a trip has a campus at one end is decided on approved-point
// identity in RoutePolicy.kt - there is no longer a "within N metres of a campus" test.
val MONASH_CAMPUSES: List<CampusPoint> = APPROVED_POINTS.filter { it.isCampus }.map { CampusPoint(it.name, it.lat, it.lng) }
