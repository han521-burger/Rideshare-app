package com.example.ds04_monash_rideshare_application.backend

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.sql.DriverManager
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.*

class VehicleRegistrationTest {
    private val owner = "vehicle-test-owner"
    private val other = "vehicle-test-other"
    private val json = Json { ignoreUnknownKeys = true }
    private fun withApp(test: suspend HttpClient.() -> Unit) {
        val file = Files.createTempFile("vehicle-test-", ".db").toFile().apply { deleteOnExit() }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}", testTravelTimes()) }
            startApplication()
            // Bind fixture writes to this app database, not a cached thread-local manager.
            transaction(TransactionManager.defaultDatabase) {
                for (id in listOf(owner, other)) Users.insert {
                    it[userId] = id; it[externalSubjectId] = id; it[emailAccount] = "$id@example.test"
                    it[displayName] = id; it[createdAt] = System.currentTimeMillis()
                }
            }
            client.test()
        }
    }
    private fun body(vararg fields: Pair<String, JsonElement>): String {
        val base = json.parseToJsonElement("""{"make":" Toyota ","model":" Corolla ","registrationMasked":" abc123 ",
            "vehicleType":"SEDAN","colour":" White ","seatCapacity":5,"fuelType":"PETROL"}""").jsonObject
        return JsonObject(base + fields.toMap()).toString()
    }
    private suspend fun HttpClient.register(caller: String = owner, payload: String = body(), expected: HttpStatusCode = HttpStatusCode.OK): VehicleDto? {
        val response = post("/vehicles") { header(HttpHeaders.Authorization, "Bearer $caller"); contentType(ContentType.Application.Json); setBody(payload) }
        assertEquals(expected, response.status, response.bodyAsText())
        return if (expected == HttpStatusCode.OK) json.decodeFromString(response.bodyAsText()) else null
    }
    private suspend fun HttpClient.mine(caller: String = owner): List<VehicleDto> {
        val response = get("/vehicles/mine") { header(HttpHeaders.Authorization, "Bearer $caller") }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString(response.bodyAsText())
    }
    private fun rideBody(seats: Int, recurring: Boolean = false) = """{"originLat":-37.8102,"originLng":144.9628,
        "destinationLat":-37.9105,"destinationLng":145.1362,"distanceMeters":10000.0,"estimatedDurationSeconds":1200,
        "departureTime":${System.currentTimeMillis()+86400000},"seatsTotal":$seats,
        "repeatDaysOfWeek":${if(recurring) "[1,2,3,4,5,6,7]" else "[]"},"repeatWeeks":1}"""
    private suspend fun HttpClient.offer(seats: Int, caller: String = owner, recurring: Boolean = false, expected: HttpStatusCode = HttpStatusCode.OK): RideDto? {
        val response = post("/rides") { header(HttpHeaders.Authorization, "Bearer $caller"); contentType(ContentType.Application.Json); setBody(rideBody(seats, recurring)) }
        assertEquals(expected, response.status, response.bodyAsText())
        return if(expected == HttpStatusCode.OK) json.decodeFromString(response.bodyAsText()) else null
    }
    private suspend fun HttpClient.invalid(field: String, value: JsonElement) {
        register(payload = body(field to value), expected = HttpStatusCode.BadRequest)
        assertTrue(mine().isEmpty())
    }

    @Test fun `registration persists normalized fields and timestamps`() = withApp {
        assertTrue(mine().isEmpty())
        val saved = assertNotNull(register())
        assertEquals(owner, saved.ownerId); assertEquals("Toyota", saved.make); assertEquals("Corolla", saved.model)
        assertEquals("ABC123", saved.registrationMasked); assertEquals("SEDAN", saved.vehicleType)
        assertEquals("White", saved.colour); assertEquals(5, saved.seatCapacity); assertEquals("PETROL", saved.fuelType)
        assertTrue(saved.createdAt > 0); assertTrue(saved.updatedAt >= saved.createdAt)
        assertEquals(listOf(saved), mine())
    }
    @Test fun `second registration conflicts and other account remains independent`() = withApp {
        val saved = register()
        register(payload = body("make" to JsonPrimitive("Honda")), expected = HttpStatusCode.Conflict)
        assertEquals(listOf(saved), mine())
        val second = register(other)
        assertEquals(listOf(second), mine(other)); assertNotEquals(saved?.vehicleId, second?.vehicleId)
    }
    @Test fun `database prevents duplicate owners independently of route`() = withApp {
        val saved = assertNotNull(register())
        assertFailsWith<org.jetbrains.exposed.exceptions.ExposedSQLException> {
            transaction {
                exec("""INSERT INTO vehicles (vehicle_id,owner_id,year,make,model,seat_capacity,fuel_type,vehicle_type,colour)
                    SELECT 'duplicate',owner_id,year,make,model,seat_capacity,fuel_type,vehicle_type,colour
                    FROM vehicles WHERE vehicle_id='${saved.vehicleId}'""")
            }
        }
        assertEquals(listOf(saved), mine())
    }
    @Test fun `blank brand rejected`() = withApp { invalid("make", JsonPrimitive("  ")) }
    @Test fun `blank model rejected`() = withApp { invalid("model", JsonPrimitive("  ")) }
    @Test fun `blank plate rejected`() = withApp { invalid("registrationMasked", JsonPrimitive("  ")) }
    @Test fun `blank colour rejected`() = withApp { invalid("colour", JsonPrimitive("  ")) }
    @Test fun `unknown vehicle type rejected`() = withApp { invalid("vehicleType", JsonPrimitive("FLYING")) }
    @Test fun `unknown fuel type rejected`() = withApp { invalid("fuelType", JsonPrimitive("WATER")) }
    @Test fun `invalid capacity rejected`() = withApp { for (n in listOf(-1,0,1,13)) invalid("seatCapacity", JsonPrimitive(n)) }
    @Test fun `excessive text lengths rejected`() = withApp {
        for ((field, size) in listOf("make" to 65,"model" to 65,"registrationMasked" to 17,"colour" to 33)) invalid(field, JsonPrimitive("X".repeat(size)))
    }
    @Test fun `unauthenticated and foreign ownership cannot affect a vehicle`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, get("/vehicles/mine").status)
        val saved = assertNotNull(register(payload = body("ownerId" to JsonPrimitive(other))))
        assertEquals(owner, saved.ownerId)
        val response = put("/vehicles/${saved.vehicleId}") { header(HttpHeaders.Authorization,"Bearer $other"); contentType(ContentType.Application.Json); setBody(body("make" to JsonPrimitive("Changed"))) }
        assertEquals(HttpStatusCode.Forbidden, response.status); assertEquals(listOf(saved), mine()); assertTrue(mine(other).isEmpty())
    }
    @Test fun `five seat vehicle permits four passengers but not five`() = withApp {
        val vehicle = register()
        assertEquals(vehicle?.vehicleId, offer(4)?.vehicleId)
        offer(5, expected = HttpStatusCode.BadRequest)
    }
    @Test fun `seven seat vehicle permits six passengers`() = withApp {
        val vehicle = register(payload = body("seatCapacity" to JsonPrimitive(7)))
        assertEquals(vehicle?.vehicleId, offer(6)?.vehicleId)
    }
    @Test fun `unregistered caller cannot offer even with another vehicle id`() = withApp {
        register()
        val response = post("/rides") { header(HttpHeaders.Authorization,"Bearer $other"); contentType(ContentType.Application.Json)
            setBody(JsonObject(json.parseToJsonElement(rideBody(2)).jsonObject + ("vehicleId" to JsonPrimitive(mine().single().vehicleId))).toString()) }
        assertEquals(HttpStatusCode.BadRequest,response.status)
    }
    @Test fun `recurring occurrences share registered vehicle and valid capacity`() = withApp {
        val vehicle = register()
        val ride = assertNotNull(offer(4, recurring = true))
        val rows = transaction { Rides.selectAll().where { Rides.recurringGroupId eq ride.recurringGroupId }.toList() }
        assertEquals(7,rows.size)
        assertTrue(rows.all { it[Rides.vehicleId] == vehicle?.vehicleId && it[Rides.seatsTotal] == 4 })
        offer(5, recurring = true, expected = HttpStatusCode.BadRequest)
    }
    @Test fun `seat updates cannot bypass vehicle capacity or remove booked seats`() = withApp {
        register(); val ride = assertNotNull(offer(4))
        val response = put("/rides/${ride.rideId}") { header(HttpHeaders.Authorization,"Bearer $owner"); contentType(ContentType.Application.Json); setBody("""{"seatsTotal":5}""") }
        assertEquals(HttpStatusCode.BadRequest,response.status)
    }
    private suspend fun HttpClient.standalone(seats: Int): RideRequestDto {
        val response = post("/requests") { header(HttpHeaders.Authorization,"Bearer fake-user-001"); contentType(ContentType.Application.Json)
            setBody("""{"originLat":-37.9112,"originLng":145.1029,"destinationLat":-37.9105,"destinationLng":145.1362,"isAsap":true,"seatsRequested":$seats}""") }
        assertEquals(HttpStatusCode.OK,response.status)
        return createdRequests(response.bodyAsText()).single()
    }
    @Test fun `standalone acceptance cannot create unregistered or oversized rides`() = withApp {
        val request = standalone(1)
        suspend fun accept(seats: Int) = put("/requests/${request.requestId}") {
            header(HttpHeaders.Authorization,"Bearer $owner"); contentType(ContentType.Application.Json)
            setBody("""{"status":"ACCEPTED","newRideSeatsTotal":$seats}""")
        }
        assertEquals(HttpStatusCode.BadRequest,accept(4).status)
        val vehicle = register()
        assertEquals(HttpStatusCode.BadRequest,accept(5).status)
        val response = accept(4); assertEquals(HttpStatusCode.OK,response.status)
        val accepted = json.decodeFromString<RideRequestDto>(response.bodyAsText())
        val ride = transaction { Rides.selectAll().where { Rides.rideId eq accepted.rideId!! }.single() }
        assertEquals(vehicle?.vehicleId,ride[Rides.vehicleId])
    }
    private suspend fun HttpClient.emissions(fuel: String, expected: Double) {
        val vehicle = register(payload = body("fuelType" to JsonPrimitive(fuel)))
        val ride = assertNotNull(offer(4))
        assertEquals(vehicle?.vehicleId,ride.vehicleId)
        val ask = post("/rides/${ride.rideId}/requests") { header(HttpHeaders.Authorization,"Bearer fake-user-001"); contentType(ContentType.Application.Json); setBody("""{"seatsRequested":2}""") }
        val request = createdRequests(ask.bodyAsText()).single()
        assertEquals(HttpStatusCode.OK,put("/requests/${request.requestId}") { header(HttpHeaders.Authorization,"Bearer $owner"); contentType(ContentType.Application.Json); setBody("""{"status":"ACCEPTED"}""") }.status)
        val completion = serverClockAfterDeparture(ride.rideId).use { put("/rides/${ride.rideId}") { header(HttpHeaders.Authorization,"Bearer $owner"); contentType(ContentType.Application.Json); setBody("""{"status":"COMPLETED"}""") } }
        assertEquals(HttpStatusCode.OK,completion.status)
        val dashboard = get("/users/me/dashboard") { header(HttpHeaders.Authorization,"Bearer $owner") }
        val value = json.parseToJsonElement(dashboard.bodyAsText()).jsonObject
        assertEquals(expected, value.getValue("totalCo2AvoidedKg").jsonPrimitive.double, 0.00001)
        val leaderboard = get("/leaderboard") { header(HttpHeaders.Authorization,"Bearer $owner") }
        assertEquals(expected,json.parseToJsonElement(leaderboard.bodyAsText()).jsonObject.getValue("me").jsonObject.getValue("totalCo2AvoidedKg").jsonPrimitive.double,0.00001)
        val changedFuel = put("/vehicles/${vehicle!!.vehicleId}") { header(HttpHeaders.Authorization,"Bearer $owner"); contentType(ContentType.Application.Json); setBody(body("fuelType" to JsonPrimitive(if(fuel=="PETROL") "ELECTRIC" else "PETROL"))) }
        assertEquals(HttpStatusCode.Conflict,changedFuel.status)
    }
    @Test fun `petrol registration flows into dashboard and leaderboard`() = withApp { emissions("PETROL",3.4) }
    @Test fun `electric registration flows into dashboard and leaderboard`() = withApp { emissions("ELECTRIC",0.4) }
    @Test fun `vehicle correction preserves id and rejects insufficient active ride capacity`() = withApp {
        val vehicle = assertNotNull(register()); offer(4)
        val response = put("/vehicles/${vehicle.vehicleId}") { header(HttpHeaders.Authorization,"Bearer $owner"); contentType(ContentType.Application.Json); setBody(body("seatCapacity" to JsonPrimitive(4))) }
        assertEquals(HttpStatusCode.Conflict,response.status)
        val corrected = put("/vehicles/${vehicle.vehicleId}") { header(HttpHeaders.Authorization,"Bearer $owner"); contentType(ContentType.Application.Json); setBody(body("colour" to JsonPrimitive(" Blue "))) }
        assertEquals(HttpStatusCode.OK,corrected.status)
        assertEquals(vehicle.vehicleId,mine().single().vehicleId); assertEquals("Blue",mine().single().colour)
    }
    @Test fun `simultaneous registrations leave exactly one vehicle`() = withApp {
        val responses = coroutineScope {
            (1..2).map { async {
                post("/vehicles") { header(HttpHeaders.Authorization,"Bearer $owner"); contentType(ContentType.Application.Json); setBody(body()) }.status
            } }.awaitAll()
        }
        assertEquals(1,responses.count { it == HttpStatusCode.OK })
        assertEquals(1,responses.count { it == HttpStatusCode.Conflict })
        assertEquals(1,mine().size)
    }

    @Test fun `legacy sqlite migration preserves vehicles and adds unique owner index`() {
        val file = Files.createTempFile("legacy-vehicle-", ".db").toFile().apply { deleteOnExit() }
        val url = "jdbc:sqlite:${file.absolutePath.replace('\\','/')}"
        initDatabase(url)
        DriverManager.getConnection(url).use { connection -> connection.createStatement().use { statement ->
            statement.execute("DROP INDEX vehicles_owner_unique")
            statement.execute("ALTER TABLE vehicles DROP COLUMN created_at")
            statement.execute("ALTER TABLE vehicles DROP COLUMN updated_at")
        } }
        initDatabase(url)
        transaction {
            val rows = Vehicles.selectAll().toList()
            assertEquals(3,rows.size)
            assertTrue(rows.all { it[Vehicles.createdAt] == 0L && it[Vehicles.updatedAt] == 0L })
            assertEquals("Toyota",rows.single { it[Vehicles.vehicleId] == "demo-vehicle-001" }[Vehicles.make])
            assertTrue(exec("SELECT name FROM sqlite_master WHERE type='index' AND name='vehicles_owner_unique'") { it.next() } == true)
        }
    }

    @Test fun `migration refuses duplicate legacy owners without deleting data`() {
        val file = Files.createTempFile("duplicate-vehicle-", ".db").toFile().apply { deleteOnExit() }
        val url = "jdbc:sqlite:${file.absolutePath.replace('\\','/')}"
        initDatabase(url)
        DriverManager.getConnection(url).use { connection -> connection.createStatement().use { statement ->
            statement.execute("DROP INDEX vehicles_owner_unique")
            statement.execute("""INSERT INTO vehicles (vehicle_id,owner_id,year,make,model,seat_capacity,fuel_type,vehicle_type,colour)
                SELECT 'legacy-duplicate',owner_id,year,make,model,seat_capacity,fuel_type,vehicle_type,colour FROM vehicles WHERE vehicle_id='demo-vehicle-001'""")
        } }
        assertFailsWith<IllegalStateException> { initDatabase(url) }
        DriverManager.getConnection(url).use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM vehicles").use { rows -> rows.next(); assertEquals(4,rows.getInt(1)) }
        } }
    }

}
