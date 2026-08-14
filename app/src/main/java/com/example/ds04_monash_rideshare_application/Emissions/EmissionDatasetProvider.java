package com.example.ds04_monash_rideshare_application.Emissions;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

//Static fallback emissions dataset (proposal Option C), used when GreenVehicleGuide has no live API.
//Combined-cycle CO2/fuel-consumption figures were read individually off public greenvehicleguide.gov.au
//vehicle pages on 2026-08-15 (each row's "source" cites the exact page). Category-average fallbacks are
//from the National Transport Commission's "Light Vehicle Emissions Intensity in Australia" 2021 report.
//Spot-check figures against the source pages before relying on them for the final report - trims vary,
//and this table only keeps one representative variant per model.
public class EmissionDatasetProvider {

    public static final List<VehicleEmissionProfile> PROFILES = Collections.unmodifiableList(Arrays.asList(
            new VehicleEmissionProfile("Toyota", "Corolla", "Hybrid", "Small Car",
                    113, 3.9, "greenvehicleguide.gov.au vehicleDisplayId=34346 (2022 Corolla Sedan Hybrid)"),
            new VehicleEmissionProfile("Hyundai", "i30", "Petrol", "Small Car",
                    159, 5.3, "greenvehicleguide.gov.au vehicleDisplayId=31022 (2020 i30 Sedan)"),
            new VehicleEmissionProfile("Toyota", "Camry", "Hybrid", "Mid-size Sedan",
                    91, 4.0, "greenvehicleguide.gov.au vehicleDisplayId=34154 (2024 Camry Hybrid)"),
            new VehicleEmissionProfile("Tesla", "Model 3", "Electric", "Mid-size Sedan",
                    0, 0, "greenvehicleguide.gov.au vehicleDisplayId=33981 (2024 Model 3)"),
            new VehicleEmissionProfile("Toyota", "RAV4", "Petrol", "Mid-size SUV",
                    159, 8.9, "greenvehicleguide.gov.au vehicleDisplayId=31805 (2021 RAV4 Edge 4WD)"),
            new VehicleEmissionProfile("Mazda", "CX-5", "Petrol", "Mid-size SUV",
                    173, 7.4, "greenvehicleguide.gov.au vehicleDisplayId=37771 (2026 CX-5 AWD)"),
            new VehicleEmissionProfile("Mitsubishi", "Outlander", "PHEV", "Mid-size SUV",
                    192, 1.5, "greenvehicleguide.gov.au vehicleDisplayId=33771 (2024 Outlander PHEV GSR)"),
            new VehicleEmissionProfile("Ford", "Ranger", "Diesel", "Light Commercial",
                    207, 7.9, "greenvehicleguide.gov.au vehicleDisplayId=31931 (2022 Ranger XL 4x4)")
    ));

    //NTC "Light Vehicle Emissions Intensity in Australia" (2021): new passenger cars/light SUVs average.
    private static final double DEFAULT_PASSENGER_CO2_G_PER_KM = 156.0;
    //NTC 2021: heavy SUVs and light commercial vehicles average.
    private static final double DEFAULT_LIGHT_COMMERCIAL_CO2_G_PER_KM = 212.5;
    private static final String DEFAULT_SOURCE = "NTC Light Vehicle Emissions Intensity in Australia (2021) category average";

    public static VehicleEmissionProfile findExact(String make, String model) {
        for (VehicleEmissionProfile profile : PROFILES) {
            if (profile.make.equalsIgnoreCase(make) && profile.model.equalsIgnoreCase(model)) {
                return profile;
            }
        }
        return null;
    }

    public static VehicleEmissionProfile estimate(String make, String model, boolean isLightCommercial) {
        VehicleEmissionProfile exact = findExact(make, model);
        if (exact != null) {
            return exact;
        }
        double fallbackCo2 = isLightCommercial ? DEFAULT_LIGHT_COMMERCIAL_CO2_G_PER_KM : DEFAULT_PASSENGER_CO2_G_PER_KM;
        String category = isLightCommercial ? "Light Commercial" : "Passenger Car/Light SUV";
        return new VehicleEmissionProfile(make, model, "Unknown", category, fallbackCo2, 0, DEFAULT_SOURCE);
    }
}
