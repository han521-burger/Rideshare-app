package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.CreateVehicleRequest
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.normalized
import com.example.ds04_monash_rideshare_application.backend.requireDriverVehicle
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Domain logic: vehicle validation and the "passenger seats = total seats - the driver's" rule.
class SeatAndVehicleRulesTest {
    private fun vehicle(seats: Int = 5, fuel: String = "petrol ", type: String = "sedan") = CreateVehicleRequest(
        year = 2020, make = " Toyota ", model = "Corolla", registrationMasked = "abc123",
        seatCapacity = seats, fuelType = fuel, vehicleType = type, colour = "Blue"
    )

    @Test fun `capacity includes the driver and must be between 2 and 12`() {
        listOf(2, 5, 12).forEach { vehicle(it).normalized() }
        listOf(-1, 0, 1, 13).forEach { assertFailsWith<ValidationException>("capacity $it") { vehicle(it).normalized() } }
    }

    @Test fun `fuel and body type are normalised and limited to the supported lists`() {
        val clean = vehicle().normalized()
        assertEquals("PETROL", clean.fuelType)
        assertEquals("SEDAN", clean.vehicleType)
        assertEquals("Toyota", clean.make)
        assertEquals("ABC123", clean.registrationMasked)
        listOf("PETROL", "DIESEL", "HYBRID", "PHEV", "ELECTRIC").forEach { vehicle(fuel = it).normalized() }
        assertFailsWith<ValidationException> { vehicle(fuel = "WATER").normalized() }
        assertFailsWith<ValidationException> { vehicle(type = "FLYING").normalized() }
    }

    @Test fun `passenger capacity is total seats minus one`() {
        openTempDatabase()
        addUser("seat-driver"); addVehicle("seat-driver", seats = 5)
        transaction {
            (1..4).forEach { requireDriverVehicle("seat-driver", it) }
            listOf(0, 5, 6, -1).forEach { passengers ->
                assertFailsWith<ValidationException>("$passengers passenger seats") { requireDriverVehicle("seat-driver", passengers) }
            }
        }
    }

    @Test fun `a two seat car offers exactly one passenger seat`() {
        openTempDatabase()
        addUser("small-driver"); addVehicle("small-driver", seats = 2)
        transaction {
            requireDriverVehicle("small-driver", 1)
            assertFailsWith<ValidationException> { requireDriverVehicle("small-driver", 2) }
        }
    }

    @Test fun `offering without a registered vehicle is refused`() {
        openTempDatabase()
        addUser("no-car")
        transaction { assertFailsWith<ValidationException> { requireDriverVehicle("no-car", 1) } }
    }
}
