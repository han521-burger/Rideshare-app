package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.UpdateProfileRequest
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import com.example.ds04_monash_rideshare_application.backend.toUserDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun Route.userRoutes() {
    get("/users/me") {
        val userId = call.requireUserId()
        val dto = transaction {
            Users.selectAll().where { Users.userId eq userId }
                .map { it.toUserDto() }
                .firstOrNull()
        } ?: throw NotFoundException("User not found")
        call.respond(dto)
    }

    put("/users/me") {
        val userId = call.requireUserId()
        val body = call.receive<UpdateProfileRequest>()
        transaction {
            Users.update({ Users.userId eq userId }) {
                it[displayName] = body.displayName.trim()
                it[preferredName] = body.preferredName.trim()
                it[phoneNumber] = body.phoneNumber
                it[photoRef] = body.photoRef
                it[passengerEnabled] = body.passengerEnabled
                it[emergencyContactName] = body.emergencyContactName
                it[emergencyContactPhone] = body.emergencyContactPhone
            }
        }
        val dto = transaction {
            Users.selectAll().where { Users.userId eq userId }.map { it.toUserDto() }.first()
        }
        call.respond(dto)
    }

    // Only a user's own rides are exposed here - viewing someone else's ride list isn't needed
    // by the app and would leak trip patterns (NFR: no access to data you don't own).
    get("/users/{id}/rides") {
        val callerId = call.requireUserId()
        val targetId = call.parameters["id"] ?: throw NotFoundException()
        if (callerId != targetId) throw ForbiddenException("You can only view your own ride history")

        val rides = transaction {
            enrichRideDtos(Rides.selectAll().where { Rides.driverId eq targetId }.toList())
        }
        call.respond(rides)
    }
}
