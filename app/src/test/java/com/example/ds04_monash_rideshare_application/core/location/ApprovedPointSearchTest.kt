package com.example.ds04_monash_rideshare_application.core.location

import com.example.ds04_monash_rideshare_application.ui.*
import com.yandex.mapkit.geometry.Point
import org.junit.Assert.*
import org.junit.Test

class ApprovedPointSearchTest {
    @Test fun fourAsciiDigitsArePostcodes() {
        for (value in listOf("3168", " 3000 ", "3800")) assertEquals(LocationInputKind.POSTCODE, locationInputKind(value))
        for (value in listOf("316", "31680", "31a8")) assertEquals(LocationInputKind.PLACE, locationInputKind(value))
    }
    @Test fun suburbsStayOnPlaceSearch() {
        for (value in listOf("Clayton", "Caulfield", "South Yarra")) assertEquals(LocationInputKind.PLACE, locationInputKind(value))
    }
    @Test fun blankInputIsRejectedBeforeLookup() { assertEquals(LocationInputKind.EMPTY, locationInputKind("  ")) }
    @Test fun closestCanonicalPointIsFirst() {
        val oakleigh = FIXED_POINTS.single { it.name == "Oakleigh" }
        val result = nearestApprovedPoints(oakleigh.point.latitude, oakleigh.point.longitude)
        assertSame(oakleigh, result.first().location)
        assertEquals(0.0, result.first().distanceMeters, 0.001)
        assertTrue(result.all { it.location in FIXED_POINTS })
    }
    @Test fun resultsAreNearestFirst() {
        val result = nearestApprovedPoints(-37.925, 145.12, limit = 20)
        assertTrue(result.zipWithNext().all { (a,b) -> a.distanceMeters <= b.distanceMeters })
        assertEquals(20, result.size)
    }
    @Test fun requestedLimitIsApplied() {
        assertEquals(3, nearestApprovedPoints(-37.925,145.12).size)
        assertEquals(1, nearestApprovedPoints(-37.925,145.12,limit=1).size)
        assertTrue(nearestApprovedPoints(-37.925,145.12,limit=0).isEmpty())
    }
    @Test fun campusAndDuplicateEndpointAreExcluded() {
        val campus = MONASH_CAMPUSES.first()
        val alias = NamedLocation("Duplicate", Point(campus.point.latitude,campus.point.longitude))
        val result = nearestApprovedPoints(campus.point.latitude,campus.point.longitude,
            points = MONASH_CAMPUSES + FIXED_POINTS + alias, excluded = campus, limit = 100)
        assertEquals(FIXED_POINTS.toSet(), result.map { it.location }.toSet())
    }
    @Test fun emptyListAndInvalidCoordinatesAreSafe() {
        assertTrue(nearestApprovedPoints(-37.9,145.1,points=emptyList()).isEmpty())
        assertTrue(nearestApprovedPoints(Double.NaN,145.1).isEmpty())
    }
    @Test fun directionPreservesCanonicalEndpoints() {
        val campus = MONASH_CAMPUSES.first(); val point = FIXED_POINTS.first()
        assertEquals(campus to point, approvedRouteEndpoints(campus,point,true))
        assertEquals(point to campus, approvedRouteEndpoints(campus,point,false))
    }
}
