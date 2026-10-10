package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Repository.Remote.BackendException
import com.example.ds04_monash_rideshare_application.Repository.Remote.DUPLICATE_OPEN_REQUEST
import com.example.ds04_monash_rideshare_application.Repository.Remote.SCHEDULE_INFEASIBLE
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSearchCriteria
import java.util.UUID

// Why sending a ride/seat request did not go through, in the terms the request screens need.
// A duplicate is not a generic failure: the passenger already has this request, so the screen says
// so and can take them to it (existingRequestId).
data class RequestSubmitError(
    val message: String,
    val isDuplicate: Boolean = false,
    val existingRequestId: String? = null,
    // The journey cannot be fitted into the passenger's schedule; message already explains why (in local time).
    val isScheduleConflict: Boolean = false
)

const val DUPLICATE_REQUEST_MESSAGE = "You've already sent this request. A driver can still accept it."

fun Exception.toRequestSubmitError(fallback: String): RequestSubmitError = when {
    this is BackendException && code == DUPLICATE_OPEN_REQUEST ->
        RequestSubmitError(DUPLICATE_REQUEST_MESSAGE, isDuplicate = true, existingRequestId = existingRequestId)
    // A schedule conflict is neither a duplicate nor a generic failure: explain it from the structured details.
    this is BackendException && code == SCHEDULE_INFEASIBLE && scheduleConflict != null ->
        RequestSubmitError(scheduleConflictMessage(scheduleConflict), isScheduleConflict = true)
    else -> RequestSubmitError(message ?: fallback)
}

// Hands out the clientRequestId for a submission. The SAME id is returned for as long as the
// submission is the same, so tapping again after a timeout or dropped connection retries the
// original - the backend then returns what it already saved instead of creating a second request.
// A changed submission gets a new id; reset() is called once a submission has gone through.
// The id of the passenger's INTENDED JOURNEY, sent with each seat request made from a ride search so the backend
// knows which requests are alternatives of one another (several rides offered for the same trip) rather than
// several trips. It is not the same thing as a SubmissionKeys id: that one identifies one tap (and is reused on a
// retry of it); this one identifies the journey and is shared by every request made from the same search.
//
// One search = one id. Searching for the same journey again (same pickup, drop-off and departure time) keeps the
// id; changing the journey - a different pickup, drop-off or time - starts a new one. The number of passengers is
// not part of the journey. The route and time alone never make requests alternatives on the backend; this id does.
internal class JourneyIntents(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private data class Journey(
        val originLat: Double, val originLng: Double, val destinationLat: Double, val destinationLng: Double, val departureTime: Long
    )

    private var journey: Journey? = null
    private var id: String? = null

    // A search was run: the id of the journey it is for.
    fun forSearch(criteria: RideSearchCriteria): String {
        val searched = Journey(criteria.originLat, criteria.originLng, criteria.destinationLat, criteria.destinationLng, criteria.departureTime)
        val existing = id
        if (existing != null && searched == journey) return existing
        return newId().also { journey = searched; id = it }
    }

    // The id of the search currently on screen, or null if no search has been made.
    fun current(): String? = id

    fun clear() {
        journey = null
        id = null
    }
}

internal class SubmissionKeys<T>(private val newKey: () -> String = { UUID.randomUUID().toString() }) {
    private var lastSubmission: T? = null
    private var lastKey: String? = null

    fun keyFor(submission: T): String {
        val existing = lastKey
        if (existing != null && submission == lastSubmission) return existing
        return newKey().also { lastSubmission = submission; lastKey = it }
    }

    fun reset() {
        lastSubmission = null
        lastKey = null
    }
}
