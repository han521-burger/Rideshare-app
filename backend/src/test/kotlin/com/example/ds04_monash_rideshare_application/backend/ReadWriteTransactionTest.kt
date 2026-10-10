package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Writers wait for each other; readers do not wait for anyone. Ordinary read-only requests must not be
// queued behind a schedule update, and nothing may depend on a "database busy" error being retried.
class ReadWriteTransactionTest {
    private val me = "fake-user-001"          // seeded: drives ride-mine-open, rides ride-confirmed
    private val t = futureDeparture()

    private suspend fun HttpClient.getAs(path: String, caller: String = me): HttpResponse =
        get(path) { header(HttpHeaders.Authorization, "Bearer $caller") }

    // Every GET endpoint the app uses.
    private fun readPaths(): List<String> = listOf(
        "/health", "/users/me", "/users/$me/rides", "/vehicles/mine", "/rides", "/rides/ride-confirmed",
        "/rides/match?originLat=-37.9002&originLng=145.0886&destinationLat=-37.9105&destinationLng=145.1362&departureTime=$t",
        "/requests", "/requests/mine", "/favorites", "/rides/ride-confirmed/messages", "/rides/ride-confirmed/ratings",
        "/users/me/trip-history", "/users/me/dashboard", "/users/me/analytics", "/leaderboard"
    )

    @Test
    fun everyReadEndpointWorksOnTheReadOnlyConnection() = withScheduling {
        // The read connection is query_only: a handler that was wrongly marked read-only but writes
        // would fail here instead of silently racing in production.
        for (path in readPaths()) {
            val response = getAs(path)
            assertEquals(HttpStatusCode.OK, response.status, "$path -> ${response.status}")
        }
    }

    @Test
    fun readRequestsAreNotQueuedBehindAScheduleUpdate() = withScheduling {
        HeldWriteLock().use {
            val elapsed = measureTimeMillis {
                // Every read endpoint, several rounds at once, while the write lock is held.
                val responses = coroutineScope {
                    (1..3).flatMap { readPaths() }.map { path -> async(Dispatchers.Default) { path to getAs(path) } }.awaitAll()
                }
                responses.forEach { (path, response) -> assertEquals(HttpStatusCode.OK, response.status, path) }
            }
            // A queued read would sit for the whole busy timeout (10 s) and then fail.
            assertTrue(elapsed < 5_000, "reads took ${elapsed}ms while a writer held the lock")
        }
    }

    @Test
    fun theStaleRecordCheckDoesNotTakeTheWriteLockWhenNothingIsStale() = withScheduling {
        HeldWriteLock().use {
            val elapsed = measureTimeMillis { expireStaleRecords() }
            assertTrue(elapsed < 3_000, "expiry check waited ${elapsed}ms for the write lock with nothing to do")
        }
    }

    @Test
    fun aWriteDoesWaitForTheScheduleUpdateAndThenSucceeds() = withScheduling {
        val lock = HeldWriteLock()
        val write = coroutineScope {
            val pending = async(Dispatchers.Default) {
                put("/users/me") {
                    header(HttpHeaders.Authorization, "Bearer $me")
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":"Waited Student","preferredName":"W","passengerEnabled":true}""")
                }
            }
            delay(800)
            assertFalse(pending.isCompleted, "a write should wait while another writer holds the lock")
            lock.release()
            pending.await()
        }
        assertEquals(HttpStatusCode.OK, write.status)
    }

    @Test
    fun manyConcurrentReadsAllSucceed() = withScheduling {
        val responses = coroutineScope {
            (1..48).map { i -> async(Dispatchers.Default) { getAs(readPaths()[i % readPaths().size]) } }.awaitAll()
        }
        assertTrue(responses.all { it.status == HttpStatusCode.OK }, responses.map { it.status }.groupingBy { it }.eachCount().toString())
    }

    @Test
    fun readsDuringConcurrentWritesAllSucceedWithoutRetries() = withScheduling {
        val responses = coroutineScope {
            val writes = (1..8).map { i ->
                async(Dispatchers.Default) {
                    postOpen(openRequestBody(at = t + i * DAY), PASSENGER)
                }
            }
            val reads = (1..24).map { i -> async(Dispatchers.Default) { getAs(readPaths()[i % readPaths().size]) } }
            (writes + reads).awaitAll()
        }
        assertTrue(responses.none { it.status.value >= 500 }, responses.map { it.status }.groupingBy { it }.eachCount().toString())
    }

    // --- the two kinds of transaction ---

    @Test
    fun aReadTransactionCannotWrite() = withScheduling {
        assertFailsWith<ExposedSQLException> {
            readTransaction { Users.update({ Users.userId eq me }) { it[displayName] = "sneaky" } }
        }
        // ...and nothing changed.
        val name = readTransaction { Users.selectAll().where { Users.userId eq me }.first()[Users.displayName] }
        assertEquals("Test Student", name)
    }

    @Test
    fun aReadInsideAWriteTransactionSeesItsUncommittedWork() = withScheduling {
        val seen = schedulingTransaction(listOf(me)) {
            Users.update({ Users.userId eq me }) { it[displayName] = "Uncommitted" }
            // A helper that reads (via readTransaction) must join this transaction, not open a stale second view.
            readTransaction { Users.selectAll().where { Users.userId eq me }.first()[Users.displayName] }
        }
        assertEquals("Uncommitted", seen)
    }

    @Test
    fun plainTransactionIsAWriteTransactionAndWritesToTheSameFile() = withScheduling {
        assertTrue(TransactionManager.defaultDatabase === Databases.write)
        org.jetbrains.exposed.sql.transactions.transaction {
            Users.update({ Users.userId eq me }) { it[displayName] = "Plain write" }
        }
        assertEquals("Plain write", readTransaction { Users.selectAll().where { Users.userId eq me }.first()[Users.displayName] })
    }

    @Test
    fun writeConnectionIsImmediateAndBothConnectionsWaitForLocksInsteadOfRetrying() = withScheduling {
        fun pragma(db: org.jetbrains.exposed.sql.Database, sql: String) =
            org.jetbrains.exposed.sql.transactions.transaction(db) { exec(sql) { it.next(); it.getInt(1) } }
        assertEquals(SchedulingConfig.DB_BUSY_TIMEOUT_MILLIS, pragma(Databases.write, "PRAGMA busy_timeout"))
        assertEquals(SchedulingConfig.DB_BUSY_TIMEOUT_MILLIS, pragma(Databases.read, "PRAGMA busy_timeout"))
        assertEquals(1, pragma(Databases.read, "PRAGMA query_only"))
        assertEquals(0, pragma(Databases.write, "PRAGMA query_only"))
        // No hidden retry: a failed transaction fails.
        assertEquals(0, TransactionManager.managerFor(Databases.write)?.defaultRepetitionAttempts)
        assertEquals(0, TransactionManager.managerFor(Databases.read)?.defaultRepetitionAttempts)
        assertNotNull(Databases.read)
    }
}
