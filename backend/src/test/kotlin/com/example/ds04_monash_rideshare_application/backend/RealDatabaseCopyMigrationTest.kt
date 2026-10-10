package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Stage 10 migration check against a COPY of a real development database. It only runs when STAGE10_DB_COPY names a copy
// (never the original: the file is opened read/write and migrated in place), so ordinary test runs skip it:
//   $env:STAGE10_DB_COPY = "C:\path\to\copy.db"; ./gradlew :backend:test --tests '*RealDatabaseCopyMigrationTest' -i
// It records every table's row count before and after the application starts on it, checks that every column the later stages
// added now exists, and then reads the data through the real routes. Lines starting with MIGRATION are the report.
class RealDatabaseCopyMigrationTest {
    private val path = System.getenv("STAGE10_DB_COPY")

    private fun <T> query(file: String, sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } } }
        }

    private fun tables(file: String) = query(file, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { it.getString(1) }
    private fun counts(file: String) = tables(file).associateWith { query(file, "SELECT COUNT(*) FROM \"$it\"") { rs -> rs.getLong(1) }.single() }
    private fun columns(file: String, table: String) = query(file, "PRAGMA table_info(\"$table\")") { it.getString("name") }.toSet()

    @Test
    fun aCopyOfTheRealDevelopmentDatabaseMigratesInPlaceWithoutLosingARow() {
        assumeTrue(path != null, "STAGE10_DB_COPY is not set - skipped")
        val file = File(path!!)
        assumeTrue(file.exists(), "STAGE10_DB_COPY does not exist - skipped")
        val before = counts(path)
        val tablesBefore = tables(path).toSet()
        println("MIGRATION before: $before")

        val url = "jdbc:sqlite:${path.replace('\\', '/')}"
        initDatabase(url)

        val after = counts(path)
        println("MIGRATION after:  $after")
        // No historical business row disappears (tables that existed keep every row; new tables start empty, or - for the
        // tables the app seeds only when the database has no users - are untouched).
        for ((table, was) in before) assertEquals(was, after[table], "row count of $table changed by migration")
        for (table in after.keys - tablesBefore) println("MIGRATION new table: $table (${after[table]} rows)")

        // Every column the scheduling stages added is there.
        val expected = mapOf(
            "rides" to setOf("origin_point_id", "destination_point_id", "planned_duration_seconds", "planned_arrival_time", "completed_at"),
            "ride_requests" to setOf(
                "origin_point_id", "destination_point_id", "journey_key", "planned_duration_seconds", "planned_arrival_time",
                "journey_intent_id", "conflict_details", "driver_decision_at"
            ),
            "request_submissions" to setOf("result"),
            "schedule_warnings" to setOf("warning_id", "user_id", "subject_kind", "subject_id", "cause_ride_id", "type", "details", "created_at")
        )
        for ((table, wanted) in expected) {
            val have = columns(path, table)
            println("MIGRATION $table columns missing: ${wanted - have}")
            assertTrue(have.containsAll(wanted), "$table is missing ${wanted - have}")
        }

        // Nothing was invented for old rows.
        assertEquals(0L, query(path, "SELECT COUNT(*) FROM rides WHERE completed_at IS NOT NULL") { it.getLong(1) }.single(), "no completed_at is fabricated")
        println("MIGRATION rides with completed_at: 0; schedule_warnings: ${after["schedule_warnings"]}")

        // ... and the app can read it all through its real routes (this also runs the lazy expiry over the old rows).
        val userIds = query(path, "SELECT user_id FROM users ORDER BY user_id") { it.getString(1) }
        testApplication {
            application { module(url, testTravelTimes()) }
            startApplication()
            for (user in userIds) for (route in listOf("/requests/mine", "/requests", "/rides", "/schedule-warnings", "/users/me/trip-history", "/users/me/analytics")) {
                val response = client.get(route) { header(HttpHeaders.Authorization, "Bearer $user") }
                assertEquals(HttpStatusCode.OK, response.status, "$route as $user: ${response.bodyAsText().take(300)}")
            }
        }
        val afterReading = counts(path)
        println("MIGRATION routes read for ${userIds.size} users: OK; row counts after reading: $afterReading")
        // Reading (and the lazy expiry it runs) changes statuses, never the number of rows.
        assertEquals(after, afterReading)
    }
}
