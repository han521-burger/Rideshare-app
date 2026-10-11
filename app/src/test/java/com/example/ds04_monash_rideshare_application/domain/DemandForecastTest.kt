package com.example.ds04_monash_rideshare_application.domain

import com.example.ds04_monash_rideshare_application.core.demand.DayBlock
import com.example.ds04_monash_rideshare_application.core.demand.DemandForecast
import com.example.ds04_monash_rideshare_application.core.demand.DemandModel
import com.example.ds04_monash_rideshare_application.core.demand.DemandPeriod
import com.example.ds04_monash_rideshare_application.core.demand.ForecastDemandDataSource
import com.example.ds04_monash_rideshare_application.ui.APPROVED_POINTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The forecast the notebook exports (analysis/demand_forecast.ipynb -> demand_forecast.json) and how the app reads it.
class DemandForecastTest {
    private val forecast = requireNotNull(DemandForecast.fromResource()) { "demand_forecast.json is missing - run analysis/demand_forecast.ipynb" }
    private val model = DemandModel(ForecastDemandDataSource(forecast))

    @Test fun theShippedForecastCoversEveryApprovedHubInEveryBucket() {
        val covered = forecast.forecasts.map { Triple(it.hub, it.weekend, it.block) }.toSet()
        assertEquals(forecast.forecasts.size, covered.size)
        APPROVED_POINTS.forEach { hub ->
            DemandPeriod.ALL.forEach { period -> assertTrue("${hub.name} ${period.label}", Triple(hub.name, period.weekend, period.block.name) in covered) }
        }
        assertTrue(forecast.forecasts.all { it.expectedRequests >= 0.0 })
    }

    @Test fun eachHubShowsExactlyTheExpectedRequestsForItsBucket() {
        DemandPeriod.ALL.forEach { period ->
            val expected = forecast.forecasts.filter { it.weekend == period.weekend && it.block == period.block.name }.associate { it.hub to it.expectedRequests }
            model.pointsFor(period).forEach { point -> assertEquals(point.hubName, expected.getValue(point.hubName), point.rawDemand, 1e-9) }
        }
    }

    @Test fun theForecastTellsTheCommuterStory() {
        // Campuses lead weekday mornings; the campus barely features on weekend evenings.
        val weekdayMorning = model.pointsFor(DemandPeriod(false, DayBlock.MORNING))
        assertEquals("Monash Clayton", weekdayMorning.first().hubName)
        val clayton = { period: DemandPeriod -> model.pointsFor(period).single { it.hubName == "Monash Clayton" }.rawDemand }
        assertTrue(clayton(DemandPeriod(false, DayBlock.MORNING)) > 5 * clayton(DemandPeriod(true, DayBlock.EVENING)))
    }

    @Test fun itIsLabelledAsAForecastAndRecordsHowItWasJudged() {
        assertTrue(model.isForecast)
        assertTrue(model.disclosure!!.startsWith("Forecast · "))
        assertTrue(model.disclosure!!.endsWith("trained on synthetic history"))
        assertTrue("beats its baseline", forecast.evaluation.poissonDeviance < forecast.evaluation.baselinePoissonDeviance)
    }

    @Test fun aMissingOrBrokenFileMeansNoForecastRatherThanACrash() {
        assertNull(DemandForecast.fromResource("no_such_forecast.json"))
        assertNull(DemandForecast.load(null))
        assertNull(DemandForecast.load("not json"))
        assertNull(DemandForecast.load("""{"model":"x"}"""))
        assertNull(DemandForecast.load("""{"model":"x","trainedOn":{"from":"a","to":"b","days":1,"requests":1},"dataProvenance":"synthetic",
            "evaluation":{"testDays":1,"poissonDeviance":1,"baseline":"b","baselinePoissonDeviance":1,"improvementOverBaseline":0,"top3HitRate":0,"rankCorrelation":0},
            "forecasts":[{"hub":"Monash Clayton","weekend":false,"block":"NIGHT","expectedRequests":1}]}"""))
    }
}
