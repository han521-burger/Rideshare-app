package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromString
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// TEST-ONLY travel times with the exact values a scenario needs, e.g. "B -> A takes 30 minutes". Everything not
// listed takes defaultMinutes. Directional: a pair is only what you list for that direction.
class ScenarioTravelTimes(private val defaultMinutes: Long = 20, vararg pairs: Triple<String, String, Long>) : TravelTimeProvider {
    private val table = pairs.associate { (from, to, minutes) -> (from to to) to minutes * 60 }
    override fun travelSeconds(fromPointId: String, toPointId: String): Long? =
        if (fromPointId == toPointId) 0L else table[fromPointId to toPointId] ?: (defaultMinutes * 60)
}

// The places the scenarios use. A and C are hubs, B and D are Monash campuses (every trip needs a campus end).
object Place {
    const val A = "huntingdale-station"
    const val B = "monash-clayton"
    const val C = "glen-waverley-station"
    const val D = "monash-caulfield"
    const val E = "oakleigh-station"
}

val CAULFIELD_CAMPUS = -37.8774 to 145.0449
val OAKLEIGH_STATION = -37.9002 to 145.0886

fun coords(pointId: String): Pair<Double, Double> = approvedPointById(pointId)!!.let { it.lat to it.lng }

// The conflict a refused create call explained, after checking it is a SCHEDULE conflict (not a duplicate or
// anything else).
suspend fun HttpResponse.scheduleConflict(type: String? = null): ScheduleConflictDetails {
    assertEquals(HttpStatusCode.Conflict, status, bodyAsText())
    val error = asError()
    assertEquals(CODE_SCHEDULE_INFEASIBLE, error.code, "expected a schedule conflict, got: $error")
    val conflict = assertNotNull(error.conflict)
    if (type != null) assertEquals(type, conflict.type)
    return conflict
}

// Post an open request between two named places at a minute offset from the scenario's base time.
suspend fun HttpClient.openBetween(from: String, to: String, at: Long, caller: String = PASSENGER, asap: Boolean = false) =
    postOpen(openRequestBody(from = coords(from), to = coords(to), at = if (asap) null else at, asap = asap), caller)

// Post a one-off ride between two named places (the MapKit estimate is deliberately unrelated to the planned one).
suspend fun HttpClient.rideBetween(caller: String, departure: Long, from: String = Place.A, to: String = Place.B): RideDto {
    val f = coords(from); val t = coords(to)
    val response = post("/rides") {
        header(HttpHeaders.Authorization, "Bearer $caller")
        contentType(ContentType.Application.Json)
        setBody("""{"originLat":${f.first},"originLng":${f.second},"destinationLat":${t.first},"destinationLng":${t.second},
            "distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":$departure,"seatsTotal":3,
            "repeatDaysOfWeek":[],"repeatWeeks":1}""")
    }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return schedulingJson.decodeFromString(response.bodyAsText())
}
