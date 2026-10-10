package com.example.ds04_monash_rideshare_application.backend

// One place for every number the scheduling rules depend on, so a policy change is a one-line edit
// (and tests can name the value they mean instead of repeating a literal).
object SchedulingConfig {
    // How close (either side) two departures must be to count as "the same intended journey".
    // This is the DUPLICATE rule only - it is deliberately unrelated to schedule feasibility.
    const val DUPLICATE_TOLERANCE_MILLIS = 60_000L

    // Added after every journey, even when the next pickup is where the last drop-off was: it
    // covers getting out, finding the next car and ordinary timing slack. Used by the passenger
    // timeline (travel-time stages) - defined here so the value has one home.
    const val TRANSFER_BUFFER_MINUTES = 10L
    const val TRANSFER_BUFFER_MILLIS = TRANSFER_BUFFER_MINUTES * 60_000L

    // How long an ASAP ask waits for a driver before it expires.
    const val ASAP_REQUEST_LIFETIME_MILLIS = 30L * 60_000L

    // A coordinate counts as an approved pickup/drop-off point when it is this close to one. The app only
    // ever sends an approved point's exact coordinates, so this just absorbs rounding. It must be less than
    // HALF the distance between the two closest approved points (Frankston Station and Bayside Shopping
    // Centre, 181 m apart) so no coordinate can be within range of two points; ApprovedPointsTest enforces
    // that whenever a point is added.
    const val APPROVED_POINT_SNAP_RADIUS_METERS = 75.0

    // The time zone the service operates in (Monash, Victoria). Anything that turns "every Monday at 8:30" into
    // instants uses THIS, never the server JVM's zone, so a request means the same thing wherever the backend runs.
    val SERVICE_ZONE: java.time.ZoneId = java.time.ZoneId.of("Australia/Melbourne")

    // SQLite: how long a writer waits for the write lock before giving up (see Database.kt).
    const val DB_BUSY_TIMEOUT_MILLIS = 10_000
}
