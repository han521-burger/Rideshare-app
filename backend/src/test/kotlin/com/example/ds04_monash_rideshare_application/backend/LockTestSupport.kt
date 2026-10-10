package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Helpers for the read/write-transaction, expiry and ride-lifecycle concurrency tests.

// Holds the scheduling write lock open from another thread until release() - the stand-in for "a
// schedule update is in progress right now". It does a real write inside, so it is a real writer.
class HeldWriteLock : AutoCloseable {
    private val acquired = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile private var failure: Throwable? = null
    private val thread = Thread {
        try {
            schedulingTransaction(listOf(PASSENGER)) {
                Users.update({ Users.userId eq OTHER_PASSENGER }) { it[preferredName] = "lock-holder" }
                acquired.countDown()
                release.await(60, TimeUnit.SECONDS)
            }
        } catch (t: Throwable) {
            failure = t
            acquired.countDown()
        }
    }.apply { isDaemon = true }

    init {
        thread.start()
        check(acquired.await(10, TimeUnit.SECONDS)) { "write lock was not acquired" }
        failure?.let { throw AssertionError("lock holder failed", it) }
    }

    fun release() {
        release.countDown()
        thread.join(15_000)
        failure?.let { throw AssertionError("lock holder failed", it) }
    }

    override fun close() = release()
}

// A pending open ask written straight to the database (so a test can make it already stale).
fun insertOpenAsk(
    id: String, passengerId: String = PASSENGER, desiredDeparture: Long?, createdAt: Long, asap: Boolean = false,
    status: String = RideRequestStatus.PENDING,
    from: Pair<Double, Double> = Pts.huntingdale, to: Pair<Double, Double> = Pts.clayton
) = transaction {
    RideRequests.insert {
        it[requestId] = id; it[rideId] = null; it[RideRequests.passengerId] = passengerId; it[seatsRequested] = 1
        it[RideRequests.status] = status
        it[originLat] = from.first; it[originLng] = from.second
        it[destinationLat] = to.first; it[destinationLng] = to.second
        it[isAsap] = asap; it[desiredDepartureTime] = desiredDeparture; it[RideRequests.createdAt] = createdAt
    }
}

// A ride written straight to the database, e.g. one whose departure is already in the past.
fun insertRideRow(
    id: String, driverId: String, departure: Long, durationSeconds: Long = 600, status: String = RideStatus.CONFIRMED,
    seatsTotal: Int = 3, seatsAvailable: Int = 2
) = transaction {
    Rides.insert {
        it[rideId] = id; it[Rides.driverId] = driverId
        it[originLat] = Pts.huntingdale.first; it[originLng] = Pts.huntingdale.second
        it[destinationLat] = Pts.clayton.first; it[destinationLng] = Pts.clayton.second
        it[distanceMeters] = 4000.0; it[estimatedDurationSeconds] = durationSeconds
        it[departureTime] = departure; it[Rides.seatsTotal] = seatsTotal; it[Rides.seatsAvailable] = seatsAvailable
        it[Rides.status] = status; it[createdAt] = departure - DAY
    }
}

fun insertAttachedRequest(id: String, passengerId: String, rideId: String, status: String, createdAt: Long) = transaction {
    RideRequests.insert {
        it[requestId] = id; it[RideRequests.rideId] = rideId; it[RideRequests.passengerId] = passengerId
        it[seatsRequested] = 1; it[RideRequests.status] = status; it[RideRequests.createdAt] = createdAt
    }
}

fun requestStatus(id: String): String = transaction {
    RideRequests.selectAll().where { RideRequests.requestId eq id }.first()[RideRequests.status]
}

fun rideStatus(id: String): String = transaction {
    Rides.selectAll().where { Rides.rideId eq id }.first()[Rides.status]
}

fun rowCount(id: String): Long = transaction { RideRequests.selectAll().where { RideRequests.requestId eq id }.count() }
