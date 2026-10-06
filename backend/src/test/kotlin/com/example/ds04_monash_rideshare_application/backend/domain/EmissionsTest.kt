package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.Emissions
import kotlin.test.Test
import kotlin.test.assertEquals

// Domain logic: the environmental-impact formula, independent of any route or screen.
class EmissionsTest {
    private val tolerance = 1e-9

    @Test fun `co2 avoided is distance times the fuel factor times seats filled`() {
        // 10 km x 170 g/km x 1 seat = 1.7 kg
        assertEquals(1.7, Emissions.co2AvoidedKgForTrip(10_000.0, "PETROL", 1), tolerance)
        assertEquals(1.8, Emissions.co2AvoidedKgForTrip(10_000.0, "DIESEL", 1), tolerance)
        assertEquals(1.0, Emissions.co2AvoidedKgForTrip(10_000.0, "HYBRID", 1), tolerance)
        assertEquals(0.7, Emissions.co2AvoidedKgForTrip(10_000.0, "PHEV", 1), tolerance)
        assertEquals(0.2, Emissions.co2AvoidedKgForTrip(10_000.0, "ELECTRIC", 1), tolerance)
    }

    @Test fun `ten km at 170 g per km with two seats filled avoids 3 point 4 kg`() {
        // 10 km x 170 g/km = 1,700 g; x 2 seats = 3,400 g; / 1,000 = 3.4 kg
        assertEquals(3.4, Emissions.co2AvoidedKgForTrip(10_000.0, "PETROL", 2), tolerance)
    }

    @Test fun `every extra passenger seat adds the same amount again`() {
        val one = Emissions.co2AvoidedKgForTrip(7_400.0, "PETROL", 1)
        assertEquals(one * 3, Emissions.co2AvoidedKgForTrip(7_400.0, "PETROL", 3), tolerance)
    }

    @Test fun `fuel type is case insensitive and unknown or missing falls back to the petrol factor`() {
        val petrol = Emissions.co2AvoidedKgForTrip(10_000.0, "PETROL", 2)
        assertEquals(petrol, Emissions.co2AvoidedKgForTrip(10_000.0, "petrol", 2), tolerance)
        assertEquals(petrol, Emissions.co2AvoidedKgForTrip(10_000.0, null, 2), tolerance)
        assertEquals(petrol, Emissions.co2AvoidedKgForTrip(10_000.0, "HYDROGEN", 2), tolerance)
    }

    @Test fun `zero distance or zero seats saves nothing`() {
        assertEquals(0.0, Emissions.co2AvoidedKgForTrip(0.0, "PETROL", 3), tolerance)
        assertEquals(0.0, Emissions.co2AvoidedKgForTrip(10_000.0, "PETROL", 0), tolerance)
        assertEquals(0.0, Emissions.fuelSavedLitresForTrip(0.0, "PETROL", 3), tolerance)
        assertEquals(0.0, Emissions.fuelSavedLitresForTrip(10_000.0, "PETROL", 0), tolerance)
    }

    @Test fun `a cleaner fuel never avoids more co2 than a dirtier one over the same trip`() {
        val order = listOf("DIESEL", "PETROL", "HYBRID", "PHEV", "ELECTRIC")
            .map { Emissions.co2AvoidedKgForTrip(12_000.0, it, 2) }
        assertEquals(order.sortedDescending(), order)
    }

    @Test fun `fuel saved follows litres per 100 km and electric saves none`() {
        // 10 km at 7.5 L/100 km for one seat = 0.75 L
        assertEquals(0.75, Emissions.fuelSavedLitresForTrip(10_000.0, "PETROL", 1), tolerance)
        assertEquals(0.0, Emissions.fuelSavedLitresForTrip(10_000.0, "ELECTRIC", 3), tolerance)
        assertEquals(0.0, Emissions.fuelSavedLitresForTrip(10_000.0, "electric", 3), tolerance)
    }
}
