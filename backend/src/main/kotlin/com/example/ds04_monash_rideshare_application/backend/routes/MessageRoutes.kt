package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.readTransaction
import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.Messages
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.SendMessageRequest
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.toMessageDto
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

// Messaging tied to confirmed rides (item 7): only the driver and passengers with an
// ACCEPTED/COMPLETED request for this ride may read or post to its thread.
private fun requireRideParticipant(userId: String, rideId: String) {
    readTransaction {
        val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
            ?: throw NotFoundException("Ride not found")
        if (ride[Rides.driverId] == userId) return@readTransaction

        val isAcceptedPassenger = RideRequests.selectAll()
            .where {
                (RideRequests.rideId eq rideId) and
                    (RideRequests.passengerId eq userId) and
                    ((RideRequests.status eq RideRequestStatus.ACCEPTED) or (RideRequests.status eq RideRequestStatus.COMPLETED))
            }.count() > 0
        if (!isAcceptedPassenger) throw ForbiddenException("Messaging opens once your seat request is accepted")
    }
}

fun Route.messageRoutes() {
    get("/rides/{rideId}/messages") {
        val userId = call.requireUserId()
        val rideId = call.parameters["rideId"] ?: throw NotFoundException()
        requireRideParticipant(userId, rideId)

        val messages = readTransaction {
            val rows = Messages.selectAll().where { Messages.rideId eq rideId }.orderBy(Messages.sentAt).toList()
            val senderIds = rows.map { it[Messages.senderId] }.toSet()
            val names = if (senderIds.isEmpty()) emptyMap() else Users.selectAll()
                .where { Users.userId inList senderIds }.associate { it[Users.userId] to it[Users.displayName] }
            rows.map { it.toMessageDto(names[it[Messages.senderId]]) }
        }
        call.respond(messages)
    }

    post("/rides/{rideId}/messages") {
        val userId = call.requireUserId()
        val rideId = call.parameters["rideId"] ?: throw NotFoundException()
        requireRideParticipant(userId, rideId)
        val payload = call.receive<SendMessageRequest>()
        if (payload.body.isBlank()) throw ValidationException("Message can't be empty")

        val messageId = "msg-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        transaction {
            Messages.insert {
                it[Messages.messageId] = messageId
                it[Messages.rideId] = rideId
                it[senderId] = userId
                it[Messages.body] = payload.body.take(2000)
                it[sentAt] = now
            }
        }
        val dto = readTransaction {
            val name = Users.selectAll().where { Users.userId eq userId }.firstOrNull()?.get(Users.displayName)
            Messages.selectAll().where { Messages.messageId eq messageId }.map { it.toMessageDto(name) }.first()
        }
        call.respond(dto)
    }
}
