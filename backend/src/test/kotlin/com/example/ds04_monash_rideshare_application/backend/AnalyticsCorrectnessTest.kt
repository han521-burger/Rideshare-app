package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.*

class AnalyticsCorrectnessTest {
    private val driver = "demo-driver-002"
    private val passenger = "demo-passenger-002"
    private val json = Json { ignoreUnknownKeys = true }
    private val future = System.currentTimeMillis() + 10 * 86_400_000L
    private fun withApp(test: suspend HttpClient.() -> Unit) {
        val file = Files.createTempFile("analytics-correctness-", ".db").toFile().apply { deleteOnExit() }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}", testTravelTimes()) }
            startApplication()
            fixture()
            client.test()
        }
    }
    private fun fixture() = transaction {
        Rides.insert {
            it[rideId] = "analytics-ride"; it[driverId] = driver; it[vehicleId] = "demo-vehicle-002"
            it[originLat] = -37.9105; it[originLng] = 145.1362
            it[destinationLat] = -37.95; it[destinationLng] = 145.15
            it[departureTime] = future; it[estimatedDurationSeconds] = 600
            it[seatsTotal] = 3; it[seatsAvailable] = 3; it[createdAt] = System.currentTimeMillis()
        }
        RideRequests.insert {
            it[requestId] = "analytics-request"; it[rideId] = "analytics-ride"; it[passengerId] = passenger
            it[seatsRequested] = 1; it[createdAt] = System.currentTimeMillis() - 120_000
        }
        RideRequests.insert {
            it[requestId] = "unanswered-request"; it[rideId] = "analytics-ride"; it[passengerId] = "fake-user-001"
            it[seatsRequested] = 1; it[createdAt] = System.currentTimeMillis() - 300_000
        }
    }
    private fun requestRow(id: String = "analytics-request") = transaction {
        RideRequests.selectAll().where { RideRequests.requestId eq id }.single()
    }
    private suspend fun HttpClient.change(path: String, user: String, status: String) {
        val result = put(path) {
            header(HttpHeaders.Authorization, "Bearer $user")
            contentType(ContentType.Application.Json); setBody("""{"status":"$status"}""")
        }
        assertEquals(HttpStatusCode.OK, result.status, result.bodyAsText())
    }
    private suspend fun HttpClient.accept(): Long {
        assertNull(requestRow()[RideRequests.driverDecisionAt])
        change("/requests/analytics-request", driver, "ACCEPTED")
        val row = requestRow()
        assertNotNull(row[RideRequests.driverDecisionAt])
        assertEquals(row[RideRequests.respondedAt], row[RideRequests.driverDecisionAt])
        return row[RideRequests.driverDecisionAt]!!
    }
    private suspend fun HttpClient.analytics(user: String = driver): AnalyticsDto {
        val result = get("/users/me/analytics") { header(HttpHeaders.Authorization, "Bearer $user") }
        assertEquals(HttpStatusCode.OK, result.status)
        return json.decodeFromString(result.bodyAsText())
    }
    @Test fun `accept records decision and analytics measures that decision only`() = withApp {
        val decision = accept()
        val expected = (decision - requestRow()[RideRequests.createdAt]) / 60_000.0
        assertEquals(expected, analytics().averageDriverResponseMinutes)
        serverClockAfterDeparture("analytics-ride").use { change("/rides/analytics-ride", driver, "COMPLETED") }
        assertEquals(decision, requestRow()[RideRequests.driverDecisionAt])
        assertNull(requestRow("unanswered-request")[RideRequests.driverDecisionAt])
        assertEquals(expected, analytics().averageDriverResponseMinutes)
    }
    @Test fun `reject records first explicit decision`() = withApp {
        change("/requests/analytics-request", driver, "REJECTED")
        val row = requestRow()
        assertNotNull(row[RideRequests.driverDecisionAt])
        assertEquals(row[RideRequests.respondedAt], row[RideRequests.driverDecisionAt])
    }
    @Test fun `passenger cancellation neither creates nor overwrites decision`() = withApp {
        change("/requests/unanswered-request", "fake-user-001", "CANCELLED")
        assertNull(requestRow("unanswered-request")[RideRequests.driverDecisionAt])
        val decision = accept()
        change("/requests/analytics-request", passenger, "CANCELLED")
        assertEquals(decision, requestRow()[RideRequests.driverDecisionAt])
    }
    @Test fun `whole ride cancellation preserves decision and leaves unanswered null`() = withApp {
        val decision = accept()
        change("/rides/analytics-ride", driver, "CANCELLED")
        assertEquals(decision, requestRow()[RideRequests.driverDecisionAt])
        assertNull(requestRow("unanswered-request")[RideRequests.driverDecisionAt])
    }
    // A single seat cannot be completed while its ride is live; the refusal changes nothing, decision included. (The
    // ride-level completion that does complete seats is covered by `accept records decision...` above.)
    @Test fun `refused individual completion leaves the driver decision and status alone`() = withApp {
        val decision = accept()
        val result = put("/requests/analytics-request") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
            contentType(ContentType.Application.Json); setBody("""{"status":"COMPLETED"}""")
        }
        assertEquals(HttpStatusCode.Conflict, result.status)
        assertEquals(decision, requestRow()[RideRequests.driverDecisionAt])
        assertEquals("ACCEPTED", requestRow()[RideRequests.status])
    }
    @Test fun `undo and reaccept preserve first decision while respondedAt keeps undo semantics`() = withApp {
        accept()
        // Distinct sentinel proves reaccept does not silently replace the original decision.
        val original = System.currentTimeMillis() - 60_000
        transaction { RideRequests.update({ RideRequests.requestId eq "analytics-request" }) { it[driverDecisionAt] = original } }
        val undo = post("/requests/analytics-request/undo-accept") { header(HttpHeaders.Authorization, "Bearer $driver") }
        assertEquals(HttpStatusCode.OK, undo.status, undo.bodyAsText())
        assertNull(requestRow()[RideRequests.respondedAt])
        assertEquals(original, requestRow()[RideRequests.driverDecisionAt])
        change("/requests/analytics-request", driver, "ACCEPTED")
        assertEquals(original, requestRow()[RideRequests.driverDecisionAt])
        assertTrue(requestRow()[RideRequests.respondedAt]!! > original)
    }
    @Test fun `automatic expiry has no decision sample and is counted in both funnels`() = withApp {
        transaction { Rides.update({ Rides.rideId eq "analytics-ride" }) { it[departureTime] = System.currentTimeMillis() - 60_000 } }
        val driverStats = analytics()
        assertEquals("EXPIRED", requestRow()[RideRequests.status])
        assertNull(requestRow()[RideRequests.driverDecisionAt])
        assertNull(driverStats.averageDriverResponseMinutes)
        assertEquals(2, driverStats.driverFunnel.expired)
        assertEquals(1, analytics(passenger).passengerFunnel.expired)
    }
    @Test fun `automatic completion preserves decision and does not manufacture unanswered decision`() = withApp {
        val decision = accept()
        expireStaleRecords(future + 4_000_000)
        assertEquals("COMPLETED", requestRow()[RideRequests.status])
        assertEquals(decision, requestRow()[RideRequests.driverDecisionAt])
        assertEquals("EXPIRED", requestRow("unanswered-request")[RideRequests.status])
        assertNull(requestRow("unanswered-request")[RideRequests.driverDecisionAt])
    }
    @Test fun `location tally uses both endpoints creation window and all statuses`() = withApp {
        // Isolate this aggregation in a disposable database; no development data is touched.
        transaction {
            Rides.update { it[createdAt] = System.currentTimeMillis() - 10 * 86_400_000L }
            Rides.update({ Rides.rideId eq "analytics-ride" }) {
                it[createdAt] = System.currentTimeMillis(); it[status] = RideStatus.CANCELLED
                it[departureTime] = future
            }
        }
        assertEquals(mapOf("Monash Clayton" to 1, "Springvale" to 1), analytics().hotzones.associate { it.locationName to it.count })
    }
    @Test fun `old database gains nullable decision column without backfill or data loss and migration is idempotent`() {
        val file = Files.createTempFile("analytics-migration-", ".db").toFile().apply { deleteOnExit() }
        val url = "jdbc:sqlite:${file.absolutePath.replace('\\', '/')}"
        initDatabase(url)
        val before = transaction { RideRequests.selectAll().associate { it[RideRequests.requestId] to it[RideRequests.respondedAt] } }
        assertTrue(before.values.any { it != null })
        transaction { exec("ALTER TABLE ride_requests DROP COLUMN driver_decision_at") }
        repeat(2) {
            initDatabase(url)
            transaction {
                val rows = RideRequests.selectAll().toList()
                assertEquals(before, rows.associate { it[RideRequests.requestId] to it[RideRequests.respondedAt] })
                assertTrue(rows.all { it[RideRequests.driverDecisionAt] == null })
            }
        }
    }
}
