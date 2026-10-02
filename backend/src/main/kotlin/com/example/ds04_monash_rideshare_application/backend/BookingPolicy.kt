package com.example.ds04_monash_rideshare_application.backend

// Scheduled rides and requests must leave at least 30 minutes from now, so a driver/passenger
// has time to see and respond (the app enforces the same rule in its pickers). ASAP requests
// are exempt - "leave now" is the whole point. The server allows a small grace period so a
// phone whose clock runs a little ahead of this machine isn't rejected right at the edge.
const val MIN_BOOKING_LEAD_MINUTES = 30L
private const val CLOCK_SKEW_GRACE_MILLIS = 2L * 60_000L

internal fun requireBookableDeparture(departureTime: Long) {
    val earliest = System.currentTimeMillis() + MIN_BOOKING_LEAD_MINUTES * 60_000L - CLOCK_SKEW_GRACE_MILLIS
    if (departureTime < earliest) {
        throw ValidationException("Departure must be at least $MIN_BOOKING_LEAD_MINUTES minutes from now")
    }
}
