package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.assertEquals

// Shared plumbing for the completion tests (actual completion time, completed-location history, completion-time revalidation).
//
// Every scenario is written in minutes from a base departure ten days away, so the real clock - which drives the lazy expiry
// of stale records - never touches it, while the SERVER clock (ServerClock) is pinned to the instant the test says the driver
// pressed Complete. Rows are written straight to the database where the point is the state (a ride with a passenger already
// accepted, a request already PENDING) rather than the route that makes it; everything under test goes through HTTP.

// Clayton (B) <-> Caulfield (D) take 30 minutes each way; Huntingdale (A) -> Clayton 10, back 5; everything else 20.
fun completionMatrix(): TravelTimeProvider = ScenarioTravelTimes(
    20, Triple(Place.B, Place.D, 30L), Triple(Place.D, Place.B, 30L), Triple(Place.A, Place.B, 10L), Triple(Place.B, Place.A, 5L)
)

// Runs the block with the server clock pinned to an instant, restoring the real clock afterwards.
suspend fun <T> atServerTime(millis: Long, block: suspend () -> T): T {
    val pin = ServerClock.pinnedAt(millis)
    try {
        return block()
    } finally {
        pin.close()
    }
}

// A planned ride between two approved points, exactly as POST /rides stores one (point ids + planned interval).
fun insertPlannedRide(
    id: String, driverId: String, from: String, to: String, departure: Long, plannedMinutes: Long,
    status: String = RideStatus.CONFIRMED, seatsTotal: Int = 3, seatsAvailable: Int = 2, completedAt: Long? = null,
    recurringGroupId: String? = null
) = transaction {
    val origin = approvedPointById(from)!!
    val destination = approvedPointById(to)!!
    Rides.insert {
        it[rideId] = id; it[Rides.driverId] = driverId
        it[originLat] = origin.lat; it[originLng] = origin.lng; it[destinationLat] = destination.lat; it[destinationLng] = destination.lng
        it[distanceMeters] = 4000.0; it[estimatedDurationSeconds] = 600
        it[originPointId] = from; it[destinationPointId] = to
        it[plannedDurationSeconds] = plannedMinutes * 60; it[plannedArrivalTime] = departure + plannedMinutes * MINUTE
        it[departureTime] = departure
        it[Rides.seatsTotal] = seatsTotal; it[Rides.seatsAvailable] = seatsAvailable
        it[Rides.status] = status; it[Rides.completedAt] = completedAt
        it[Rides.recurringGroupId] = recurringGroupId
        it[createdAt] = departure - DAY
    }
}

// A seat request on a posted ride, in any status.
fun insertSeatRequest(id: String, passengerId: String, rideId: String, status: String = RideRequestStatus.ACCEPTED, intent: String? = null) = transaction {
    RideRequests.insert {
        it[requestId] = id; it[RideRequests.rideId] = rideId; it[RideRequests.passengerId] = passengerId
        it[seatsRequested] = 1; it[RideRequests.status] = status; it[journeyIntentId] = intent
        it[createdAt] = System.currentTimeMillis() - 120_000; it[journeyKey] = "ride:$rideId"
        if (status != RideRequestStatus.PENDING) it[respondedAt] = System.currentTimeMillis() - 60_000
    }
}

// A standalone (open) request between two approved points, with the planned interval POST /requests stores.
fun insertPlannedAsk(
    id: String, passengerId: String, from: String, to: String, departure: Long, plannedMinutes: Long,
    status: String = RideRequestStatus.PENDING, respondedAt: Long? = null, conflictDetails: String? = null
) = transaction {
    val origin = approvedPointById(from)!!
    val destination = approvedPointById(to)!!
    RideRequests.insert {
        it[requestId] = id; it[rideId] = null; it[RideRequests.passengerId] = passengerId; it[seatsRequested] = 1
        it[RideRequests.status] = status
        it[originLat] = origin.lat; it[originLng] = origin.lng; it[destinationLat] = destination.lat; it[destinationLng] = destination.lng
        it[isAsap] = false; it[desiredDepartureTime] = departure; it[createdAt] = System.currentTimeMillis() - 120_000
        it[originPointId] = from; it[destinationPointId] = to
        it[plannedDurationSeconds] = plannedMinutes * 60; it[plannedArrivalTime] = departure + plannedMinutes * MINUTE
        it[RideRequests.respondedAt] = respondedAt; it[RideRequests.conflictDetails] = conflictDetails
    }
}

fun rideRow(id: String): ResultRow = transaction { Rides.selectAll().where { Rides.rideId eq id }.first() }
fun requestRow(id: String): ResultRow = transaction { RideRequests.selectAll().where { RideRequests.requestId eq id }.first() }
fun conflictOf(requestId: String): ScheduleConflictDetails? =
    requestRow(requestId)[RideRequests.conflictDetails]?.let { schedulingJson.decodeFromString<ScheduleConflictDetails>(it) }
fun warningRowCount(userId: String? = null): Long = transaction {
    if (userId == null) ScheduleWarnings.selectAll().count() else ScheduleWarnings.selectAll().where { ScheduleWarnings.userId eq userId }.count()
}
fun submissionResult(userId: String, key: String): String? = transaction {
    RequestSubmissions.selectAll().where { (RequestSubmissions.passengerId eq userId) and (RequestSubmissions.clientRequestId eq key) }
        .firstOrNull()?.get(RequestSubmissions.result)
}

suspend fun HttpClient.completeRide(driver: String, rideId: String, body: String = """{"status":"COMPLETED"}"""): HttpResponse =
    put("/rides/$rideId") {
        header(HttpHeaders.Authorization, "Bearer $driver")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

// Complete at an exact server time; asserts the call succeeded.
suspend fun HttpClient.completeRideAt(driver: String, rideId: String, serverTime: Long): RideDto {
    val response = atServerTime(serverTime) { completeRide(driver, rideId) }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return schedulingJson.decodeFromString(response.bodyAsText())
}

// POST /rides without asserting success (rideBetween asserts it), so a refusal can be examined.
suspend fun HttpClient.postRide(caller: String, departure: Long, from: String = Place.A, to: String = Place.B): HttpResponse {
    val f = coords(from); val t = coords(to)
    return post("/rides") {
        header(HttpHeaders.Authorization, "Bearer $caller")
        contentType(ContentType.Application.Json)
        setBody("""{"originLat":${f.first},"originLng":${f.second},"destinationLat":${t.first},"destinationLng":${t.second},
            "distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":$departure,"seatsTotal":3,
            "repeatDaysOfWeek":[],"repeatWeeks":1}""")
    }
}

suspend fun HttpClient.warningsOf(caller: String): List<ScheduleWarningDto> {
    val response = get("/schedule-warnings") { header(HttpHeaders.Authorization, "Bearer $caller") }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return schedulingJson.decodeFromString(response.bodyAsText())
}

suspend fun HttpClient.myRequests(caller: String): List<RideRequestDto> {
    val response = get("/requests/mine") { header(HttpHeaders.Authorization, "Bearer $caller") }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return schedulingJson.decodeFromString(response.bodyAsText())
}

suspend fun HttpClient.rideAs(caller: String, rideId: String): RideDto {
    val response = get("/rides/$rideId") { header(HttpHeaders.Authorization, "Bearer $caller") }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return schedulingJson.decodeFromString(response.bodyAsText())
}
