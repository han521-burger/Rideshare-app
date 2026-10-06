package com.example.ds04_monash_rideshare_application.domain

import com.example.ds04_monash_rideshare_application.core.demand.DemandPalette
import com.example.ds04_monash_rideshare_application.core.demand.DemandPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Domain logic (pure JVM): how intensity becomes circle size and colour, and which hubs get a circle.
class DemandPaletteTest {
    private fun alpha(argb: Int) = (argb ushr 24) / 255.0
    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF

    @Test fun radiusGrowsWithIntensityWithinFixedBounds() {
        assertEquals(DemandPalette.MIN_RADIUS_METERS, DemandPalette.radiusMeters(0.0), 1e-9)
        assertEquals(DemandPalette.MAX_RADIUS_METERS, DemandPalette.radiusMeters(1.0), 1e-9)
        val radii = (0..10).map { DemandPalette.radiusMeters(it / 10.0) }
        assertEquals(radii.sorted(), radii)
        assertEquals(DemandPalette.radiusMeters(1.0), DemandPalette.radiusMeters(7.0), 0.0)
        assertEquals(DemandPalette.radiusMeters(0.0), DemandPalette.radiusMeters(-3.0), 0.0)
    }

    @Test fun opacityGrowsWithIntensityButNeverHidesTheMap() {
        val fills = (0..10).map { alpha(DemandPalette.fillArgb(it / 10.0)) }
        assertEquals(fills.sorted(), fills)
        assertTrue("max fill alpha ${fills.max()}", fills.max() <= 0.55)
        assertTrue("min fill alpha ${fills.min()}", fills.min() >= 0.1)
        assertTrue((0..10).all { alpha(DemandPalette.strokeArgb(it / 10.0)) <= 0.71 })
    }

    @Test fun colourMovesFromCoolBlueToHotRed() {
        val low = DemandPalette.fillArgb(0.0); val high = DemandPalette.fillArgb(1.0)
        assertTrue("low is blue-dominant", blue(low) > red(low))
        assertTrue("high is red-dominant", red(high) > blue(high))
        assertTrue(red(DemandPalette.fillArgb(0.75)) > red(DemandPalette.fillArgb(0.25)))
    }

    @Test fun onlyHubsWithDemandGetAZone() {
        fun point(name: String, raw: Double, intensity: Double) = DemandPoint(name, -37.9, 145.1, false, raw, intensity, 1)
        val zones = DemandPalette.visibleZones(listOf(point("a", 5.0, 1.0), point("b", 0.0, 0.0), point("c", 2.0, 0.6)))
        assertEquals(listOf("a", "c"), zones.map { it.hubName })
        assertTrue(DemandPalette.visibleZones(emptyList()).isEmpty())
    }
}
