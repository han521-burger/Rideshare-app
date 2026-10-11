package com.example.ds04_monash_rideshare_application.core.demand

import com.google.gson.Gson
import java.time.LocalDate

// PREDICTED demand, from the forecasting model in analysis/demand_forecast.ipynb. The notebook trains on the demand
// history, picks the model with the lowest Poisson deviance on held-out days, and exports the expected number of
// requests per hub for each of the 8 time buckets to demand_forecast.json (a Java resource, so it loads the same way
// in the app and in JVM tests). See docs/DEMAND_FORECAST.md.

// The file as the notebook writes it.
data class DemandForecast(
    val model: String,
    val trainedOn: TrainedOn,
    val dataProvenance: String,
    val evaluation: Evaluation,
    val forecasts: List<Entry>
) {
    data class TrainedOn(val from: String, val to: String, val days: Int, val requests: Int)
    data class Evaluation(
        val testDays: Int,
        val poissonDeviance: Double,
        val baseline: String,
        val baselinePoissonDeviance: Double,
        val improvementOverBaseline: Double,
        val top3HitRate: Double,
        val rankCorrelation: Double
    )
    // Expected requests at `hub` in `block` on one weekday (or one weekend day).
    data class Entry(val hub: String, val weekend: Boolean, val block: String, val expectedRequests: Double)

    companion object {
        const val RESOURCE = "demand_forecast.json"

        // Null when the file is missing or unusable - the app then shows past demand only.
        fun load(json: String?): DemandForecast? = try {
            json?.let { Gson().fromJson(it, DemandForecast::class.java) }?.takeIf { it.isUsable() }
        } catch (e: RuntimeException) {
            null
        }

        fun fromResource(name: String = RESOURCE): DemandForecast? =
            DemandForecast::class.java.classLoader?.getResourceAsStream(name)?.bufferedReader(Charsets.UTF_8)?.use { load(it.readText()) }
    }

    // Gson leaves fields the file lacks as null whatever Kotlin says, so check what the app relies on.
    @Suppress("SENSELESS_COMPARISON")
    private fun isUsable(): Boolean =
        model != null && trainedOn != null && evaluation != null && forecasts != null && forecasts.isNotEmpty() &&
            forecasts.all { it.hub != null && it.block != null && DayBlock.entries.any { b -> b.name == it.block } && it.expectedRequests.isFinite() }
}

// Each forecast becomes one DemandEvent inside its bucket, weighted by the expected requests, so DemandAggregator's
// "weighted sum per hub in the period" gives exactly the predicted number and the rest of the pipeline is unchanged.
class ForecastDemandDataSource(val forecast: DemandForecast) : DemandDataSource {
    override val provenance = DemandProvenance.FORECAST
    override val disclosure: String =
        "Forecast · ${forecast.model} · trained on " + (if (forecast.dataProvenance == "synthetic") "synthetic history" else "request history")

    private val events: List<DemandEvent> by lazy {
        forecast.forecasts.filter { it.expectedRequests > 0.0 }.map { entry ->
            val block = DayBlock.valueOf(entry.block)
            val day = if (entry.weekend) REFERENCE_SATURDAY else REFERENCE_WEDNESDAY
            DemandEvent(entry.hub, day.atTime(block.startHour, 30).atZone(DEMAND_ZONE).toInstant().toEpochMilli(), entry.expectedRequests)
        }
    }

    override fun events(): List<DemandEvent> = events

    private companion object {
        // Any weekday / weekend day works: only the bucket of the timestamp matters to the aggregator.
        val REFERENCE_WEDNESDAY: LocalDate = LocalDate.of(2026, 9, 30)
        val REFERENCE_SATURDAY: LocalDate = LocalDate.of(2026, 10, 3)
    }
}
