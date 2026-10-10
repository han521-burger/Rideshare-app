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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Declining from Request Detail or the driver banner. On a request for the driver's own ride it's
// a real rejection; on an open ask (no ride yet) it only hides the ask from that driver.
class DeclineRequestTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val passenger = "demo-passenger-002"
    private val tripTime = System.currentTimeMillis() + 5 * 24 * 60 * 60 * 1000L

    private fun withApp(test: suspend HttpClient.() -> Unit) {
        val file = Files.createTempFile("rideshare-decline-test-", ".db").toFile().apply { deleteOnExit() }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}", testTravelTimes()) }
            startApplication()
            client.test()
        }
    }

    private suspend fun HttpClient.respond(caller: String, requestId: String, status: String): HttpResponse =
        put("/requests/$requestId") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"$status"}""")
        }

    private suspend fun HttpClient.inbox(driver: String): List<RideRequestDto> =
        json.decodeFromString(get("/requests") { header(HttpHeaders.Authorization, "Bearer $driver") }.bodyAsText())

    @Test
    fun `declining an open ask hides it for that driver only and keeps it open`() = withApp {
        val created = post("/requests") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
            contentType(ContentType.Application.Json)
            setBody(
                """{"originLat":-37.9112,"originLng":145.1029,"destinationLat":-37.9105,"destinationLng":145.1362,
                    |"isAsap":false,"desiredDepartureTime":$tripTime,"seatsRequested":1}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val ask = createdRequests(created.bodyAsText()).single()
        assertTrue(inbox("demo-driver-001").any { it.requestId == ask.requestId })

        val declined = respond("demo-driver-001", ask.requestId, "REJECTED")
        assertEquals(HttpStatusCode.OK, declined.status, declined.bodyAsText())
        assertEquals(RideRequestStatus.PENDING, json.decodeFromString<RideRequestDto>(declined.bodyAsText()).status)

        assertFalse(inbox("demo-driver-001").any { it.requestId == ask.requestId })
        assertTrue(inbox("demo-driver-002").any { it.requestId == ask.requestId })
        // Declining twice is harmless, the passenger can't "decline" their own ask, and another
        // driver can still accept it.
        assertEquals(HttpStatusCode.OK, respond("demo-driver-001", ask.requestId, "REJECTED").status)
        assertEquals(HttpStatusCode.Forbidden, respond(passenger, ask.requestId, "REJECTED").status)
        assertEquals(HttpStatusCode.OK, respond("demo-driver-002", ask.requestId, "ACCEPTED").status)
    }

    @Test
    fun `declining a request on your own ride still rejects it`() = withApp {
        val created = post("/rides/ride-open-2/requests") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
            contentType(ContentType.Application.Json)
            setBody("""{"seatsRequested":1,"applyToRecurringGroup":false}""")
        }
        val request = createdRequests(created.bodyAsText()).single()

        assertEquals(HttpStatusCode.Forbidden, respond("demo-driver-001", request.requestId, "REJECTED").status)
        val rejected = respond("demo-driver-002", request.requestId, "REJECTED")
        assertEquals(RideRequestStatus.REJECTED, json.decodeFromString<RideRequestDto>(rejected.bodyAsText()).status)
    }
}
