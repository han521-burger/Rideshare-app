package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.CreateVehicleRequest
import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.UpdateVehicleRequest
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
import io.ktor.server.routing.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

private fun validateVehicleFields(make: String, model: String, fuelType: String, vehicleType: String, colour: String, seatCapacity: Int, year: Int) {
    if (make.isBlank()) throw ValidationException("make is required")
    if (model.isBlank()) throw ValidationException("model is required")
    if (fuelType.isBlank()) throw ValidationException("fuelType is required")
    if (vehicleType.isBlank()) throw ValidationException("vehicleType is required")
    if (colour.isBlank()) throw ValidationException("colour is required")
    if (seatCapacity <= 0) throw ValidationException("seatCapacity must be positive")
    if (year < 1900 || year > 2100) throw ValidationException("year is not valid")
}

// Vehicle management (docs/FEATURE_PROPOSALS.md item 2, refined: a driver registers exactly one
// vehicle - never a fleet). It's stored once and reused for every ride they offer (RideRoutes.kt's
// POST /rides auto-attaches it), so seat capacity and fuel type always match what a passenger
// actually sees turn up.
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
        validateVehicleFields(body.make, body.model, body.fuelType, body.vehicleType, body.colour, body.seatCapacity, body.year)

        val vehicleId = "vehicle-${UUID.randomUUID()}"
        transaction {
            val alreadyHasVehicle = Vehicles.selectAll().where { Vehicles.ownerId eq ownerId }.count() > 0
            if (alreadyHasVehicle) {
                throw ConflictException("You already have a registered vehicle - update it instead of adding another")
            }
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
                it[vehicleType] = body.vehicleType
                it[colour] = body.colour
            }
        }
        val dto = transaction {
            Vehicles.selectAll().where { Vehicles.vehicleId eq vehicleId }.first().toVehicleDto()
        }
        call.respond(dto)
    }

    // Updates the driver's one vehicle in place (same vehicleId), rather than delete-and-recreate,
    // so any of the driver's existing rides keep pointing at the same (now-updated) vehicle.
    put("/vehicles/{id}") {
        val ownerId = call.requireUserId()
        val vehicleId = call.parameters["id"] ?: throw NotFoundException()
        val body = call.receive<UpdateVehicleRequest>()
        validateVehicleFields(body.make, body.model, body.fuelType, body.vehicleType, body.colour, body.seatCapacity, body.year)

        transaction {
            val vehicle = Vehicles.selectAll().where { Vehicles.vehicleId eq vehicleId }.firstOrNull()
                ?: throw NotFoundException("Vehicle not found")
            if (vehicle[Vehicles.ownerId] != ownerId) throw ForbiddenException("You don't own this vehicle")

            Vehicles.update({ Vehicles.vehicleId eq vehicleId }) {
                it[year] = body.year
                it[make] = body.make
                it[model] = body.model
                it[variant] = body.variant
                it[registrationMasked] = body.registrationMasked
                it[seatCapacity] = body.seatCapacity
                it[fuelType] = body.fuelType
                it[vehicleType] = body.vehicleType
                it[colour] = body.colour
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
