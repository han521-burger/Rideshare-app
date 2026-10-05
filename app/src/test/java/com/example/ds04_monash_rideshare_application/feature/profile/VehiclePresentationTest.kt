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

    @Test fun validationReportsOnlyTheFirstFailureAgainstItsField() {
        val blank = valid.copy(make = "", model = "", registrationMasked = "", colour = "", vehicleType = "", fuelType = "")
        assertEquals(VehicleField.MAKE, validateVehicleDetailed(blank)?.field)
        assertEquals(VehicleField.MODEL, validateVehicleDetailed(blank.copy(make = "Mazda"))?.field)
        assertEquals(VehicleField.PLATE, validateVehicleDetailed(blank.copy(make = "M", model = "3"))?.field)
        assertEquals(VehicleField.COLOUR, validateVehicleDetailed(blank.copy(make = "M", model = "3", registrationMasked = "A"))?.field)
        val noChoices = valid.copy(vehicleType = "", fuelType = "")
        assertEquals(VehicleField.TYPE, validateVehicleDetailed(noChoices)?.field)
        assertEquals(VehicleField.FUEL, validateVehicleDetailed(noChoices.copy(vehicleType = "SUV"))?.field)
        assertEquals(VehicleField.SEATS, validateVehicleDetailed(valid.copy(seatCapacity = 13))?.field)
        assertNull(validateVehicleDetailed(valid))
    }

    @Test fun validationMessagesAndLimitsAreUnchanged() {
        assertEquals("Enter a car brand (up to 64 characters).", validateVehicle(valid.copy(make = "")))
        assertEquals("Enter a car brand (up to 64 characters).", validateVehicle(valid.copy(make = "x".repeat(65))))
        assertNull(validateVehicle(valid.copy(make = "x".repeat(64))))
        assertEquals("Enter a registration plate (up to 16 characters).", validateVehicle(valid.copy(registrationMasked = "x".repeat(17))))
        assertEquals("Enter a colour (up to 32 characters).", validateVehicle(valid.copy(colour = "x".repeat(33))))
        assertEquals("Choose 2–12 total seats, including the driver.", validateVehicle(valid.copy(seatCapacity = 1)))
        assertEquals("Choose a vehicle type.", validateVehicle(valid.copy(vehicleType = "")))
        assertEquals("Choose a fuel type.", validateVehicle(valid.copy(fuelType = "")))
    }

    @Test fun seatBoundsDefaultAndPassengerCopy() {
        assertEquals(2, MIN_SEATS); assertEquals(12, MAX_SEATS); assertEquals(5, DEFAULT_SEATS)
        assertEquals("Up to 4 passenger seats can be offered.", passengerSeatsText(DEFAULT_SEATS))
        assertEquals("Up to 1 passenger seats can be offered.", passengerSeatsText(MIN_SEATS))
        assertEquals("Up to 11 passenger seats can be offered.", passengerSeatsText(MAX_SEATS))
    }

    @Test fun choiceOptionsAreTheSourceBackedLists() {
        assertEquals(listOf("SEDAN", "HATCHBACK", "SUV", "WAGON", "UTE", "VAN", "COUPE", "OTHER"), vehicleTypes)
        assertEquals(listOf("PETROL", "DIESEL", "HYBRID", "PHEV", "ELECTRIC"), fuelTypes)
        assertEquals(listOf("Sedan", "Hatchback", "SUV", "Wagon", "Ute", "Van", "Coupe", "Other"), vehicleTypes.map(::vehicleLabel))
        assertEquals(listOf("Petrol", "Diesel", "Hybrid", "Plug-in Hybrid", "Electric"), fuelTypes.map(::vehicleLabel))
    }

    @Test fun structuredFactsUseOnlySourceData() {
        val facts = vehicleFacts(VehicleSummary("id", 0, "Mazda", "CX-5", null, "3AA·BB4", "PHEV", 4, "SEDAN", "White"))
        assertEquals(VehicleFacts("Mazda CX-5", "3AA·BB4", "Sedan", "White", "4 total", "3 for passengers", "Plug-in Hybrid"), facts)
        assertEquals("Not recorded", vehicleFacts(VehicleSummary("id", 0, "A", "B", null, null, "PETROL", 2, "VAN", "Red")).plate)
    }

    @Test fun introDependsOnVehicleAndHowTheUserArrived() {
        assertEquals(VehicleIntro.DRIVER_GATE, vehicleIntro(hasVehicle = false, driverSetup = true))
        assertEquals(VehicleIntro.NEUTRAL, vehicleIntro(hasVehicle = false, driverSetup = false))
        assertEquals(VehicleIntro.NONE, vehicleIntro(hasVehicle = true, driverSetup = false))
        assertEquals(VehicleIntro.NONE, vehicleIntro(hasVehicle = true, driverSetup = true))
    }

    @Test fun driverHandOffShowsOnlyWhileTheDriverSwitchIsRunningAfterRegistration() {
        assertTrue(showDriverHandOff(driverSetup = true, driverChecking = true, hasVehicle = true, editing = false))
        assertFalse(showDriverHandOff(driverSetup = true, driverChecking = false, hasVehicle = true, editing = false))
        assertFalse(showDriverHandOff(driverSetup = false, driverChecking = true, hasVehicle = true, editing = false))
        assertFalse(showDriverHandOff(driverSetup = true, driverChecking = true, hasVehicle = false, editing = true))
    }

    @Test fun titlesAndActionLabelsFollowTheApprovedCopy() {
        assertEquals("Register your vehicle", vehicleScreenTitle(false)); assertEquals("My vehicle", vehicleScreenTitle(true))
        assertEquals("Register vehicle", vehiclePrimaryLabel(false)); assertEquals("Save changes", vehiclePrimaryLabel(true))
        assertEquals("Registering…", vehicleBusyLabel(false)); assertEquals("Saving…", vehicleBusyLabel(true))
    }
}
