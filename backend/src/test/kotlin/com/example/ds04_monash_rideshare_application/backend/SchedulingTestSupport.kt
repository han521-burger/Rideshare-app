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
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files

// Shared plumbing for the passenger-duplicate / idempotency / concurrency tests: a throwaway
// database per test, the approved points the tests travel between, and small request helpers.
// Passengers and drivers are the seeded demo accounts (the test runner trusts "Bearer <userId>").
object Pts {
    val huntingdale = -37.9112 to 145.1029       // approved point: huntingdale-station
    val glenWaverley = -37.8795 to 145.1621      // approved point: glen-waverley-station
    val clayton = -37.9105 to 145.1362           // campus: monash-clayton
}

const val PASSENGER = "demo-passenger-002"
const val OTHER_PASSENGER = "fake-user-001"
const val DRIVER_A = "demo-driver-002"
const val DRIVER_B = "demo-driver-001"
const val MINUTE = 60_000L
const val DAY = 24 * 60 * MINUTE

val schedulingJson = Json { ignoreUnknownKeys = true }

// A departure comfortably in the future, on a whole minute.
fun futureDeparture(daysAhead: Int = 5): Long = (System.currentTimeMillis() + daysAhead * DAY) / MINUTE * MINUTE

// travelTimes defaults to the synthetic test provider; pass another (e.g. one with holes) to test how requests behave then.
fun withScheduling(travelTimes: TravelTimeProvider = testTravelTimes(), test: suspend HttpClient.() -> Unit) {
    val file = Files.createTempFile("rideshare-scheduling-", ".db").toFile().apply { deleteOnExit() }
    testApplication {
        application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}", travelTimes) }
        startApplication()
        client.test()
    }
}

