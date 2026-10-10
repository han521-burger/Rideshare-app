package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.readTransaction
import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.CreateVehicleRequest
import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.UpdateVehicleRequest
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
import com.example.ds04_monash_rideshare_application.backend.normalized
import com.example.ds04_monash_rideshare_application.backend.RideStatus
import org.jetbrains.exposed.exceptions.ExposedSQLException

// Vehicle management (docs/FEATURE_PROPOSALS.md item 2, refined: a driver registers exactly one
// vehicle - never a fleet). It's stored once and reused for every ride they offer (RideRoutes.kt's
// POST /rides auto-attaches it), so seat capacity and fuel type always match what a passenger
// actually sees turn up.
fun Route.vehicleRoutes() {
    get("/vehicles/mine") {
        val ownerId = call.requireUserId()
        val vehicles = readTransaction {
            Vehicles.selectAll().where { Vehicles.ownerId eq ownerId }.map { it.toVehicleDto() }
        }
        call.respond(vehicles)
    }

    post("/vehicles") {
        val ownerId = call.requireUserId()
        val body = call.receive<CreateVehicleRequest>().normalized()

        val vehicleId = "vehicle-${UUID.randomUUID()}"
        try {
            transaction {
                val alreadyHasVehicle = Vehicles.selectAll().where { Vehicles.ownerId eq ownerId }.count() > 0
                if (alreadyHasVehicle) {
                    throw ConflictException("You already have a registered vehicle - update it instead of adding another")
                }
                Vehicles.insert {
                    it[Vehicles.vehicleId] = vehicleId
                    it[Vehicles.ownerId] = ownerId
                    it[createdAt] = System.currentTimeMillis()
                    it[year] = body.year
                    it[make] = body.make
                    it[model] = body.model
                    it[variant] = body.variant
                    it[registrationMasked] = body.registrationMasked
                    it[seatCapacity] = body.seatCapacity
                    it[fuelType] = body.fuelType
                    it[vehicleType] = body.vehicleType
                    it[colour] = body.colour
                    it[updatedAt] = System.currentTimeMillis()
                }
            }
        } catch (error: ExposedSQLException) {
            if (error.cause?.message?.contains("UNIQUE constraint failed: vehicles.owner_id") == true) {
                throw ConflictException("You already have a registered vehicle")
            }
            throw error
        }
        val dto = readTransaction {
            Vehicles.selectAll().where { Vehicles.vehicleId eq vehicleId }.first().toVehicleDto()
        }
        call.respond(dto)
    }

    // Updates the driver's one vehicle in place (same vehicleId), rather than delete-and-recreate,
    // so any of the driver's existing rides keep pointing at the same (now-updated) vehicle.
    put("/vehicles/{id}") {
        val ownerId = call.requireUserId()
        val vehicleId = call.parameters["id"] ?: throw NotFoundException()
        val input = call.receive<UpdateVehicleRequest>()
        val body = CreateVehicleRequest(input.year, input.make, input.model, input.variant,
            input.registrationMasked, input.seatCapacity, input.fuelType, input.vehicleType, input.colour).normalized()

        transaction {
            val vehicle = Vehicles.selectAll().where { Vehicles.vehicleId eq vehicleId }.firstOrNull()
                ?: throw NotFoundException("Vehicle not found")
            if (vehicle[Vehicles.ownerId] != ownerId) throw ForbiddenException("You don't own this vehicle")

            val attached = Rides.selectAll().where { Rides.vehicleId eq vehicleId }.toList()
            if (attached.any { it[Rides.status] in setOf(RideStatus.OPEN, RideStatus.CONFIRMED) && it[Rides.seatsTotal] > body.seatCapacity - 1 }) {
                throw ConflictException("This capacity is too small for an active ride you have already offered")
            }
            if (!vehicle[Vehicles.fuelType].equals(body.fuelType, ignoreCase = true) && attached.any { it[Rides.status] == RideStatus.COMPLETED }) {
                throw ConflictException("Fuel type cannot change after completed trips because it determines your trip estimates")
            }

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
                it[updatedAt] = System.currentTimeMillis()
            }
        }
        val dto = readTransaction {
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
