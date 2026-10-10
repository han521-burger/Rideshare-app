package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.APPROVED_POINTS
import com.example.ds04_monash_rideshare_application.backend.MONASH_CAMPUSES
import com.example.ds04_monash_rideshare_application.backend.NAMED_FIXED_POINTS
import com.example.ds04_monash_rideshare_application.backend.SchedulingConfig
import com.example.ds04_monash_rideshare_application.backend.approvedPointById
import com.example.ds04_monash_rideshare_application.backend.legacyPointRef
import com.example.ds04_monash_rideshare_application.backend.lockOrder
import com.example.ds04_monash_rideshare_application.backend.resolveApprovedPoint
import com.example.ds04_monash_rideshare_application.backend.routes.haversine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The approved pickup/drop-off points: the single list in ApprovedPoints.kt and how coordinates resolve to it.
class ApprovedPointsTest {
    // Every location the product offers, written out independently of the code under test. If a point is added,
    // removed or renamed this list must change on purpose.
    private val expectedHubs = listOf(
        "Huntingdale Station", "Clayton Station", "Oakleigh Station", "Chadstone Shopping Centre",
        "Brandon Park Shopping Centre", "Waverley Gardens Shopping Centre", "Glen Waverley Station", "Springvale Station",
        "Westall Station", "Caulfield Station", "Carnegie Station", "Malvern Central", "South Yarra Station", "IKEA Richmond",
        "Royal Park Station", "Parkville Station", "Barkly Square", "Melbourne Central", "Southern Cross Station",
        "The District Docklands", "Footscray Station", "Frankston Station", "Leawarra Station", "Bayside Shopping Centre",
        "Karingal Hub"
    )
    private val expectedCampuses = listOf(
        "Monash Clayton", "Monash Caulfield", "Monash Parkville", "Monash Peninsula", "Monash Melbourne City – 750 Collins St"
    )

    @Test fun everyExpectedLocationIsRepresentedExactlyOnce() {
        assertEquals(30, APPROVED_POINTS.size)
        assertEquals((expectedHubs + expectedCampuses).sorted(), APPROVED_POINTS.map { it.name }.sorted())
        assertEquals(expectedHubs.size, APPROVED_POINTS.count { !it.isCampus })
        assertEquals(expectedCampuses.size, APPROVED_POINTS.count { it.isCampus })
        assertEquals(expectedCampuses.sorted(), APPROVED_POINTS.filter { it.isCampus }.map { it.name }.sorted())
    }

    @Test fun idsAreUniqueStableSlugs() {
        assertEquals(APPROVED_POINTS.size, APPROVED_POINTS.map { it.id }.toSet().size)
        assertTrue(APPROVED_POINTS.all { Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(it.id) })
        // Ids are permanent keys for requests and the travel-time matrix - pin some so a rename is a loud failure.
        assertEquals("Huntingdale Station", approvedPointById("huntingdale-station")?.name)
        assertEquals("Monash Clayton", approvedPointById("monash-clayton")?.name)
        assertEquals("Monash Melbourne City – 750 Collins St", approvedPointById("monash-melbourne-city")?.name)
        assertEquals("Karingal Hub", approvedPointById("karingal-hub")?.name)
    }

    @Test fun displayNamesAreNonEmptyTrimmedAndUnique() {
        assertTrue(APPROVED_POINTS.all { it.name.isNotBlank() && it.name == it.name.trim() })
        assertEquals(APPROVED_POINTS.size, APPROVED_POINTS.map { it.name }.toSet().size)
    }

    @Test fun coordinatesAreValidAndInTheServiceArea() {
        APPROVED_POINTS.forEach {
            assertTrue(it.lat in -90.0..90.0 && it.lng in -180.0..180.0, "${it.name} is not a valid coordinate")
            assertTrue(it.lat in -38.30..-37.70 && it.lng in 144.80..145.30, "${it.name} (${it.lat}, ${it.lng}) is outside greater Melbourne / the Peninsula")
        }
    }

    @Test fun noTwoPointsAreAccidentalDuplicatesOrTooCloseToTellApart() {
        assertEquals(APPROVED_POINTS.size, APPROVED_POINTS.map { it.lat to it.lng }.toSet().size)
        for (a in APPROVED_POINTS) for (b in APPROVED_POINTS) {
            if (a.id >= b.id) continue
            val metres = haversine(a.lat, a.lng, b.lat, b.lng)
            // Far enough apart that a coordinate can only ever be within the snap radius of one of them.
            assertTrue(metres > 2 * SchedulingConfig.APPROVED_POINT_SNAP_RADIUS_METERS, "${a.name} and ${b.name} are only ${metres.toInt()} m apart")
        }
    }

    @Test fun everyPointIsReachableByIdAndResolvesToExactlyItself() {
        APPROVED_POINTS.forEach { point ->
            assertEquals(point, approvedPointById(point.id), point.name)
            assertEquals(point.id, resolveApprovedPoint(point.lat, point.lng)?.id, point.name)
            val withinRadius = APPROVED_POINTS.count { haversine(point.lat, point.lng, it.lat, it.lng) <= SchedulingConfig.APPROVED_POINT_SNAP_RADIUS_METERS }
            assertEquals(1, withinRadius, "${point.name} must be within the snap radius of exactly one approved point")
        }
        assertNull(approvedPointById("not-a-point"))
    }

