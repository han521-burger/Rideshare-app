package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale

// Explicit, operator-run seeding of SYNTHETIC completed-ride history for one named account
// (CLI: SeedDemoHistoryTool.kt). It writes ordinary rows through the ordinary tables, so the real
// dashboard / trip-history / analytics code computes every figure - nothing is hardcoded.
//
// Everything it creates lives in one id namespace so it can be found and removed again:
//   rides/requests   demohist-<8 hex of the target's user id>-...   (one set per target account)
//   cast accounts    demohist-driver-N / demohist-passenger-N        (shared synthetic people)
// No schema change. Genuine records never carry the prefix, so removal never touches them.
const val DEMO_HISTORY_PREFIX = "demohist-"

private val DRIVER_CAST = listOf(
    Triple("Demo Driver – Alex", Triple("Toyota", "Corolla", "HYBRID"), "Silver"),
    Triple("Demo Driver – Sam", Triple("Mazda", "3", "PETROL"), "Blue"),
    Triple("Demo Driver – Jordan", Triple("Tesla", "Model 3", "ELECTRIC"), "White")
)
private val PASSENGER_CAST = listOf("Riley", "Casey", "Morgan", "Taylor")
private const val CAST_VEHICLE_SEATS = 5
private const val COMPLETION_DELAY_MILLIS = 30L * 60_000L
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

data class DemoSeedSummary(
    val alreadySeeded: Boolean,
    val rides: Int,
    val driverRides: Int,
    val passengerRides: Int,
    val requests: Int
)

fun demoHistoryPrefixFor(userId: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(userId.toByteArray())
    return DEMO_HISTORY_PREFIX + digest.take(4).joinToString("") { "%02x".format(it) } + "-"
}

// An email (anything with "@") or a user id. Must be called inside a transaction.
fun resolveDemoTarget(emailOrUserId: String): ResultRow {
    val key = emailOrUserId.trim()
    if (key.isEmpty()) throw ValidationException("Name the account to seed: an email address or a user id")
    val user = if ('@' in key) Users.selectAll().where { Users.emailAccount eq key.lowercase(Locale.ROOT) }.firstOrNull()
    else Users.selectAll().where { Users.userId eq key }.firstOrNull()
    user ?: throw NotFoundException("No account for $key. Nothing was seeded (this tool never creates the target account).")
    if (user[Users.accountStatus] == ACCOUNT_STATUS_DELETED) throw ValidationException("$key is a deleted account")
    return user
}

// Must be called inside a transaction. Idempotent: a target that already has demo history is
// left exactly as it is.
fun seedDemoHistory(
    emailOrUserId: String,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): DemoSeedSummary {
    val user = resolveDemoTarget(emailOrUserId)
    val userId = user[Users.userId]
    val prefix = demoHistoryPrefixFor(userId)

    if (Rides.selectAll().where { Rides.rideId like "$prefix%" }.count() > 0) {
        return DemoSeedSummary(true, 0, 0, 0, 0)
    }
    // Driver rides need the target's own registered vehicle (one per account); it is never
    // replaced or edited - only read, so impact uses its real fuel type.
    val vehicle = Vehicles.selectAll().where { Vehicles.ownerId eq userId }.singleOrNull()
        ?: throw ValidationException("This account has no registered vehicle. Register one in the app (Profile > My vehicle) first - demo driving history is attributed to it.")
    val maxPassengerSeats = vehicle[Vehicles.seatCapacity] - 1
    val plan = DemoHistoryGenerator.generate(now, zone, maxPassengerSeats)

    ensureCast(now)
    var requests = 0
    plan.forEach { trip ->
        val rideId = "${prefix}r%02d".format(trip.index)
        val driverId = if (trip.role == DemoRole.DRIVER) userId else "${DEMO_HISTORY_PREFIX}driver-${trip.counterpartSlot + 1}"
        val vehicleId = if (trip.role == DemoRole.DRIVER) vehicle[Vehicles.vehicleId] else "${DEMO_HISTORY_PREFIX}vehicle-${trip.counterpartSlot + 1}"
        val requestedAt = trip.departureTime - trip.requestLeadMillis
        Rides.insert {
            it[Rides.rideId] = rideId
            it[Rides.driverId] = driverId
            it[Rides.vehicleId] = vehicleId
            it[originLat] = trip.origin.lat
            it[originLng] = trip.origin.lng
            it[destinationLat] = trip.destination.lat
            it[destinationLng] = trip.destination.lng
            it[distanceMeters] = trip.distanceMeters
            it[estimatedDurationSeconds] = trip.durationSeconds
            it[departureTime] = trip.departureTime
            it[seatsTotal] = trip.seatsTotal
            it[seatsAvailable] = trip.seatsTotal - trip.seatsFilled
            it[status] = RideStatus.COMPLETED
            it[createdAt] = requestedAt - 60 * 60_000L
        }

        // One COMPLETED request per filled seat-holder, exactly as a finished real ride leaves
        // them: decision time kept, responded-at moved to the completion time.
        val completedAt = trip.departureTime + trip.durationSeconds * 1000 + COMPLETION_DELAY_MILLIS
        val riders = buildList {
            if (trip.role == DemoRole.PASSENGER) add(userId to trip.targetSeats)
            val others = trip.seatsFilled - trip.targetSeats
            repeat(others) { k ->
                val slot = (trip.counterpartSlot + k + 1) % DemoHistoryGenerator.SYNTHETIC_PASSENGERS
                add("${DEMO_HISTORY_PREFIX}passenger-${slot + 1}" to 1)
            }
        }
        riders.forEachIndexed { k, (passenger, seats) ->
            val created = requestedAt + k * 7 * 60_000L
            RideRequests.insert {
                it[requestId] = "${prefix}q%02d-%d".format(trip.index, k + 1)
                it[RideRequests.rideId] = rideId
                it[passengerId] = passenger
                it[seatsRequested] = seats
                it[status] = RideRequestStatus.COMPLETED
                it[createdAt] = created
                it[driverDecisionAt] = created + trip.decisionDelayMillis
                it[respondedAt] = completedAt
            }
            requests++
        }
    }
    return DemoSeedSummary(false, plan.size, plan.count { it.role == DemoRole.DRIVER },
        plan.count { it.role == DemoRole.PASSENGER }, requests)
}

