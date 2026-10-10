package com.example.ds04_monash_rideshare_application.backend

// The server's clock for the one decision that must be able to be tested at an exact instant: when a driver completes a
// ride (PUT /rides/{id} status=COMPLETED stamps Rides.completedAt and refuses a ride that has not started). Production
// always reads the system clock; a test may pin it with pinnedAt() so "completed at 2:50" is exact. A client can never
// influence this - there is no request field that reaches it.
object ServerClock {
    @Volatile private var pinned: Long? = null

    fun now(): Long = pinned ?: System.currentTimeMillis()

    // Tests only. Always restore with the returned handle (or use withPinnedTime) so one test cannot leak into the next.
    internal fun pinnedAt(millis: Long): AutoCloseable {
        pinned = millis
        return AutoCloseable { pinned = null }
    }
}
