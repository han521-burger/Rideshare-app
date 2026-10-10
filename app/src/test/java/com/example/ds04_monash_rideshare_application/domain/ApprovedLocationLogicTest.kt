package com.example.ds04_monash_rideshare_application.domain

import com.example.ds04_monash_rideshare_application.core.location.nearestApprovedPoints
import com.example.ds04_monash_rideshare_application.ui.APPROVED_POINTS
import com.example.ds04_monash_rideshare_application.ui.FIXED_POINTS
import com.example.ds04_monash_rideshare_application.ui.MONASH_CAMPUSES
import com.example.ds04_monash_rideshare_application.ui.NAMED_LOCATIONS
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import com.example.ds04_monash_rideshare_application.ui.haversineMeters
import com.example.ds04_monash_rideshare_application.ui.nearestFixedPoint
import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Domain logic (pure JVM, no emulator): the approved-location dataset, distance maths and
// nearest-hub ranking. ApprovedPointSearchTest covers the search plumbing; this covers the data
// and the guarantees the ranking must keep.
class ApprovedLocationLogicTest {
    private fun NamedLocation.lat() = point.latitude
    private fun NamedLocation.lng() = point.longitude

    // --- dataset ---------------------------------------------------------------------------

    @Test fun datasetHas25HubsAnd5Campuses() {
        assertEquals(25, FIXED_POINTS.size)
        assertEquals(5, MONASH_CAMPUSES.size)
        assertEquals(30, APPROVED_POINTS.size)
        assertEquals(30, NAMED_LOCATIONS.size)
    }

    @Test fun namesAndCoordinatesAreUnique() {
        assertEquals(APPROVED_POINTS.size, APPROVED_POINTS.map { it.name }.toSet().size)
        assertEquals(APPROVED_POINTS.size, APPROVED_POINTS.map { it.lat() to it.lng() }.toSet().size)
    }

    @Test fun everyCoordinateIsValidAndInGreaterMelbourneOrThePeninsula() {
        APPROVED_POINTS.forEach {
            assertTrue("${it.name} latitude", it.lat() in -38.30..-37.70)
            assertTrue("${it.name} longitude", it.lng() in 144.80..145.30)
        }
    }

    @Test fun onlyCampusesAreFlaggedAsCampuses() {
        assertTrue(MONASH_CAMPUSES.all { it.isCampus })
        assertTrue(FIXED_POINTS.none { it.isCampus })
        assertEquals(5, APPROVED_POINTS.count { it.isCampus })
        assertTrue(MONASH_CAMPUSES.all { it.name.startsWith("Monash ") })
    }

    // The two lists are "kept in sync by hand" with the backend (which validates every trip), so
    // a drifting copy would let the app offer a place the server can't name. The backend's single list of
    // approved points (ApprovedPoints.kt, which NamedPoints.kt and MonashCampuses.kt are now views of) is
    // parsed and compared. Gradle runs unit tests with the module (app/) as working directory.
    // (ApprovedPointsParityTest checks the same list for ids, campus flags and set equality.)
    @Test fun appHubsAndCampusesMatchTheBackendDataset() {
        val file = File("../backend/src/main/kotlin/com/example/ds04_monash_rideshare_application/backend/ApprovedPoints.kt")
        assertTrue("backend source not found at ${file.absolutePath}", file.exists())
        val point = Regex("""ApprovedPoint\("[^"]+", "([^"]+)", (-?[0-9.]+), (-?[0-9.]+), isCampus = (true|false)\)""")
        val all = point.findAll(file.readText()).map { Pair(Triple(it.groupValues[1], it.groupValues[2].toDouble(), it.groupValues[3].toDouble()), it.groupValues[4].toBoolean()) }.toList()
        val backendHubs = all.filter { !it.second }.map { it.first }
        val backendCampuses = all.filter { it.second }.map { it.first }
        assertEquals(FIXED_POINTS.map { Triple(it.name, it.lat(), it.lng()) }, backendHubs)
        assertEquals(MONASH_CAMPUSES.map { Triple(it.name, it.lat(), it.lng()) }, backendCampuses)
    }

    // --- distance --------------------------------------------------------------------------

    @Test fun distanceIsZeroForTheSamePointAndSymmetric() {
        val a = MONASH_CAMPUSES[0]; val b = FIXED_POINTS[5]
        assertEquals(0.0, haversineMeters(a.lat(), a.lng(), a.lat(), a.lng()), 1e-6)
        assertEquals(haversineMeters(a.lat(), a.lng(), b.lat(), b.lng()), haversineMeters(b.lat(), b.lng(), a.lat(), a.lng()), 1e-6)
    }

