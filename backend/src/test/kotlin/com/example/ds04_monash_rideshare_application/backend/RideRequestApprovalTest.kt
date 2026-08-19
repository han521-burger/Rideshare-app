package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RideRequestApprovalTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `seat request stays pending until owning driver accepts it`() {
        val databaseFile = Files.createTempFile("rideshare-request-test-", ".db").toFile().apply {
            deleteOnExit()
        }

        testApplication {
            application { module("jdbc:sqlite:${databaseFile.absolutePath.replace('\\', '/')}") }

            val requestResponse = client.post("/rides/ride-open-2/requests") {
                header(HttpHeaders.Authorization, "Bearer fake-user-001")
                contentType(ContentType.Application.Json)
                setBody("""{"seatsRequested":1,"note":"Near the library, please","applyToRecurringGroup":false}""")
            }
            assertEquals(HttpStatusCode.OK, requestResponse.status)
            val created = json.decodeFromString<List<RideRequestDto>>(requestResponse.bodyAsText()).single()
            assertEquals(RideRequestStatus.PENDING, created.status)
            assertEquals(null, created.respondedAt)

            val rideBeforeApproval = client.get("/rides/ride-open-2") {
                header(HttpHeaders.Authorization, "Bearer fake-user-001")
            }
            val before = json.decodeFromString<RideDto>(rideBeforeApproval.bodyAsText())
            assertEquals(RideStatus.OPEN, before.status)
            assertEquals(2, before.seatsAvailable)

            val driverInbox = client.get("/requests") {
                header(HttpHeaders.Authorization, "Bearer demo-driver-002")
            }
            val incoming = json.decodeFromString<List<RideRequestDto>>(driverInbox.bodyAsText())
                .firstOrNull { it.requestId == created.requestId }
            assertNotNull(incoming)
            assertEquals("Test Student", incoming.passenger?.displayName)
            assertNotNull(incoming.ride)

            val acceptResponse = client.put("/requests/${created.requestId}") {
                header(HttpHeaders.Authorization, "Bearer demo-driver-002")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"ACCEPTED"}""")
            }
            assertEquals(HttpStatusCode.OK, acceptResponse.status)
            val accepted = json.decodeFromString<RideRequestDto>(acceptResponse.bodyAsText())
            assertEquals(RideRequestStatus.ACCEPTED, accepted.status)
            assertNotNull(accepted.respondedAt)

            val rideAfterApproval = client.get("/rides/ride-open-2") {
                header(HttpHeaders.Authorization, "Bearer fake-user-001")
            }
            val after = json.decodeFromString<RideDto>(rideAfterApproval.bodyAsText())
            assertEquals(RideStatus.CONFIRMED, after.status)
            assertEquals(1, after.seatsAvailable)
            assertTrue(after.seatsAvailable < before.seatsAvailable)
        }
    }

    @Test
    fun `only owning driver can respond and rejection keeps capacity unchanged`() {
        val databaseFile = Files.createTempFile("rideshare-rejection-test-", ".db").toFile().apply {
            deleteOnExit()
        }

        testApplication {
            application { module("jdbc:sqlite:${databaseFile.absolutePath.replace('\\', '/')}") }

            val requestResponse = client.post("/rides/ride-open-1/requests") {
                header(HttpHeaders.Authorization, "Bearer fake-user-001")
                contentType(ContentType.Application.Json)
                setBody("""{"seatsRequested":1,"applyToRecurringGroup":false}""")
            }
            val created = json.decodeFromString<List<RideRequestDto>>(requestResponse.bodyAsText()).single()

            val wrongDriverResponse = client.put("/requests/${created.requestId}") {
                header(HttpHeaders.Authorization, "Bearer demo-driver-002")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"ACCEPTED"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, wrongDriverResponse.status)

            val rejectResponse = client.put("/requests/${created.requestId}") {
                header(HttpHeaders.Authorization, "Bearer demo-driver-001")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"REJECTED"}""")
            }
            assertEquals(HttpStatusCode.OK, rejectResponse.status)
            val rejected = json.decodeFromString<RideRequestDto>(rejectResponse.bodyAsText())
            assertEquals(RideRequestStatus.REJECTED, rejected.status)

            val rideResponse = client.get("/rides/ride-open-1") {
                header(HttpHeaders.Authorization, "Bearer fake-user-001")
            }
            val ride = json.decodeFromString<RideDto>(rideResponse.bodyAsText())
            assertEquals(RideStatus.OPEN, ride.status)
            assertEquals(3, ride.seatsAvailable)
        }
    }
}
