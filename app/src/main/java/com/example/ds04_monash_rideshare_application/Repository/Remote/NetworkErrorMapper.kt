package com.example.ds04_monash_rideshare_application.Repository.Remote

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.ResponseBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// Turns network/backend failures into short, friendly messages - never a raw exception message
// or stack trace reaches the UI (NFR: "no raw exceptions shown to users").
// code is set for errors the app reacts to specifically (e.g. CONFLICT_OWN_PENDING_REQUEST).
// existingRequestId accompanies DUPLICATE_OPEN_REQUEST: the live request that already stands for the journey.
// scheduleConflict accompanies SCHEDULE_INFEASIBLE: the backend's structured explanation of why a journey does not fit.
class BackendException(
    message: String,
    val code: String? = null,
    val existingRequestId: String? = null,
    val scheduleConflict: ScheduleConflict? = null
) : Exception(message)

// Why a journey does not fit the person's schedule, exactly as the backend sends it (times are epoch milliseconds,
// durations are seconds). The app formats these for display (core/state/ScheduleConflictMessage.kt); it never
// parses backend prose. type is TOO_SOON_AFTER_PREVIOUS, TOO_LATE_FOR_NEXT or OVERLAPS_UNVERIFIED_LEGACY.
data class ScheduleConflict(
    val type: String,
    val conflictingId: String,
    val conflictingKind: String,
    val conflictingDepartureTime: Long,
    val conflictingArrivalTime: Long,
    val fromPointId: String? = null,
    val fromPointName: String? = null,
    val toPointId: String? = null,
    val toPointName: String? = null,
    val repositioningSeconds: Long? = null,
    val transferBufferSeconds: Long,
    val earliestFeasibleDeparture: Long? = null,
    val latestFeasibleDeparture: Long? = null,
    val proposedDepartureTime: Long,
    val proposedArrivalTime: Long
)

// Backend error codes (backend Lifecycle.kt).
const val CONFLICT_OWN_PENDING_REQUEST = "OWN_PENDING_REQUEST"
// The passenger already has a live request for this same journey (the backend refused to create another).
const val DUPLICATE_OPEN_REQUEST = "DUPLICATE_OPEN_REQUEST"
// The journey cannot be fitted into the passenger's schedule (backend ScheduleFeasibility.kt); see ScheduleConflict.
const val SCHEDULE_INFEASIBLE = "SCHEDULE_INFEASIBLE"
// A driver pressed Accept on a request that is no longer there to accept: someone else (or they, a moment ago) already
// holds it, or it ended some other way. Not a failure to retry - the request has simply gone.
const val REQUEST_ALREADY_ACCEPTED = "REQUEST_ALREADY_ACCEPTED"
const val REQUEST_UNAVAILABLE = "REQUEST_UNAVAILABLE"
// The driver pressed Complete on a ride that has not started yet (backend CompletionRevalidation.kt). The message the backend
// sends is already user-friendly and is shown as is; the app also holds the button back until departure.
const val RIDE_NOT_STARTED = "RIDE_NOT_STARTED"
// A single seat cannot be completed on its own: the ride owns completion (backend CompletionRevalidation.kt). No screen offers a
// request-level Complete, so this is only a guard; the backend's message ("This trip is completed when the driver completes the
// ride.") is already user-friendly and would be shown as is.
const val RIDE_NOT_COMPLETED = "RIDE_NOT_COMPLETED"

private data class BackendErrorBody(val error: String?, val code: String?, val existingRequestId: String?, val conflict: ScheduleConflict?)

object NetworkErrorMapper {
    fun fromThrowable(error: Throwable): BackendException = when (error) {
        is UnknownHostException, is SocketTimeoutException ->
            BackendException("Can't reach the server. Check your connection and try again.")
        is IOException ->
            BackendException("A network problem interrupted that request. Please try again.")
        else ->
            BackendException("Something went wrong. Please try again shortly.")
    }

    // Reads the backend's {"error": "..."} body (see backend Errors.kt) when present, so a
    // deliberate 4xx (e.g. "Not enough seats available") shows its real, still-friendly message
    // instead of a generic one.
    fun fromErrorBody(code: Int, body: ResponseBody?): BackendException {
        val parsed = body?.string()?.let {
            try {
                Gson().fromJson(it, BackendErrorBody::class.java)
            } catch (e: JsonSyntaxException) {
                null
            }
        }
        parsed?.error?.let { return BackendException(it, parsed.code, parsed.existingRequestId, parsed.conflict) }
        return when (code) {
            401 -> BackendException("Please sign in again.")
            403 -> BackendException("You don't have permission to do that.")
            404 -> BackendException("That couldn't be found.")
            in 500..599 -> BackendException("The server had a problem. Please try again shortly.")
            else -> BackendException("That request couldn't be completed.")
        }
    }
}
