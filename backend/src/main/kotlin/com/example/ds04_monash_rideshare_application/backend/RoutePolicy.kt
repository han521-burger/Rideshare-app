package com.example.ds04_monash_rideshare_application.backend

// The product's rule for where a ride or request may start and end, enforced on every NEW ride and
// every NEW request. (Rows already in the database are never re-checked.)
//
//   1. Both ends must be an APPROVED point - one of the 25 hubs or 5 campuses in ApprovedPoints.kt,
//      matched by coordinates within SchedulingConfig.APPROVED_POINT_SNAP_RADIUS_METERS (75 m). The app
//      only ever sends an approved point's exact coordinates, so the tolerance only absorbs rounding.
//      An unapproved coordinate is rejected - it is never converted into some other identity.
//   2. At least one end must be a Monash CAMPUS (one of the 5 approved campus points). Campus to campus
//      is allowed; hub to hub is not.
//   3. The two ends must be different points.
//
// This is the same rule the app's pickers already apply (CampusRoutePicker offers only approved points,
// always with a campus at one end, never the same point twice); the backend now enforces it too instead
// of only checking "within 300 m of a campus". Two consequences worth knowing: a hub is no longer
// accepted merely for being near a campus (Caulfield Station is ~210 m from Monash Caulfield but is a
// hub, not a campus), and the other end can no longer be an arbitrary coordinate.
//
// Machine-readable codes (ErrorResponse.code) so the app can react without parsing text.
const val CODE_UNAPPROVED_LOCATION = "UNAPPROVED_LOCATION"
const val CODE_CAMPUS_ENDPOINT_REQUIRED = "CAMPUS_ENDPOINT_REQUIRED"
const val CODE_SAME_ENDPOINT = "SAME_ENDPOINT"

class ApprovedRoute(val origin: ApprovedPoint, val destination: ApprovedPoint)

fun requireApprovedRoute(originLat: Double, originLng: Double, destinationLat: Double, destinationLng: Double): ApprovedRoute {
    val origin = resolveApprovedPoint(originLat, originLng)
        ?: throw ValidationException("The pickup isn't an approved pickup/drop-off point", CODE_UNAPPROVED_LOCATION)
    val destination = resolveApprovedPoint(destinationLat, destinationLng)
        ?: throw ValidationException("The drop-off isn't an approved pickup/drop-off point", CODE_UNAPPROVED_LOCATION)
    if (origin.id == destination.id) {
        throw ValidationException("The pickup and drop-off must be different places", CODE_SAME_ENDPOINT)
    }
    if (!origin.isCampus && !destination.isCampus) {
        throw ValidationException("One end of the trip must be a Monash campus", CODE_CAMPUS_ENDPOINT_REQUIRED)
    }
    return ApprovedRoute(origin, destination)
}
