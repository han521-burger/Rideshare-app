package com.example.ds04_monash_rideshare_application.feature.profile

import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateVehicleDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.VehicleSummary
import org.junit.Assert.*
import org.junit.Test

class VehiclePresentationTest {
    private val valid = CreateVehicleDraft(0,"Toyota","Corolla",null,"ABC123",5,"PETROL","SEDAN","White")
    @Test fun requiredFieldsCannotBeBlank() {
        for (draft in listOf(valid.copy(make=" "),valid.copy(model=" "),valid.copy(registrationMasked=" "),valid.copy(colour=" "))) assertNotNull(validateVehicle(draft))
        assertNull(validateVehicle(valid))
    }
    @Test fun capacityIncludesDriverAndIsBounded() {
        for (n in listOf(0,1,13)) assertNotNull(validateVehicle(valid.copy(seatCapacity=n)))
        for (n in listOf(2,5,12)) assertNull(validateVehicle(valid.copy(seatCapacity=n)))
        assertEquals(4,valid.seatCapacity-1)
    }
    @Test fun onlyControlledFuelAndVehicleCategoriesAreAccepted() {
        assertNotNull(validateVehicle(valid.copy(fuelType="")))
        assertNotNull(validateVehicle(valid.copy(vehicleType="Flying")))
        for(fuel in fuelTypes) assertNull(validateVehicle(valid.copy(fuelType=fuel)))
    }
    @Test fun summaryContainsPassengerIdentificationAndFuel() {
        val vehicle = VehicleSummary("id",0,"Toyota","Corolla",null,"ABC123","PETROL",5,"SEDAN","White")
        val summary = vehicleSummary(vehicle)
        for (text in listOf("Toyota Corolla","White Sedan","ABC123","5 total seats","Petrol")) assertTrue(summary.contains(text))
    }
    @Test fun labelsAreReadableWithoutEmissionFactors() {
        assertEquals("Plug-in Hybrid",vehicleLabel("PHEV")); assertEquals("SUV",vehicleLabel("SUV"))
    }
}