    @Test fun theOldNamedListsAreJustViewsOfTheOneSource() {
        assertEquals(APPROVED_POINTS.filter { !it.isCampus }.map { Triple(it.name, it.lat, it.lng) }, NAMED_FIXED_POINTS.map { Triple(it.name, it.lat, it.lng) })
        assertEquals(APPROVED_POINTS.filter { it.isCampus }.map { Triple(it.name, it.lat, it.lng) }, MONASH_CAMPUSES.map { Triple(it.name, it.lat, it.lng) })
    }

    // --- resolution tolerance ---

    private fun metresNorth(lat: Double, metres: Double) = lat + metres / 111_195.0

    @Test fun aCoordinateJustInsideTheSnapRadiusResolvesAndJustOutsideDoesNot() {
        val clayton = approvedPointById("monash-clayton")!!
        assertEquals("monash-clayton", resolveApprovedPoint(metresNorth(clayton.lat, 5.0), clayton.lng)?.id)
        assertEquals("monash-clayton", resolveApprovedPoint(metresNorth(clayton.lat, SchedulingConfig.APPROVED_POINT_SNAP_RADIUS_METERS - 5), clayton.lng)?.id)
        assertNull(resolveApprovedPoint(metresNorth(clayton.lat, SchedulingConfig.APPROVED_POINT_SNAP_RADIUS_METERS + 5), clayton.lng))
        assertNull(resolveApprovedPoint(clayton.lat + 0.01, clayton.lng)) // ~1.1 km
    }

    @Test fun theOldThreeHundredMetreCampusRadiusNoLongerCountsAsApproved() {
        val campus = approvedPointById("monash-caulfield")!!
        // 250 m from the campus: inside the old "near a campus" radius, but not an approved point.
        assertNull(resolveApprovedPoint(metresNorth(campus.lat, 250.0), campus.lng))
    }

    @Test fun closePointsAreNeverConfusedWithEachOther() {
        // Leawarra Station sits ~0.5 km from Monash Peninsula.
        val leawarra = approvedPointById("leawarra-station")!!
        val peninsula = approvedPointById("monash-peninsula")!!
        assertEquals("leawarra-station", resolveApprovedPoint(leawarra.lat, leawarra.lng)?.id)
        assertEquals("monash-peninsula", resolveApprovedPoint(peninsula.lat, peninsula.lng)?.id)
        // Caulfield Station and Monash Caulfield (~210 m) are different approved points.
        assertEquals("caulfield-station", resolveApprovedPoint(approvedPointById("caulfield-station")!!.lat, approvedPointById("caulfield-station")!!.lng)?.id)
    }

    @Test fun floatNoiseDoesNotChangeWhichPointACoordinateIs() {
        val a = resolveApprovedPoint(-37.9112, 145.1029)
        val b = resolveApprovedPoint(-37.91120004, 145.10290003) // ~0.5 m of noise
        assertNotNull(a)
        assertEquals(a, b)
    }

    // --- legacy rows only ---

    @Test fun legacyPointRefsExistOnlyToReadOldRowsAndNeverInventAnApprovedId() {
        val approved = legacyPointRef(-37.9112, 145.1029)
        assertEquals("huntingdale-station", approved.key)
        assertEquals("huntingdale-station", approved.pointId)

        val a = legacyPointRef(-37.9, 145.1)
        val b = legacyPointRef(-37.9000001, 145.1000001)
        assertNull(a.pointId)
        assertEquals(a.key, b.key)
        assertTrue(a.key.startsWith("raw:"))
        assertTrue(a.key != legacyPointRef(-37.9005, 145.1).key)
    }

    // --- lock ordering (Concurrency.kt) and config ---

    @Test fun lockOrderIsAscendingDeduplicatedAndSkipsNulls() {
        assertEquals(listOf("a", "b", "c"), lockOrder(listOf("c", null, "a", "b", "a")))
        assertEquals(emptyList(), lockOrder(listOf(null, null)))
        assertEquals(lockOrder(listOf("p-9", "d-1")), lockOrder(listOf("d-1", "p-9")))
    }

    @Test fun approvedSchedulingValuesAreUnchanged() {
        assertEquals(10L, SchedulingConfig.TRANSFER_BUFFER_MINUTES)
        assertEquals(10 * 60_000L, SchedulingConfig.TRANSFER_BUFFER_MILLIS)
        assertEquals(60_000L, SchedulingConfig.DUPLICATE_TOLERANCE_MILLIS)
        assertEquals(30 * 60_000L, SchedulingConfig.ASAP_REQUEST_LIFETIME_MILLIS)
        assertEquals("Australia/Melbourne", SchedulingConfig.SERVICE_ZONE.id)
    }
}
