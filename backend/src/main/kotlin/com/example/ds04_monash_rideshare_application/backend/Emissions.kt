package com.example.ds04_monash_rideshare_application.backend

// CO2 estimate = distance x an emissions factor. This is explicitly an ESTIMATE (item 8), not a
// measured figure - fuel type is used when known, otherwise a fleet-average petrol factor is
// used as a documented fallback (mirrors the Android-side EmissionDatasetProvider's approach of
// falling back to a category default when there's no exact vehicle match).
object Emissions {
    // Grams of CO2 per km, by fuel type. Rough Australian fleet-average figures for a trial;
    // not sourced from an authoritative dataset - clearly an estimate, not a measurement.
    private val CO2_G_PER_KM: Map<String, Double> = mapOf(
        "PETROL" to 170.0,
        "DIESEL" to 180.0,
        "HYBRID" to 100.0,
        "PHEV" to 70.0,
        "ELECTRIC" to 20.0
    )
    private const val DEFAULT_CO2_G_PER_KM = 170.0

    // Litres/100km, by fuel type - same fleet-average-estimate caveat as CO2_G_PER_KM. Electric
    // has no fuel to save in the litres sense, so it's excluded from the map (treated as 0 below).
    private val LITRES_PER_100KM: Map<String, Double> = mapOf(
        "PETROL" to 7.5,
        "DIESEL" to 6.5,
        "HYBRID" to 4.2,
        "PHEV" to 2.5
    )
    private const val DEFAULT_LITRES_PER_100KM = 7.5

    // A solo drive avoided, per passenger seat filled - the whole point of the dashboard stat.
    fun co2AvoidedKgForTrip(distanceMeters: Double, fuelType: String?, seatsFilled: Int): Double {
        val factor = fuelType?.uppercase()?.let { CO2_G_PER_KM[it] } ?: DEFAULT_CO2_G_PER_KM
        val distanceKm = distanceMeters / 1000.0
        val totalCo2Grams = distanceKm * factor
        return (totalCo2Grams * seatsFilled) / 1000.0
    }

    fun fuelSavedLitresForTrip(distanceMeters: Double, fuelType: String?, seatsFilled: Int): Double {
        val normalizedType = fuelType?.uppercase()
        if (normalizedType == "ELECTRIC") return 0.0
        val litresPer100Km = normalizedType?.let { LITRES_PER_100KM[it] } ?: DEFAULT_LITRES_PER_100KM
        val distanceKm = distanceMeters / 1000.0
        return (distanceKm / 100.0) * litresPer100Km * seatsFilled
    }
}
