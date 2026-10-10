package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A database written by an OLDER version of the application - before planning fields, journey intents, conflict details,
// stored submission results, completion times and schedule warnings existed - is opened by the current one. Nothing is
// deleted or rewritten; the old rows keep behaving as the documented legacy rules say; and nothing is fabricated for them.
class LegacyDatabaseMigrationTest {
    private val base = futureDeparture(10)
    private val matrix = completionMatrix()

    private fun exec(file: File, vararg statements: String) = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
        c.createStatement().use { s -> statements.forEach(s::execute) }
    }

    private fun <T> query(file: File, sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } } }
        }

    private fun columns(file: File, table: String) = query(file, "PRAGMA table_info(\"$table\")") { it.getString("name") }.toSet()
    private fun count(file: File, table: String) = query(file, "SELECT COUNT(*) FROM \"$table\"") { it.getLong(1) }.single()

    // Huntingdale station -> Monash Clayton, the coordinates the app has always sent (an approved route, but no point ids stored).
    private val a = coords(Place.A)
    private val b = coords(Place.B)

    private fun ride(id: String, driver: String, departure: Long, status: String, from: Pair<Double, Double> = a, to: Pair<Double, Double> = b) =
        "INSERT INTO rides (ride_id, driver_id, origin_lat, origin_lng, destination_lat, destination_lng, distance_meters, " +
            "estimated_duration_seconds, departure_time, seats_total, seats_available, status, driver_en_route, created_at) VALUES " +
            "('$id','$driver',${from.first},${from.second},${to.first},${to.second},4000.0,600,$departure,3,2,'$status',0,${departure - DAY})"

    private fun ask(id: String, passenger: String, departure: Long, status: String = "PENDING", rideId: String? = null) =
        "INSERT INTO ride_requests (request_id, ride_id, passenger_id, seats_requested, status, origin_lat, origin_lng, destination_lat, " +
            "destination_lng, is_asap, desired_departure_time, created_at) VALUES ('$id',${rideId?.let { "'$it'" } ?: "NULL"},'$passenger',1,'$status'," +
            "${if (rideId == null) "${a.first},${a.second},${b.first},${b.second}" else "NULL,NULL,NULL,NULL"},0,$departure,${System.currentTimeMillis() - 5 * DAY})"

    private fun seat(id: String, passenger: String, rideId: String, status: String) =
        "INSERT INTO ride_requests (request_id, ride_id, passenger_id, seats_requested, status, is_asap, created_at) " +
            "VALUES ('$id','$rideId','$passenger',1,'$status',0,${System.currentTimeMillis() - 5 * DAY})"

    private fun user(id: String) =
        "INSERT INTO users (user_id, external_subject_id, email_account, display_name, preferred_name, account_status, passenger_enabled, created_at) " +
            "VALUES ('$id','sub-$id','$id@student.monash.edu','$id','$id','ACTIVE',1,1000)"

    private val oldSchema = listOf(
        """CREATE TABLE users (user_id VARCHAR(64) PRIMARY KEY, external_subject_id VARCHAR(128) NOT NULL UNIQUE, email_account VARCHAR(256) NOT NULL UNIQUE,
           display_name VARCHAR(128) NOT NULL, preferred_name VARCHAR(128) NOT NULL DEFAULT '', phone_number VARCHAR(32), photo_ref VARCHAR(512),
           account_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', passenger_enabled BOOLEAN NOT NULL DEFAULT 1, emergency_contact_name VARCHAR(128),
           emergency_contact_phone VARCHAR(32), created_at BIGINT NOT NULL, password_hash VARCHAR(256))""",
        """CREATE TABLE rides (ride_id VARCHAR(64) PRIMARY KEY, driver_id VARCHAR(64) NOT NULL, vehicle_id VARCHAR(64), origin_lat REAL NOT NULL,
           origin_lng REAL NOT NULL, destination_lat REAL NOT NULL, destination_lng REAL NOT NULL, distance_meters REAL NOT NULL DEFAULT 0.0,
           estimated_duration_seconds BIGINT NOT NULL DEFAULT 0, departure_time BIGINT NOT NULL, seats_total INT NOT NULL, seats_available INT NOT NULL,
           notes VARCHAR(1024), status VARCHAR(32) NOT NULL DEFAULT 'OPEN', driver_en_route BOOLEAN NOT NULL DEFAULT 0, recurring_group_id VARCHAR(64),
           created_at BIGINT NOT NULL)""",
        """CREATE TABLE ride_requests (request_id VARCHAR(64) PRIMARY KEY, ride_id VARCHAR(64), passenger_id VARCHAR(64) NOT NULL, seats_requested INT NOT NULL,
           status VARCHAR(32) NOT NULL DEFAULT 'PENDING', note VARCHAR(500), origin_lat REAL, origin_lng REAL, destination_lat REAL, destination_lng REAL,
           is_asap BOOLEAN NOT NULL DEFAULT 0, desired_departure_time BIGINT, repeat_days_of_week VARCHAR(32), repeat_weeks INT, created_at BIGINT NOT NULL,
           responded_at BIGINT)""",
        // The first version of the idempotency table: no stored result.
        """CREATE TABLE request_submissions (passenger_id VARCHAR(64) NOT NULL, client_request_id VARCHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL,
           fingerprint VARCHAR(64) NOT NULL, request_ids TEXT NOT NULL, created_at BIGINT NOT NULL, PRIMARY KEY (passenger_id, client_request_id))"""
    )

    private val legacyAskBody = openRequestBody(from = a, to = b, at = base + 2 * DAY, clientRequestId = "legacy-key-0001")
    private fun legacyFingerprint(): String {
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<CreateStandaloneRequestBody>(legacyAskBody)
        return fingerprintOf("OPEN|" + Json.encodeToString(CreateStandaloneRequestBody.serializer(), parsed.copy(clientRequestId = null)))
    }

    private fun legacyDatabase(): File {
        val file = Files.createTempFile("rideshare-legacy-", ".db").toFile().apply { deleteOnExit() }
        exec(
            file, *oldSchema.toTypedArray(),
            user(PASSENGER), user(OTHER_PASSENGER), user(DRIVER_A), user(DRIVER_B),
            // A future open ride, a ride that finished long ago, and a live ride (L3) carrying an inconsistent seat.
            ride("L1", DRIVER_A, base, "OPEN"), ride("L2", DRIVER_A, base - 30 * DAY, "COMPLETED"), ride("L3", DRIVER_B, base + DAY, "CONFIRMED"),
            ride("L4", DRIVER_B, base + 3 * DAY, "OPEN"),
            // Two identical waiting asks left behind by a version that did not refuse duplicates (no journey key: exempt from the index).
            ask("dup1", PASSENGER, base + 2 * DAY), ask("dup2", PASSENGER, base + 2 * DAY),
            // Seat requests with no journey intent: independent journeys.
            seat("seat-l1", OTHER_PASSENGER, "L1", "PENDING"), seat("seat-l4", OTHER_PASSENGER, "L4", "PENDING"),
            // A finished trip, and a seat marked COMPLETED while its ride is still live.
            seat("seat-done", PASSENGER, "L2", "COMPLETED"), seat("seat-inconsistent", PASSENGER, "L3", "COMPLETED"),
            "INSERT INTO request_submissions (passenger_id, client_request_id, kind, fingerprint, request_ids, created_at) " +
                "VALUES ('$PASSENGER','legacy-key-0001','OPEN','${legacyFingerprint()}','dup1',1000)"
        )
        return file
    }

    private fun legacyApp(test: suspend HttpClient.(File) -> Unit) {
        val file = legacyDatabase()
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}", matrix) }
            startApplication()
            client.test(file)
        }
    }

    @Test
    fun theOldDatabaseMigratesInPlaceKeepsEveryRowAndInventsNothing() {
        val file = legacyDatabase()
        val tables = listOf("users", "rides", "ride_requests", "request_submissions")
        val before = tables.associateWith { count(file, it) }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}", matrix) }
            startApplication()
        }
        for (table in tables) assertEquals(before.getValue(table), count(file, table), "$table lost or gained rows")
        // The columns every later stage added are there ...
        assertTrue(columns(file, "rides").containsAll(setOf("origin_point_id", "destination_point_id", "planned_duration_seconds", "planned_arrival_time", "completed_at")))
        assertTrue(columns(file, "ride_requests").containsAll(setOf("origin_point_id", "destination_point_id", "journey_key", "planned_duration_seconds",
            "planned_arrival_time", "journey_intent_id", "conflict_details", "driver_decision_at")))
        assertTrue("result" in columns(file, "request_submissions"))
        assertTrue(count(file, "schedule_warnings") == 0L)
        // ... and old rows have nothing in them: no planning, no intent, no conflict, no completion time, no stored result.
        assertEquals(0L, query(file, "SELECT COUNT(*) FROM rides WHERE completed_at IS NOT NULL OR planned_arrival_time IS NOT NULL OR origin_point_id IS NOT NULL") { it.getLong(1) }.single())
        assertEquals(0L, query(file, "SELECT COUNT(*) FROM ride_requests WHERE journey_intent_id IS NOT NULL OR conflict_details IS NOT NULL OR journey_key IS NOT NULL OR planned_arrival_time IS NOT NULL") { it.getLong(1) }.single())
        assertEquals(0L, query(file, "SELECT COUNT(*) FROM request_submissions WHERE result IS NOT NULL") { it.getLong(1) }.single())
    }

    @Test
    fun oldDuplicateAsksStayHistoryAndDoNotBecomeNewlyActionableDuplicates() = legacyApp { _ ->
        // Both rows are still there (history) but a driver is offered the journey once.
        assertEquals(2L, transaction {
            RideRequests.selectAll().where { RideRequests.requestId inList listOf("dup1", "dup2") }.count()
        })
        val feed = driverFeedAll(DRIVER_B).filter { it.requestId in setOf("dup1", "dup2") }
        assertEquals(1, feed.size)
        // A new request for the same journey is still a duplicate of the live one.
        val again = postOpen(openRequestBody(from = a, to = b, at = base + 2 * DAY))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, again.asError().code)
    }

    @Test
    fun anOldSubmissionWithNoStoredResultStillReplays() = legacyApp { file ->
        val rowsBefore = count(file, "ride_requests")
        val replay = postOpen(legacyAskBody)
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        val result = creationResult(replay.bodyAsText())
        assertEquals(listOf("dup1"), result.requests.map { it.requestId })
        assertEquals(1, result.created)
        assertEquals(rowsBefore, count(file, "ride_requests"), "a replay creates nothing")
        // Old rows are never rewritten: still no stored result.
        assertNull(submissionResult(PASSENGER, "legacy-key-0001"))
    }

    @Test
    fun legacyCompletedRowsKeepNoCompletionTimeAndLegacyRidesFollowTheDocumentedFallback() = legacyApp { file ->
        assertNull(rideAs(DRIVER_A, "L2").completedAt)
        assertNull(rideRow("L2")[Rides.completedAt])
        // The legacy ride has no planning fields: its interval is derived in memory (Huntingdale -> Clayton, 10 minutes in
        // this test's matrix) and never written back.
        val l1 = rideRow("L1")
        assertNull(l1[Rides.plannedArrivalTime]); assertNull(l1[Rides.originPointId])
        // OTHER_PASSENGER holds a (pending) seat on L1 at base: a trip overlapping it is refused by the physical rule alone.
        openBetween(Place.A, Place.B, base + 5 * MINUTE, caller = OTHER_PASSENGER).scheduleConflict()
        // ... and one that follows it with enough room is not: 10 (ride) + 5 (back B -> A) ... + 10 buffer.
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, base + 40 * MINUTE, caller = OTHER_PASSENGER).status)
        assertNull(rideRow("L1")[Rides.plannedArrivalTime], "still not written back")
        assertEquals(0L, query(file, "SELECT COUNT(*) FROM rides WHERE completed_at IS NOT NULL") { it.getLong(1) }.single())
    }

    @Test
    fun legacyRequestsWithNoJourneyIntentAreIndependentJourneys() = legacyApp { _ ->
        // Two pending seats with no intent, days apart: both fit, both stay, neither is treated as an alternative of the other.
        assertEquals(RideRequestStatus.PENDING, requestRow("seat-l1")[RideRequests.status])
        assertEquals(RideRequestStatus.PENDING, requestRow("seat-l4")[RideRequests.status])
        assertNull(requestRow("seat-l1")[RideRequests.journeyIntentId])
        // Accepting one never cancels the other (that is what a shared intent would do).
        val accepted = respond(DRIVER_A, "seat-l1", "ACCEPTED")
        assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
        assertEquals(RideRequestStatus.ACCEPTED, requestRow("seat-l1")[RideRequests.status])
        assertEquals(RideRequestStatus.PENDING, requestRow("seat-l4")[RideRequests.status])
    }

    @Test
    fun aSeatMarkedCompletedOnALiveRideCannotBeUsedToEscapeTheSchedule() = legacyApp { _ ->
        // PASSENGER's seat on L3 (Huntingdale -> Clayton, a day after base) was marked COMPLETED early by an older version.
        // It is still a live commitment: a trip overlapping L3 is refused, a trip after it is not.
        openBetween(Place.A, Place.B, base + DAY + 5 * MINUTE).scheduleConflict()
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, base + DAY + 60 * MINUTE).status)
        // And it is not history: the only history PASSENGER has is the finished L2.
        assertEquals("seat-done", transaction { latestCompletedJourneyOf(PASSENGER, matrix) }?.id)
        // Completing it directly is still refused while L3 is live.
        val direct = respond(PASSENGER, "seat-inconsistent", "COMPLETED")
        assertEquals(HttpStatusCode.Conflict, direct.status)
        assertEquals(RideRequestStatus.COMPLETED, requestRow("seat-inconsistent")[RideRequests.status])
    }
}
