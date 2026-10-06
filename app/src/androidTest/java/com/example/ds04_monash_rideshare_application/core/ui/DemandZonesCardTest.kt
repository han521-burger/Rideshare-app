package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.example.ds04_monash_rideshare_application.core.demand.DEMAND_ZONE
import com.example.ds04_monash_rideshare_application.core.demand.DemandDataSource
import com.example.ds04_monash_rideshare_application.core.demand.DemandEvent
import com.example.ds04_monash_rideshare_application.core.demand.DemandModel
import com.example.ds04_monash_rideshare_application.core.demand.DemandPalette
import com.example.ds04_monash_rideshare_application.core.demand.DemandProvenance
import com.example.ds04_monash_rideshare_application.ui.FIXED_POINTS
import com.example.ds04_monash_rideshare_application.ui.MONASH_CAMPUSES
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

// The map itself is a slot here (no Yandex MapKit in these tests); the slot reports what the card
// hands to the map so we can assert the demand layer's state without pixel checks.
class DemandZonesCardTest {
    @get:Rule val compose = createComposeRule()

    // Monday 08:30 Melbourne time.
    private val monday830 = LocalDateTime.of(2026, 9, 28, 8, 30).atZone(DEMAND_ZONE).toInstant().toEpochMilli()
    private val clayton = MONASH_CAMPUSES.single { it.name == "Monash Clayton" }
    private val huntingdale = FIXED_POINTS.single { it.name == "Huntingdale Station" }

    private fun source(provenance: DemandProvenance, events: List<DemandEvent>) = object : DemandDataSource {
        override val provenance = provenance
        override fun events() = events
    }
    private fun morning(hub: String, count: Int) = List(count) { DemandEvent(hub, monday830 + it * 60_000L) }
    private val busy = source(DemandProvenance.SYNTHETIC, morning("Monash Clayton", 6) + morning("Huntingdale Station", 3))

    private fun show(
        model: DemandModel, scale: Float = 1f,
        markers: List<com.example.ds04_monash_rideshare_application.ui.NamedLocation> = emptyList()
    ) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                        DemandZonesCard(campus = clayton, markers = markers, model = model, nowMillis = monday830) { points, showZones, campus, pins ->
                            val zones = DemandPalette.visibleZones(points).size
                            Text(
                                "zones=${if (showZones) zones else 0} markers=${pins.map { it.name }} campus=${campus?.name}",
                                androidx.compose.ui.Modifier.testTag("map-slot")
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun showsZonesByDefaultWithTheRightBucketAndBusiestHubs() {
        show(DemandModel(busy))
        compose.onNodeWithTag("demand-zones-card").assertIsDisplayed()
        compose.onNodeWithTag("demand-period").assertIsDisplayed()
        compose.onNodeWithText("Weekday morning").assertIsDisplayed()
        compose.onNodeWithTag("demand-toggle").assertIsOn()
        compose.onNodeWithTag("demand-legend").assertIsDisplayed()
        compose.onNodeWithText("Busiest now: 1. Monash Clayton · 2. Huntingdale Station").assertIsDisplayed()
        compose.onNodeWithTag("map-slot").assertTextEquals("zones=2 markers=[] campus=Monash Clayton")
    }

    @Test fun syntheticDataIsDisclosed() {
        show(DemandModel(busy))
        compose.onNodeWithTag("demand-disclosure").assertIsDisplayed()
        compose.onNodeWithText("Prototype demand · synthetic data").assertIsDisplayed()
    }

    @Test fun genuineDataIsNotLabelledSynthetic() {
        show(DemandModel(source(DemandProvenance.OBSERVED, morning("Monash Clayton", 2))))
        compose.onNodeWithText("Prototype demand · synthetic data").assertDoesNotExist()
        compose.onNodeWithTag("demand-disclosure").assertDoesNotExist()
    }

    @Test fun turningDemandOffRemovesTheLayerAndLegendAndBackOnRestoresThem() {
        show(DemandModel(busy))
        compose.onNodeWithTag("demand-toggle").performClick()
        compose.onNodeWithTag("demand-toggle").assertIsOff()
        compose.onNodeWithTag("map-slot").assertTextEquals("zones=0 markers=[] campus=Monash Clayton")
        compose.onNodeWithTag("demand-legend").assertDoesNotExist()
        compose.onNodeWithTag("demand-top").assertDoesNotExist()
        compose.onNodeWithTag("demand-disclosure").assertIsDisplayed()   // the data is still described

        compose.onNodeWithTag("demand-toggle").performClick()
        compose.onNodeWithTag("demand-toggle").assertIsOn()
        compose.onNodeWithTag("map-slot").assertTextEquals("zones=2 markers=[] campus=Monash Clayton")
        compose.onNodeWithTag("demand-legend").assertIsDisplayed()
    }

    @Test fun routeMarkersReachTheMapUnchangedWhetherOrNotZonesAreShown() {
        show(DemandModel(busy), markers = listOf(huntingdale, clayton))
        compose.onNodeWithTag("map-slot").assertTextEquals("zones=2 markers=[Huntingdale Station, Monash Clayton] campus=Monash Clayton")
        compose.onNodeWithTag("demand-toggle").performClick()
        compose.onNodeWithTag("map-slot").assertTextEquals("zones=0 markers=[Huntingdale Station, Monash Clayton] campus=Monash Clayton")
    }

    @Test fun anEmptyDemandHistoryDoesNotBreakTheCard() {
        show(DemandModel(source(DemandProvenance.SYNTHETIC, emptyList())))
        compose.onNodeWithText("No demand recorded for this time of day yet.").assertIsDisplayed()
        compose.onNodeWithTag("map-slot").assertTextEquals("zones=0 markers=[] campus=Monash Clayton")
        compose.onNodeWithTag("demand-toggle").performClick()
        compose.onNodeWithTag("demand-toggle").assertIsOff()
    }

    @Test fun controlsStayUsableAtTwiceTheTextSize() {
        show(DemandModel(busy), scale = 2f)
        compose.onNodeWithTag("demand-toggle").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("demand-legend").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("demand-disclosure").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("demand-toggle").performClick()
        compose.onNodeWithTag("demand-toggle").assertIsOff()
    }

    @Test fun theToggleIsAtLeast48dpTall() {
        show(DemandModel(busy))
        val height = compose.onNodeWithTag("demand-toggle").fetchSemanticsNode().size.height
        val density = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("toggle is ${height / density} dp", height / density >= 47.5f)
    }
}
