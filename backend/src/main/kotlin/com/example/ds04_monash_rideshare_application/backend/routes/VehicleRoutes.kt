package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.CreateVehicleRequest
import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.Vehicles
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import com.example.ds04_monash_rideshare_application.backend.toVehicleDto
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

// Vehicle management (docs/FEATURE_PROPOSALS.md item 2): the Vehicles table (Tables.kt) has
// always been complete server-side - this was purely a missing-endpoints gap, no schema change.
fun Route.vehicleRoutes() {
    get("/vehicles/mine") {
        val ownerId = call.requireUserId()
        val vehicles = transaction {
            Vehicles.selectAll().where { Vehicles.ownerId eq ownerId }.map { it.toVehicleDto() }
        }
        call.respond(vehicles)
    }

    post("/vehicles") {
        val ownerId = call.requireUserId()
        val body = call.receive<CreateVehicleRequest>()
        if (body.make.isBlank()) throw ValidationException("make is required")
        if (body.model.isBlank()) throw ValidationException("model is required")
        if (body.fuelType.isBlank()) throw ValidationException("fuelType is required")
        if (body.seatCapacity <= 0) throw ValidationException("seatCapacity must be positive")
        if (body.year < 1900 || body.year > 2100) throw ValidationException("year is not valid")

        val vehicleId = "vehicle-${UUID.randomUUID()}"
        transaction {
            Vehicles.insert {
                it[Vehicles.vehicleId] = vehicleId
                it[Vehicles.ownerId] = ownerId
                it[year] = body.year
                it[make] = body.make
                it[model] = body.model
                it[variant] = body.variant
                it[registrationMasked] = body.registrationMasked
                it[seatCapacity] = body.seatCapacity
                it[fuelType] = body.fuelType
            }
        }
        val dto = transaction {
            Vehicles.selectAll().where { Vehicles.vehicleId eq vehicleId }.first().toVehicleDto()
        }
        call.respond(dto)
    }

    delete("/vehicles/{id}") {
        val ownerId = call.requireUserId()
        val vehicleId = call.parameters["id"] ?: throw NotFoundException()

        transaction {
            val vehicle = Vehicles.selectAll().where { Vehicles.vehicleId eq vehicleId }.firstOrNull()
                ?: throw NotFoundException("Vehicle not found")
            if (vehicle[Vehicles.ownerId] != ownerId) throw ForbiddenException("You don't own this vehicle")

            val usedByRide = Rides.selectAll().where { Rides.vehicleId eq vehicleId }.count() > 0
            if (usedByRide) {
                throw ConflictException("This vehicle is attached to an existing ride - remove it from that ride first")
            }

            Vehicles.deleteWhere { Vehicles.vehicleId eq vehicleId }
        }
        call.respond(mapOf("deleted" to true))
    }
}
