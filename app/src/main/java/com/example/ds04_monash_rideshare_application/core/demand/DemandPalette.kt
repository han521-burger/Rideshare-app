package com.example.ds04_monash_rideshare_application.core.demand

import kotlin.math.roundToInt

// How a normalised intensity (0..1) looks on the map. Pure ARGB maths (no Android classes) so the
// mapping is unit-testable. Low demand is a faint cool blue, medium amber, high a stronger red;
// opacity stays well under 0.6 so roads, route pins and approved-point markers show through.
object DemandPalette {
    const val MIN_RADIUS_METERS = 350.0
    const val MAX_RADIUS_METERS = 1_300.0
    private const val MIN_ALPHA = 0.14
    private const val MAX_ALPHA = 0.42
    private const val STROKE_ALPHA_BOOST = 0.20

    private val COOL = intArrayOf(0x3B, 0x82, 0xC4)   // blue
    private val WARM = intArrayOf(0xF2, 0xA3, 0x3A)   // amber
    private val HOT = intArrayOf(0xD9, 0x3A, 0x2B)    // red

    fun radiusMeters(intensity: Double): Double =
        MIN_RADIUS_METERS + (MAX_RADIUS_METERS - MIN_RADIUS_METERS) * intensity.coerceIn(0.0, 1.0)

    fun fillArgb(intensity: Double): Int = argb(intensity, MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * intensity.coerceIn(0.0, 1.0))

    fun strokeArgb(intensity: Double): Int =
        argb(intensity, (MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * intensity.coerceIn(0.0, 1.0) + STROKE_ALPHA_BOOST).coerceAtMost(0.70))

    // Only hubs that actually have demand get a zone; a zero-demand hub would just be visual noise.
    fun visibleZones(points: List<DemandPoint>): List<DemandPoint> = points.filter { it.rawDemand > 0.0 && it.normalizedIntensity > 0.0 }

    private fun argb(intensity: Double, alpha: Double): Int {
        val t = intensity.coerceIn(0.0, 1.0)
        val (from, to, u) = if (t < 0.5) Triple(COOL, WARM, t / 0.5) else Triple(WARM, HOT, (t - 0.5) / 0.5)
        fun channel(i: Int) = (from[i] + (to[i] - from[i]) * u).roundToInt()
        return ((alpha * 255).roundToInt() shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }
}
