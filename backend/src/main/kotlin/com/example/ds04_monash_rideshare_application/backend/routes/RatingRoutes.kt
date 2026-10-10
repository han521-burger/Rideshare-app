package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.readTransaction
import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.Ratings
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.SubmitRatingRequest
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import com.example.ds04_monash_rideshare_application.backend.toRatingDto
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

// After a ride completes: a passenger rates the driver, a driver rates each passenger who rode
// with them. One rating per (ride, rater, rated person) - resubmitting is rejected, not
// overwritten, so a rating can't be quietly changed after the fact.
fun Route.ratingRoutes() {
    post("/rides/{rideId}/ratings") {
        val raterId = call.requireUserId()
        val rideId = call.parameters["rideId"] ?: throw NotFoundException()
        val body = call.receive<SubmitRatingRequest>()
        if (body.stars !in 1..5) throw ValidationException("stars must be between 1 and 5")

        val ratingId = "rating-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        transaction {
            val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
                ?: throw NotFoundException("Ride not found")
            val isDriver = ride[Rides.driverId] == raterId
            val wasAcceptedPassenger = RideRequests.selectAll()
                .where {
                    (RideRequests.rideId eq rideId) and (RideRequests.passengerId eq raterId) and
                        ((RideRequests.status eq RideRequestStatus.ACCEPTED) or (RideRequests.status eq RideRequestStatus.COMPLETED))
                }.count() > 0
            if (!isDriver && !wasAcceptedPassenger) {
                throw ForbiddenException("You weren't part of this ride")
            }

            val ratedIsValidTarget = if (isDriver) {
                RideRequests.selectAll().where {
                    (RideRequests.rideId eq rideId) and (RideRequests.passengerId eq body.ratedUserId) and
                        ((RideRequests.status eq RideRequestStatus.ACCEPTED) or (RideRequests.status eq RideRequestStatus.COMPLETED))
                }.count() > 0
            } else {
                ride[Rides.driverId] == body.ratedUserId
            }
            if (!ratedIsValidTarget) throw ValidationException("That person wasn't part of this ride")

            val alreadyRated = Ratings.selectAll().where {
                (Ratings.rideId eq rideId) and (Ratings.raterId eq raterId) and (Ratings.ratedUserId eq body.ratedUserId)
            }.count() > 0
            if (alreadyRated) throw ConflictException("You've already rated this person for this ride")

            Ratings.insert {
                it[Ratings.ratingId] = ratingId
                it[Ratings.rideId] = rideId
                it[Ratings.raterId] = raterId
                it[Ratings.ratedUserId] = body.ratedUserId
                it[stars] = body.stars
                it[tags] = body.tags.joinToString(",").ifBlank { null }
                it[comment] = body.comment
                it[createdAt] = now
            }
        }
        val dto = readTransaction {
            Ratings.selectAll().where { Ratings.ratingId eq ratingId }.map { it.toRatingDto() }.first()
        }
        call.respond(dto)
    }

    get("/rides/{rideId}/ratings") {
        val userId = call.requireUserId()
        val rideId = call.parameters["rideId"] ?: throw NotFoundException()
        val ratings = readTransaction {
            val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
                ?: throw NotFoundException("Ride not found")
            val isDriver = ride[Rides.driverId] == userId
            val wasPassenger = RideRequests.selectAll()
                .where {
                    (RideRequests.rideId eq rideId) and (RideRequests.passengerId eq userId) and
                        ((RideRequests.status eq RideRequestStatus.ACCEPTED) or (RideRequests.status eq RideRequestStatus.COMPLETED))
                }.count() > 0
            if (!isDriver && !wasPassenger) throw ForbiddenException("You weren't part of this ride")

            Ratings.selectAll().where { Ratings.rideId eq rideId }.map { it.toRatingDto() }
        }
        call.respond(ratings)
    }
}
