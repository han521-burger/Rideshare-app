package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.readTransaction
import com.example.ds04_monash_rideshare_application.backend.FavoriteDriverDto
import com.example.ds04_monash_rideshare_application.backend.FavoriteDrivers
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// "Ride with again" shortcuts (Profile / passenger request flow). Favoriting a driver who
// doesn't exist just 404s - no need to validate they've actually driven you anywhere first.
fun Route.favoriteRoutes() {
    get("/favorites") {
        val userId = call.requireUserId()
        val favorites = readTransaction {
            val rows = FavoriteDrivers.selectAll().where { FavoriteDrivers.passengerId eq userId }.toList()
            val driverIds = rows.map { it[FavoriteDrivers.driverId] }.toSet()
            val driversById = if (driverIds.isEmpty()) emptyMap() else {
                Users.selectAll().where { Users.userId inList driverIds }.associateBy { it[Users.userId] }
            }
            rows.mapNotNull { row ->
                val driver = driversById[row[FavoriteDrivers.driverId]] ?: return@mapNotNull null
                FavoriteDriverDto(
                    driverId = driver[Users.userId],
                    displayName = driver[Users.displayName],
                    photoRef = driver[Users.photoRef],
                    addedAt = row[FavoriteDrivers.createdAt]
                )
            }
        }
        call.respond(favorites)
    }

    post("/favorites/{driverId}") {
        val userId = call.requireUserId()
        val driverId = call.parameters["driverId"] ?: throw NotFoundException()
        transaction {
            val exists = Users.selectAll().where { Users.userId eq driverId }.count() > 0
            if (!exists) throw NotFoundException("Driver not found")
            val alreadyFavorited = FavoriteDrivers.selectAll()
                .where { (FavoriteDrivers.passengerId eq userId) and (FavoriteDrivers.driverId eq driverId) }
                .count() > 0
            if (!alreadyFavorited) {
                FavoriteDrivers.insert {
                    it[passengerId] = userId
                    it[FavoriteDrivers.driverId] = driverId
                    it[createdAt] = System.currentTimeMillis()
                }
            }
        }
        call.respond(mapOf("favorited" to true))
    }

    delete("/favorites/{driverId}") {
        val userId = call.requireUserId()
        val driverId = call.parameters["driverId"] ?: throw NotFoundException()
        transaction {
            FavoriteDrivers.deleteWhere { (FavoriteDrivers.passengerId eq userId) and (FavoriteDrivers.driverId eq driverId) }
        }
        call.respond(mapOf("favorited" to false))
    }
}
