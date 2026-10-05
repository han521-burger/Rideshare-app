package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.util.Locale

const val ACCOUNT_STATUS_DELETED = "DELETED"
const val DELETED_USER_NAME = "Deleted user"

private val ACTIVE_RIDE_STATUSES = setOf(RideStatus.OPEN, RideStatus.CONFIRMED)
private val ACTIVE_REQUEST_STATUSES = setOf(RideRequestStatus.PENDING, RideRequestStatus.ACCEPTED)

// "Delete my account" (must be called inside a transaction). Upcoming trips are cancelled the same
// way a normal cancel would (other people's seats come back, their bookings on this person's rides
// are cancelled). Past trips, messages and ratings stay so other people's history and analytics
// don't change, but this person's identifying details are wiped - they show as "Deleted user".
// The email is freed, so the same person can sign up again later as a new account.
fun deleteAccount(userId: String, at: Long) {
    Rides.selectAll().where { Rides.driverId eq userId }
        .filter { it[Rides.status] in ACTIVE_RIDE_STATUSES }
        .forEach { ride ->
            val rideId = ride[Rides.rideId]
            Rides.update({ Rides.rideId eq rideId }) { it[status] = RideStatus.CANCELLED }
            RideRequests.update({
                (RideRequests.rideId eq rideId) and (RideRequests.status inList ACTIVE_REQUEST_STATUSES.toList())
            }) {
                it[status] = RideRequestStatus.CANCELLED
                it[respondedAt] = at
            }
        }
    RideRequests.selectAll().where { RideRequests.passengerId eq userId }
        .filter { it[RideRequests.status] in ACTIVE_REQUEST_STATUSES }
        .forEach { cancelRequest(it, at) }

    FavoriteDrivers.deleteWhere { (FavoriteDrivers.passengerId eq userId) or (FavoriteDrivers.driverId eq userId) }
    // The vehicle row stays because past rides point at it (make/model/fuel feed other people's
    // CO2 history); only the identifying plate is removed.
    Vehicles.update({ Vehicles.ownerId eq userId }) { it[registrationMasked] = null }
    Sessions.deleteWhere { Sessions.userId eq userId }
    Users.update({ Users.userId eq userId }) {
        it[displayName] = DELETED_USER_NAME
        it[preferredName] = ""
        it[emailAccount] = "deleted-$userId@deleted.invalid"
        it[externalSubjectId] = "deleted-$userId"
        it[phoneNumber] = null
        it[photoRef] = null
        it[emergencyContactName] = null
        it[emergencyContactPhone] = null
        it[passwordHash] = null
        it[passengerEnabled] = false
        it[accountStatus] = ACCOUNT_STATUS_DELETED
    }
}

// Forgotten-password reset, run by whoever operates the backend (there's no email sending yet):
//   ./gradlew :backend:resetPassword --args="someone@student.monash.edu"
// Sets a new random temporary password, signs the account out everywhere, and returns the
// password to pass on to the person (they can then change it). Must be called inside a transaction.
fun resetPassword(email: String): String {
    val normalized = email.trim().lowercase(Locale.ROOT)
    val user = Users.selectAll().where { Users.emailAccount eq normalized }.firstOrNull()
        ?: throw NotFoundException("No account for $normalized")
    val userId = user[Users.userId]
    val temporary = temporaryPassword()
    Users.update({ Users.userId eq userId }) { it[passwordHash] = hashPassword(temporary) }
    Sessions.deleteWhere { Sessions.userId eq userId }
    LoginThrottle.recordSuccess(normalized)
    return temporary
}

// 12 characters from an alphabet without look-alikes (no 0/O, 1/l/I), easy to read out or type.
private fun temporaryPassword(): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val random = SecureRandom()
    return (1..12).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}
