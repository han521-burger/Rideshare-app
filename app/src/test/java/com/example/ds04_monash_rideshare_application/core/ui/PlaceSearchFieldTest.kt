package com.example.ds04_monash_rideshare_application.core.ui

import com.example.ds04_monash_rideshare_application.ui.FIXED_POINTS
import com.example.ds04_monash_rideshare_application.ui.MONASH_CAMPUSES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchFieldTest {
    private val places = MONASH_CAMPUSES + FIXED_POINTS

    @Test fun blankQueryShowsEverything() = assertEquals(places, filterPlaces(places, "  "))

    @Test fun matchesAnyPartOfTheNameIgnoringCase() {
        val stations = filterPlaces(places, "STAT")
        assertTrue(stations.isNotEmpty())
        assertTrue(stations.all { it.name.contains("Station") })
    }

    @Test fun everyWordMustMatch() {
        assertEquals(listOf("Glen Waverley Station"), filterPlaces(places, "glen stat").map { it.name })
        assertEquals(listOf("Monash Melbourne City – 750 Collins St"), filterPlaces(places, "collins").map { it.name })
    }

    @Test fun listHasTheFiveCampusesAndTwentyFivePoints() {
        assertEquals(5, MONASH_CAMPUSES.size)
        assertEquals(25, FIXED_POINTS.size)
        assertEquals(30, places.map { it.name }.distinct().size)
    }
}
