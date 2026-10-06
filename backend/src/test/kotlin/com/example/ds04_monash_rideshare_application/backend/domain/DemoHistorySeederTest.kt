package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.AnalyticsDto
import com.example.ds04_monash_rideshare_application.backend.DEMO_HISTORY_PREFIX
import com.example.ds04_monash_rideshare_application.backend.DashboardDto
import com.example.ds04_monash_rideshare_application.backend.Emissions
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.RideDto
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.RideStatus
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.Vehicles
import com.example.ds04_monash_rideshare_application.backend.demoHistoryPrefixFor
import com.example.ds04_monash_rideshare_application.backend.module
import com.example.ds04_monash_rideshare_application.backend.removeDemoHistory
import com.example.ds04_monash_rideshare_application.backend.seedDemoHistory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Domain logic: seeding synthetic history for one explicit account - idempotent, removable, and
// valid under the real schema rules - and proof that the REAL analytics compute from it.
class DemoHistorySeederTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val email = "demo.target@student.monash.edu"

    private fun demoRides() = transaction { Rides.selectAll().where { Rides.rideId like "$DEMO_HISTORY_PREFIX%" }.toList() }
    private fun demoRequests() = transaction { RideRequests.selectAll().where { RideRequests.requestId like "$DEMO_HISTORY_PREFIX%" }.toList() }
    private fun rowCounts() = transaction {
        listOf(Users.selectAll().count(), Vehicles.selectAll().count(), Rides.selectAll().count(), RideRequests.selectAll().count())
    }
    private fun targetWithVehicle(fuel: String = "PETROL", seats: Int = 5) {
        addUser("target-1", email); addVehicle("target-1", seats = seats, fuel = fuel)
    }

    @Test fun `an unknown target stops with a useful error and creates nothing`() {
        openTempDatabase()
        val before = rowCounts()
        val failure = assertFailsWith<NotFoundException> { transaction { seedDemoHistory("nobody@student.monash.edu") } }
        assertTrue(failure.message!!.contains("nobody@student.monash.edu"))
        assertEquals(before, rowCounts())
    }

    @Test fun `a target without a registered vehicle is refused and creates nothing`() {
        openTempDatabase(); addUser("target-1", email)
        val before = rowCounts()
        assertFailsWith<ValidationException> { transaction { seedDemoHistory(email) } }
        assertEquals(before, rowCounts())
    }

    @Test fun `a deleted account is refused`() {
        openTempDatabase(); addUser("target-1", email, status = "DELETED"); addVehicle("target-1")
        assertFailsWith<ValidationException> { transaction { seedDemoHistory(email) } }
    }

    @Test fun `the target can be named by email or by user id`() {
        openTempDatabase(); targetWithVehicle()
        assertFalse(transaction { seedDemoHistory("target-1") }.alreadySeeded)
        // The same account again, now by (differently cased) email, is recognised as already seeded.
        assertTrue(transaction { seedDemoHistory(email.uppercase()) }.alreadySeeded)
    }

    @Test fun `seeded records follow the real lifecycle schema`() {
        openTempDatabase(); targetWithVehicle()
        val vehicleBefore = transaction { Vehicles.selectAll().where { Vehicles.ownerId eq "target-1" }.single().let { it[Vehicles.vehicleId] to it[Vehicles.fuelType] } }
        val summary = transaction { seedDemoHistory(email) }
        val rides = demoRides(); val requests = demoRequests()

        assertFalse(summary.alreadySeeded)
        assertEquals(32, summary.rides); assertEquals(rides.size, summary.rides)
        assertEquals(summary.driverRides + summary.passengerRides, summary.rides)
        assertEquals(requests.size, summary.requests)
        assertTrue(rides.all { it[Rides.status] == RideStatus.COMPLETED })
        assertTrue(requests.all { it[RideRequests.status] == RideRequestStatus.COMPLETED })

        val requestsByRide = requests.groupBy { it[RideRequests.rideId] }
        transaction {
            rides.forEach { ride ->
                val seatsBooked = requestsByRide[ride[Rides.rideId]].orEmpty().sumOf { it[RideRequests.seatsRequested] }
                assertEquals(ride[Rides.seatsTotal] - ride[Rides.seatsAvailable], seatsBooked, "${ride[Rides.rideId]}: seats filled must equal booked seats")
                assertTrue(seatsBooked >= 1)
                assertTrue(ride[Rides.seatsTotal] in 1..4)
                // The driver owns the vehicle the ride points at.
                val vehicle = Vehicles.selectAll().where { Vehicles.vehicleId eq ride[Rides.vehicleId]!! }.single()
                assertEquals(ride[Rides.driverId], vehicle[Vehicles.ownerId])
                assertTrue(ride[Rides.seatsTotal] <= vehicle[Vehicles.seatCapacity] - 1)
                // Nobody rides their own ride.
                assertTrue(requestsByRide[ride[Rides.rideId]].orEmpty().none { it[RideRequests.passengerId] == ride[Rides.driverId] })
            }
            // Every request's passenger exists.
            requests.forEach { assertEquals(1L, Users.selectAll().where { Users.userId eq it[RideRequests.passengerId] }.count()) }
        }
        // The target's own vehicle was reused untouched: same id, same fuel, still the only one.
        val vehicleAfter = transaction { Vehicles.selectAll().where { Vehicles.ownerId eq "target-1" }.map { it[Vehicles.vehicleId] to it[Vehicles.fuelType] } }
        assertEquals(listOf(vehicleBefore), vehicleAfter)
    }

    @Test fun `seeding twice adds nothing the second time`() {
        openTempDatabase(); targetWithVehicle()
        transaction { seedDemoHistory(email) }
        val after = rowCounts()
        val again = transaction { seedDemoHistory(email) }
        assertTrue(again.alreadySeeded)
        assertEquals(after, rowCounts())
    }

    @Test fun `each target gets its own namespace`() {
        assertTrue(demoHistoryPrefixFor("a").startsWith(DEMO_HISTORY_PREFIX))
        assertFalse(demoHistoryPrefixFor("a") == demoHistoryPrefixFor("b"))
        assertEquals(demoHistoryPrefixFor("a"), demoHistoryPrefixFor("a"))
    }

    @Test fun `removing deletes only demo records and leaves genuine data alone`() {
        openTempDatabase(); targetWithVehicle()
        addUser("friend-1"); addVehicle("friend-1")
        addRide("genuine-ride", "target-1", "target-1-car", RideStatus.COMPLETED, 6_000.0, 3, 2)
        addRequest("genuine-request", "genuine-ride", "friend-1", RideRequestStatus.COMPLETED)
        val genuineUsers = transaction { Users.selectAll().count() }
        val before = rowCounts()
        transaction { seedDemoHistory(email) }

        val removed = transaction { removeDemoHistory(email) }
        assertEquals(32, removed)
        assertEquals(before, rowCounts())
        assertEquals(genuineUsers, transaction { Users.selectAll().count() }, "synthetic cast accounts are removed too")
        assertTrue(demoRides().isEmpty() && demoRequests().isEmpty())
        transaction {
            assertEquals(1L, Rides.selectAll().where { Rides.rideId eq "genuine-ride" }.count())
            assertEquals(1L, RideRequests.selectAll().where { RideRequests.requestId eq "genuine-request" }.count())
        }
        // And it can be seeded again afterwards.
        assertFalse(transaction { seedDemoHistory(email) }.alreadySeeded)
    }

    @Test fun `removing one target keeps another targets history and the shared cast`() {
        openTempDatabase(); targetWithVehicle()
        addUser("target-2", "second.target@student.monash.edu"); addVehicle("target-2")
        transaction { seedDemoHistory(email); seedDemoHistory("second.target@student.monash.edu") }
        transaction { removeDemoHistory(email) }
        val remaining = demoRides()
        assertEquals(32, remaining.size)
        assertTrue(remaining.all { it[Rides.rideId].startsWith(demoHistoryPrefixFor("target-2")) })
        // The cast still exists, so the second target's rides still resolve their drivers and riders.
        transaction {
            remaining.forEach { assertEquals(1L, Users.selectAll().where { Users.userId eq it[Rides.driverId] }.count()) }
        }
    }

    @Test fun `cast accounts cannot sign in`() {
        openTempDatabase(); targetWithVehicle()
        transaction { seedDemoHistory(email) }
        transaction {
            Users.selectAll().where { Users.userId like "$DEMO_HISTORY_PREFIX%" }.forEach {
                assertNull(it[Users.passwordHash])
                assertTrue(it[Users.emailAccount].endsWith("@demo.invalid"))
            }
        }
    }

    // --- the real pipeline, fed only by the seeded rows -------------------------------------

    private fun withApp(test: suspend HttpClient.() -> Unit) {
        val url = tempDatabaseUrl()
        testApplication {
            application { module(url) }
            startApplication()
            targetWithVehicle(fuel = "HYBRID")
            transaction { seedDemoHistory(email) }
            client.test()
        }
    }
    private suspend inline fun <reified T> HttpClient.fetch(path: String): T =
        json.decodeFromString(get(path) { header(HttpHeaders.Authorization, "Bearer target-1") }.bodyAsText())

    @Test fun `dashboard impact is computed from the seeded rows by the normal formula`() = withApp {
        val driven: List<ResultRow> = demoRides().filter { it[Rides.driverId] == "target-1" }
        val expectedCo2 = driven.sumOf { Emissions.co2AvoidedKgForTrip(it[Rides.distanceMeters], "HYBRID", it[Rides.seatsTotal] - it[Rides.seatsAvailable]) }
        val dashboard = fetch<DashboardDto>("/users/me/dashboard")
        assertEquals(driven.size, dashboard.totalRidesCompleted)
        assertTrue(dashboard.totalRidesCompleted > 0 && dashboard.totalCo2AvoidedKg > 0.0 && dashboard.totalFuelSavedLitres > 0.0)
        assertEquals(expectedCo2, dashboard.totalCo2AvoidedKg, 1e-9)
        assertEquals(driven.size, dashboard.monthlyTripSummary.sumOf { it.tripsCompleted })
        assertTrue(dashboard.monthlyTripSummary.size >= 2, "history should span several months")
    }

    @Test fun `trip history and analytics see both roles`() = withApp {
        val history = fetch<List<RideDto>>("/users/me/trip-history")
        assertEquals(demoRides().size, history.size)
        assertTrue(history.any { it.driverId == "target-1" } && history.any { it.driverId != "target-1" })

        val analytics = fetch<AnalyticsDto>("/users/me/analytics")
        assertEquals(history.size, analytics.tripsByDayOfWeek.sum())
        assertTrue(analytics.tripsByDayOfWeek.take(5).all { it > 0 }, "a Monday-to-Friday commuting pattern")
        assertEquals(0, analytics.tripsByDayOfWeek.drop(5).sum())
        assertEquals(history.count { it.driverId != "target-1" }, analytics.passengerFunnel.completed)
        assertTrue(analytics.driverFunnel.completed > 0)
        assertTrue(analytics.mostUsedRoute != null && analytics.mostUsedRoute!!.count >= 2)
        val fill = analytics.averageSeatFillRatePercent
        assertTrue(fill != null && fill > 0.0 && fill <= 100.0)
        assertTrue(analytics.averageDriverResponseMinutes != null)
    }
}
