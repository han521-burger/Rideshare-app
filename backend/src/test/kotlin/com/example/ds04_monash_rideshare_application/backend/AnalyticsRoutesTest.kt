package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Rule-based analytics (docs/FEATURE_PROPOSALS.md item 5) computed from existing tables - these
// checks exercise the real endpoint end to end against seeded data, the same class of coverage
// that would have caught vehicleRoutes() never being mounted (see VehicleRegistrationTest.kt).
class AnalyticsRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun withApp(test: suspend io.ktor.client.HttpClient.() -> Unit) {
        val file = Files.createTempFile("rideshare-analytics-test-", ".db").toFile().apply { deleteOnExit() }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}") }
            startApplication()
            client.test()
        }
    }

    @Test
    fun `analytics reflects seeded completed requests for both passenger and driver roles`() = withApp {
        // fake-user-001 drove ride-completed-driver (req-completed-1, COMPLETED) and rode as a
        // passenger on ride-completed-passenger (req-completed-2, COMPLETED) - see Database.kt.
        val response = get("/users/me/analytics") { header(HttpHeaders.Authorization, "Bearer fake-user-001") }
        assertEquals(HttpStatusCode.OK, response.status)
        val analytics = json.decodeFromString<AnalyticsDto>(response.bodyAsText())

        assertTrue(analytics.driverFunnel.completed >= 1, "expected at least one completed request on a ride fake-user-001 drove")
        assertTrue(analytics.passengerFunnel.completed >= 1, "expected at least one completed request fake-user-001 sent as a passenger")
        assertEquals(7, analytics.tripsByDayOfWeek.size)
        assertTrue(analytics.tripsByDayOfWeek.sum() >= 2, "expected both seeded completed trips to land somewhere in the week")
        assertTrue(analytics.averageSeatFillRatePercent != null && analytics.averageSeatFillRatePercent!! in 0.0..100.0)
    }

    @Test
    fun `a user with no activity gets an empty but well-formed response`() = withApp {
        val response = get("/users/me/analytics") { header(HttpHeaders.Authorization, "Bearer demo-passenger-002") }
        assertEquals(HttpStatusCode.OK, response.status)
        val analytics = json.decodeFromString<AnalyticsDto>(response.bodyAsText())
        assertEquals(0, analytics.driverFunnel.pending + analytics.driverFunnel.accepted + analytics.driverFunnel.rejected +
            analytics.driverFunnel.cancelled + analytics.driverFunnel.completed)
        assertEquals(null, analytics.averageSeatFillRatePercent)
    }

    @Test
    fun `unauthenticated analytics request is rejected`() = withApp {
        val response = get("/users/me/analytics")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