fun openRequestBody(
    from: Pair<Double, Double> = Pts.huntingdale,
    to: Pair<Double, Double> = Pts.clayton,
    at: Long? = null,
    asap: Boolean = false,
    clientRequestId: String? = null,
    repeatDays: List<Int> = emptyList(),
    repeatWeeks: Int? = null,
    note: String? = null
): String = buildString {
    append("""{"originLat":${from.first},"originLng":${from.second},"destinationLat":${to.first},"destinationLng":${to.second},""")
    append(""""isAsap":$asap,"desiredDepartureTime":${at ?: "null"},"seatsRequested":1""")
    if (clientRequestId != null) append(""","clientRequestId":"$clientRequestId"""")
    if (repeatDays.isNotEmpty()) append(""","repeatDaysOfWeek":$repeatDays""")
    if (repeatWeeks != null) append(""","repeatWeeks":$repeatWeeks""")
    if (note != null) append(""","note":"$note"""")
    append("}")
}

suspend fun HttpClient.postOpen(body: String, caller: String = PASSENGER): HttpResponse =
    post("/requests") {
        header(HttpHeaders.Authorization, "Bearer $caller")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

// journeyIntentId: the explicit "these are alternatives of one intended journey" id (omitted = a journey of its own).
suspend fun HttpClient.postSeat(
    rideId: String, clientRequestId: String? = null, caller: String = PASSENGER, journeyIntentId: String? = null
): HttpResponse =
    post("/rides/$rideId/requests") {
        header(HttpHeaders.Authorization, "Bearer $caller")
        contentType(ContentType.Application.Json)
        val key = clientRequestId?.let { ""","clientRequestId":"$it"""" } ?: ""
        val intent = journeyIntentId?.let { ""","journeyIntentId":"$it"""" } ?: ""
        setBody("""{"seatsRequested":1,"applyToRecurringGroup":false$key$intent}""")
    }

suspend fun HttpClient.offerRide(caller: String, at: Long, from: Pair<Double, Double> = Pts.huntingdale, to: Pair<Double, Double> = Pts.clayton): RideDto {
    val response = post("/rides") {
        header(HttpHeaders.Authorization, "Bearer $caller")
        contentType(ContentType.Application.Json)
        setBody("""{"originLat":${from.first},"originLng":${from.second},"destinationLat":${to.first},"destinationLng":${to.second},
            "distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":$at,"seatsTotal":3,
            "repeatDaysOfWeek":[],"repeatWeeks":1}""")
    }
    check(response.status.value == 200) { response.bodyAsText() }
    return schedulingJson.decodeFromString(response.bodyAsText())
}

suspend fun HttpClient.respond(caller: String, requestId: String, status: String, targetRideId: String? = null): HttpResponse =
    put("/requests/$requestId") {
        header(HttpHeaders.Authorization, "Bearer $caller")
        contentType(ContentType.Application.Json)
        val target = targetRideId?.let { ""","targetRideId":"$it"""" } ?: ""
        setBody("""{"status":"$status"$target}""")
    }

// The requests a creation call made (POST /requests, POST /rides/{id}/requests), from its CreateRequestsResponse.
fun createdRequests(text: String): List<RideRequestDto> = schedulingJson.decodeFromString<CreateRequestsResponse>(text).requests

// The whole answer, including the rejected occurrences.
fun creationResult(text: String): CreateRequestsResponse = schedulingJson.decodeFromString(text)

// A single request out of a 200 answer: the first request a creation call made, or - for the PUT answers that are still a
// bare request (accept, reject, cancel, undo) - that request.
fun HttpResponse.requireOk(text: String): RideRequestDto {
    check(status.value == 200) { "expected 200 but was $status: $text" }
    return if (text.contains("\"occurrences\"")) createdRequests(text).first() else schedulingJson.decodeFromString(text)
}

suspend fun HttpResponse.asRequest(): RideRequestDto = requireOk(bodyAsText())

suspend fun HttpResponse.asError(): ErrorResponse = schedulingJson.decodeFromString(bodyAsText())

// Everything GET /requests returns to a driver (their own rides' requests of any status + open asks).
suspend fun HttpClient.driverFeedAll(driver: String = DRIVER_A): List<RideRequestDto> {
    val response = get("/requests") { header(HttpHeaders.Authorization, "Bearer $driver") }
    return schedulingJson.decodeFromString(response.bodyAsText())
}

// What a driver can actually act on from open asks: PENDING, no ride attached yet.
suspend fun HttpClient.driverFeedOpenAsks(driver: String = DRIVER_A, passenger: String = PASSENGER): List<RideRequestDto> =
    driverFeedAll(driver).filter { it.rideId == null && it.status == RideRequestStatus.PENDING && it.passengerId == passenger }

// Requests the seeded demo data already gives these accounts - not created by the test under way.
private val SEEDED_REQUEST_IDS = listOf("req-pending-1", "req-completed-1", "req-accepted-1", "req-completed-2")

// Rows this test created for the passenger (the seeded demo requests are excluded).
fun requestRows(passengerId: String = PASSENGER): Long = transaction {
    RideRequests.selectAll().where { (RideRequests.passengerId eq passengerId) and (RideRequests.requestId notInList SEEDED_REQUEST_IDS) }.count()
}

fun liveOpenRows(passengerId: String = PASSENGER): Long = transaction {
    RideRequests.selectAll().where {
        (RideRequests.passengerId eq passengerId) and RideRequests.originLat.isNotNull() and
            (RideRequests.status inList LIVE_REQUEST_STATUSES)
    }.count()
}

fun requestById(id: String): RideRequestDto? = transaction {
    RideRequests.selectAll().where { RideRequests.requestId eq id }.firstOrNull()?.toRideRequestDto()
}

// (seatsTotal, seatsAvailable) of a ride.
fun rideSeats(rideId: String): Pair<Int, Int> = transaction {
    Rides.selectAll().where { Rides.rideId eq rideId }.first().let { it[Rides.seatsTotal] to it[Rides.seatsAvailable] }
}

fun setStatus(requestId: String, status: String) {
    transaction { RideRequests.update({ RideRequests.requestId eq requestId }) { it[RideRequests.status] = status } }
}

// A fake directional travel-time table for tests (the real matrix arrives in a later stage).
class FakeTravelTimeProvider(private val seconds: Map<Pair<String, String>, Long>) : TravelTimeProvider {
    var lookups = 0
        private set
    override fun travelSeconds(fromPointId: String, toPointId: String): Long? {
        lookups++
        return seconds[fromPointId to toPointId]
    }
}

// A ride cannot be completed before it has started (RIDE_NOT_STARTED), judged by the SERVER clock (ServerClock).
// These tests build rides in the future (so the lazy expiry leaves their pending requests alone) and still need to
// complete them, so the server clock is pinned to just after the ride's departure while the block runs.
// Close the result (use { }) to put the real clock back.
fun serverClockAfterDeparture(rideId: String, minutesAfter: Long = 5): AutoCloseable {
    val departure = transaction { Rides.selectAll().where { Rides.rideId eq rideId }.first()[Rides.departureTime] }
    return ServerClock.pinnedAt(departure + minutesAfter * MINUTE)
}
