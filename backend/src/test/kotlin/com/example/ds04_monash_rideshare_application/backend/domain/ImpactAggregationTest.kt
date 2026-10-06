package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.DashboardDto
import com.example.ds04_monash_rideshare_application.backend.RideDto
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.RideStatus
import com.example.ds04_monash_rideshare_application.backend.module
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

// Domain logic: which records count towards impact. Driven + COMPLETED rides only; every other
// ride or request status, and rides taken as a passenger, must not change the dashboard.
class ImpactAggregationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun withApp(test: suspend HttpClient.() -> Unit) {
        val url = tempDatabaseUrl()
        testApplication {
            application { module(url) }
            startApplication()
            client.test()
        }
    }

    private suspend fun HttpClient.dashboard(user: String): DashboardDto {
        val response = get("/users/me/dashboard") { header(HttpHeaders.Authorization, "Bearer $user") }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun HttpClient.history(user: String): List<RideDto> {
        val response = get("/users/me/trip-history") { header(HttpHeaders.Authorization, "Bearer $user") }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString(response.bodyAsText())
    }

    @Test fun `only completed driven rides count and several rides add up`() = withApp {
        addUser("agg-driver"); addVehicle("agg-driver", fuel = "PETROL")
        // Two completed rides: 10 km with 1 seat filled and 5 km with 2 seats filled.
        addRide("agg-done-1", "agg-driver", "agg-driver-car", RideStatus.COMPLETED, 10_000.0, 3, 2)
        addRide("agg-done-2", "agg-driver", "agg-driver-car", RideStatus.COMPLETED, 5_000.0, 3, 1)
        // Same shape but not completed - none of these may count.
        listOf(RideStatus.OPEN, RideStatus.CONFIRMED, RideStatus.CANCELLED, RideStatus.EXPIRED).forEachIndexed { i, status ->
            addRide("agg-other-$i", "agg-driver", "agg-driver-car", status, 20_000.0, 3, 0)
        }

        val result = dashboard("agg-driver")
        assertEquals(2, result.totalRidesCompleted)
        // (10 km x 1 seat + 5 km x 2 seats) x 0.170 kg/km
        assertEquals((10.0 * 1 + 5.0 * 2) * 0.170, result.totalCo2AvoidedKg, 1e-9)
        assertEquals((10.0 * 1 + 5.0 * 2) / 100 * 7.5, result.totalFuelSavedLitres, 1e-9)
        assertEquals(2, result.monthlyTripSummary.sumOf { it.tripsCompleted })
    }

    @Test fun `a completed ride nobody joined avoids nothing but is still a completed ride`() = withApp {
        addUser("empty-driver"); addVehicle("empty-driver")
        addRide("empty-done", "empty-driver", "empty-driver-car", RideStatus.COMPLETED, 8_000.0, 3, 3)
        val result = dashboard("empty-driver")
        assertEquals(1, result.totalRidesCompleted)
        assertEquals(0.0, result.totalCo2AvoidedKg, 1e-12)
    }

    @Test fun `riding as a passenger does not add to the riders own driving impact`() = withApp {
        addUser("agg-owner"); addVehicle("agg-owner")
        addUser("agg-rider")
        addRide("agg-ride", "agg-owner", "agg-owner-car", RideStatus.COMPLETED, 9_000.0, 3, 2)
        addRequest("agg-req", "agg-ride", "agg-rider", RideRequestStatus.COMPLETED)
        assertEquals(0, dashboard("agg-rider").totalRidesCompleted)
        assertEquals(1, dashboard("agg-owner").totalRidesCompleted)
    }

    @Test fun `trip history lists a passengers completed rides but not rejected pending or cancelled ones`() = withApp {
        addUser("hist-owner"); addVehicle("hist-owner")
        addUser("hist-rider")
        addRide("hist-done", "hist-owner", "hist-owner-car", RideStatus.COMPLETED, 9_000.0, 3, 2)
        addRequest("hist-q-done", "hist-done", "hist-rider", RideRequestStatus.COMPLETED)
        listOf(RideRequestStatus.REJECTED, RideRequestStatus.PENDING, RideRequestStatus.CANCELLED).forEach { status ->
            addRide("hist-$status", "hist-owner", "hist-owner-car", RideStatus.COMPLETED, 4_000.0, 3, 2)
            addRequest("hist-q-$status", "hist-$status", "hist-rider", status)
        }
        assertEquals(listOf("hist-done"), history("hist-rider").map { it.rideId })
    }
}