// Removes ONLY rows in this target's demo namespace; the shared synthetic cast goes once no demo
// ride of anyone is left. Returns the number of demo rides removed. Must be called inside a
// transaction.
fun removeDemoHistory(emailOrUserId: String): Int {
    val prefix = demoHistoryPrefixFor(resolveDemoTarget(emailOrUserId)[Users.userId])
    val rideIds = Rides.selectAll().where { Rides.rideId like "$prefix%" }.map { it[Rides.rideId] }
    if (rideIds.isNotEmpty()) {
        // Only synthetic rides can carry these, so nothing genuine is lost.
        Ratings.deleteWhere { rideId inList rideIds }
        Messages.deleteWhere { rideId inList rideIds }
        RideStops.deleteWhere { rideId inList rideIds }
        RideRequests.deleteWhere { rideId inList rideIds }
        Rides.deleteWhere { rideId inList rideIds }
    }
    if (Rides.selectAll().where { Rides.rideId like "$DEMO_HISTORY_PREFIX%" }.count() == 0L) {
        Vehicles.deleteWhere { vehicleId like "$DEMO_HISTORY_PREFIX%" }
        Users.deleteWhere { userId like "$DEMO_HISTORY_PREFIX%" }
    }
    return rideIds.size
}

private fun ensureCast(now: Long) {
    DRIVER_CAST.forEachIndexed { i, (name, car, colour) ->
        val id = "${DEMO_HISTORY_PREFIX}driver-${i + 1}"
        insertCastUser(id, name, now)
        if (Vehicles.selectAll().where { Vehicles.vehicleId eq "${DEMO_HISTORY_PREFIX}vehicle-${i + 1}" }.count() == 0L) {
            Vehicles.insert {
                it[vehicleId] = "${DEMO_HISTORY_PREFIX}vehicle-${i + 1}"
                it[ownerId] = id
                it[year] = 2021
                it[make] = car.first
                it[model] = car.second
                it[registrationMasked] = "DEMO${i + 1}"
                it[seatCapacity] = CAST_VEHICLE_SEATS
                it[fuelType] = car.third
                it[vehicleType] = "SEDAN"
                it[Vehicles.colour] = colour
                it[createdAt] = now - 90 * DAY_MILLIS
                it[updatedAt] = now - 90 * DAY_MILLIS
            }
        }
    }
    PASSENGER_CAST.forEachIndexed { i, name -> insertCastUser("${DEMO_HISTORY_PREFIX}passenger-${i + 1}", "Demo Passenger – $name", now) }
}

// Cast accounts have no password, so nobody can sign in as them; the reserved .invalid domain
// can never receive mail.
private fun insertCastUser(id: String, name: String, now: Long) {
    if (Users.selectAll().where { Users.userId eq id }.count() > 0L) return
    Users.insert {
        it[userId] = id
        it[externalSubjectId] = "$id-subject"
        it[emailAccount] = "$id@demo.invalid"
        it[displayName] = name
        it[preferredName] = name.substringAfter("– ")
        it[accountStatus] = "ACTIVE"
        it[passengerEnabled] = id.contains("passenger")
        it[createdAt] = now - 90 * DAY_MILLIS
    }
}

// Convenience for the CLI.
fun seedDemoHistoryInTransaction(emailOrUserId: String) = transaction { seedDemoHistory(emailOrUserId) }
fun removeDemoHistoryInTransaction(emailOrUserId: String) = transaction { removeDemoHistory(emailOrUserId) }
