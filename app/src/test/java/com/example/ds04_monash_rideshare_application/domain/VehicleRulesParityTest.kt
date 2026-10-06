package com.example.ds04_monash_rideshare_application.domain

import com.example.ds04_monash_rideshare_application.feature.profile.MAX_SEATS
import com.example.ds04_monash_rideshare_application.feature.profile.MIN_SEATS
import com.example.ds04_monash_rideshare_application.feature.profile.fuelTypes
import com.example.ds04_monash_rideshare_application.feature.profile.passengerSeatsText
import com.example.ds04_monash_rideshare_application.feature.profile.vehicleTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Domain logic (pure JVM): the app's vehicle choices must stay in step with the rules the backend
// enforces and the emissions factors it uses, or a driver could register a car the server rejects
// or whose fuel type has no CO2 factor. The backend source is parsed (the modules can't import each
// other); Gradle runs unit tests with the module (app/) as working directory.
class VehicleRulesParityTest {
    private val backend = File("../backend/src/main/kotlin/com/example/ds04_monash_rideshare_application/backend")

    private fun source(name: String): String {
        val file = File(backend, name)
        assertTrue("backend source not found at ${file.absolutePath}", file.exists())
        return file.readText()
    }

    private fun quotedSet(source: String, declaration: String): List<String> {
        val line = source.lines().single { declaration in it }
        return Regex("\"([A-Z]+)\"").findAll(line).map { it.groupValues[1] }.toList()
    }

    @Test fun fuelAndBodyTypesMatchTheBackendLists() {
        val policy = source("VehiclePolicy.kt")
        assertEquals(fuelTypes.sorted(), quotedSet(policy, "val vehicleFuelTypes").sorted())
        assertEquals(vehicleTypes.sorted(), quotedSet(policy, "val vehicleTypes").sorted())
    }

    @Test fun seatLimitsMatchTheBackendRange() {
        val policy = source("VehiclePolicy.kt")
        assertTrue("backend capacity rule changed: re-check MIN_SEATS/MAX_SEATS", "seatCapacity !in $MIN_SEATS..$MAX_SEATS" in policy)
    }

    @Test fun everyFuelTypeTheAppOffersHasAnEmissionsFactor() {
        val emissions = source("Emissions.kt")
        val factors = Regex("\"([A-Z]+)\" to ([0-9.]+)").findAll(emissions).map { it.groupValues[1] }.toSet()
        fuelTypes.forEach { assertTrue("$it has no CO2 factor in Emissions.kt", it in factors) }
    }

    @Test fun passengerSeatsAreAlwaysTotalSeatsMinusTheDriver() {
        (MIN_SEATS..MAX_SEATS).forEach { total ->
            assertEquals("Up to ${total - 1} passenger seats can be offered.", passengerSeatsText(total))
            assertTrue(total - 1 >= 1)
        }
    }
}
