package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchingRouteTest {
    @Test
    fun `HTTP fallback preserves capacity and eligible ride statuses`() {
        val databaseFile = Files.createTempFile("rideshare-matching-test-", ".db").toFile().apply {
            deleteOnExit()
        }
        val departure = 1_800_000_000_000L
        val statuses = listOf(RideStatus.OPEN, RideStatus.CONFIRMED, RideStatus.CANCELLED, RideStatus.COMPLETED)
        testApplication {
            application { module("jdbc:sqlite:${databaseFile.absolutePath.replace('\\', '/')}") }
            startApplication()
            transaction {
                for (rideStatus in statuses) {
                    for (capacity in 0..3) {
                        Rides.insert {
                            it[rideId] = "match-$rideStatus-$capacity"
                            it[driverId] = "demo-driver-002"
                            // Same direction and time, but over 11 km from search pickup:
                            // all these fixtures must go through fallback, not normal rank.
                            it[originLat] = 0.1
                            it[originLng] = 0.0
                            it[destinationLat] = 0.1
                            it[destinationLng] = 1.0
                            it[departureTime] = departure
                            it[seatsTotal] = 3
                            it[seatsAvailable] = capacity
                            it[status] = rideStatus
                            it[createdAt] = departure - 60_000
                        }
                    }
                }
            }

            for (needed in listOf(1, 2, 0, -1)) {
                val response = client.get("/rides/match") {
                    header(HttpHeaders.Authorization, "Bearer fake-user-001")
                    parameter("originLat", 0.0)
                    parameter("originLng", 0.0)
                    parameter("destinationLat", 0.0)
                    parameter("destinationLng", 1.0)
                    parameter("departureTime", departure)
                    parameter("seatsNeeded", needed)
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val result = Json.decodeFromString<MatchSearchResponse>(response.bodyAsText())
                assertTrue(result.usedFallback)
                val expectedIds = if (needed > 0) {
                    listOf(RideStatus.OPEN, RideStatus.CONFIRMED).flatMap { status ->
                        (needed..3).map { capacity -> "match-$status-$capacity" }
                    }.toSet()
                } else emptySet()
                assertEquals(expectedIds, result.rides.map { it.rideId }.toSet())
                assertTrue(result.rides.all { it.seatsAvailable >= needed })
                assertTrue(result.rides.all { it.matchScore == MatchScoreDto(0.0, 0.0, 0.0, 0.0, 0.0) })
            }
        }
    }
}