    @Test fun knownDistancesAreReasonable() {
        val clayton = MONASH_CAMPUSES.single { it.name == "Monash Clayton" }
        val caulfield = MONASH_CAMPUSES.single { it.name == "Monash Caulfield" }
        val peninsula = MONASH_CAMPUSES.single { it.name == "Monash Peninsula" }
        assertEquals(8.8, haversineMeters(clayton.lat(), clayton.lng(), caulfield.lat(), caulfield.lng()) / 1000, 0.3)
        // Clayton to Peninsula is roughly 27 km as the crow flies.
        assertEquals(27.0, haversineMeters(clayton.lat(), clayton.lng(), peninsula.lat(), peninsula.lng()) / 1000, 1.5)
        // One degree of latitude is ~111 km everywhere.
        assertEquals(111.2, haversineMeters(-38.0, 145.0, -37.0, 145.0) / 1000, 0.3)
    }

    @Test fun triangleInequalityHoldsAcrossTheDataset() {
        val a = MONASH_CAMPUSES[0]; val b = FIXED_POINTS[3]; val c = FIXED_POINTS[20]
        val ab = haversineMeters(a.lat(), a.lng(), b.lat(), b.lng())
        val bc = haversineMeters(b.lat(), b.lng(), c.lat(), c.lng())
        val ac = haversineMeters(a.lat(), a.lng(), c.lat(), c.lng())
        assertTrue(ac <= ab + bc + 1e-6)
    }

    // --- nearest hubs ----------------------------------------------------------------------

    @Test fun everyHubIsItsOwnNearestHub() {
        FIXED_POINTS.forEach { assertSame(it, nearestFixedPoint(it.lat(), it.lng())) }
    }

    @Test fun referenceLocationsRankTheExpectedHubFirst() {
        // Each reference coordinate sits a short walk from the hub it should recommend.
        val expectations = mapOf(
            Point(-37.9240, 145.1200) to "Clayton Station",
            Point(-37.8780, 145.1630) to "Glen Waverley Station",
            Point(-38.1430, 145.1250) to "Frankston Station",
            Point(-37.8180, 144.9530) to "Southern Cross Station"
        )
        expectations.forEach { (where, expected) ->
            assertEquals(expected, nearestApprovedPoints(where.latitude, where.longitude, points = FIXED_POINTS).first().location.name)
        }
    }

    @Test fun rankingMatchesAnIndependentBruteForceAndIsNearestFirst() {
        val here = Point(-37.9000, 145.1000)
        val expected = FIXED_POINTS.sortedWith(compareBy({ haversineMeters(here.latitude, here.longitude, it.lat(), it.lng()) }, { it.name }))
        val actual = nearestApprovedPoints(here.latitude, here.longitude, points = FIXED_POINTS, limit = FIXED_POINTS.size)
        assertEquals(expected.map { it.name }, actual.map { it.location.name })
        assertTrue(actual.zipWithNext().all { (a, b) -> a.distanceMeters <= b.distanceMeters })
    }

    @Test fun asksForMoreResultsExtendTheSameOrdering() {
        val top3 = nearestApprovedPoints(-37.93, 145.14, limit = 3).map { it.location.name }
        val top8 = nearestApprovedPoints(-37.93, 145.14, limit = 8).map { it.location.name }
        assertEquals(top3, top8.take(3))
    }

    @Test fun tiesAreBrokenAlphabeticallySoTheOrderIsStable() {
        val spot = Point(-37.9, 145.1)
        val twins = listOf(NamedLocation("Zulu", spot), NamedLocation("Alpha", spot), NamedLocation("Mike", spot))
        val ranked = nearestApprovedPoints(spot.latitude, spot.longitude, points = twins, limit = 3).map { it.location.name }
        assertEquals(listOf("Alpha", "Mike", "Zulu"), ranked)
        assertEquals(ranked, nearestApprovedPoints(spot.latitude, spot.longitude, points = twins.reversed(), limit = 3).map { it.location.name })
    }

    @Test fun theNearestDistanceNeverGrowsAsTheSearchPointApproachesAHub() {
        val hub = FIXED_POINTS.single { it.name == "Huntingdale Station" }
        val distances = listOf(0.05, 0.02, 0.01, 0.0).map {
            nearestApprovedPoints(hub.lat() + it, hub.lng(), points = FIXED_POINTS, limit = 1).first().distanceMeters
        }
        assertTrue(distances.zipWithNext().all { (a, b) -> a >= b })
        assertEquals(0.0, distances.last(), 1e-6)
    }

    @Test fun searchingBesideACampusRecommendsTheOtherCampusOnlyWhenItIsNotExcluded() {
        val clayton = MONASH_CAMPUSES.single { it.name == "Monash Clayton" }
        val withCampus = nearestApprovedPoints(clayton.lat(), clayton.lng(), limit = 1).first().location
        val withoutCampus = nearestApprovedPoints(clayton.lat(), clayton.lng(), excluded = clayton, limit = 30).map { it.location }
        assertSame(clayton, withCampus)
        assertFalse(clayton in withoutCampus)
    }
}
