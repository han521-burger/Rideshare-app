package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.MONASH_CAMPUSES
import com.example.ds04_monash_rideshare_application.backend.NAMED_FIXED_POINTS
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.isNearMonashCampus
import com.example.ds04_monash_rideshare_application.backend.nearestNamedPointLabel
import com.example.ds04_monash_rideshare_application.backend.requireMonashEndpoint
import com.example.ds04_monash_rideshare_application.backend.routes.haversine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Domain logic: the approved-location dataset, distance maths and the Monash-endpoint rule.
class HubDatasetTest {
    @Test fun `there are 25 approved hubs and 5 campuses`() {
        assertEquals(25, NAMED_FIXED_POINTS.size)
        assertEquals(5, MONASH_CAMPUSES.size)
    }

    @Test fun `names and coordinates are unique`() {
        val all = NAMED_FIXED_POINTS.map { it.name to (it.lat to it.lng) } + MONASH_CAMPUSES.map { it.name to (it.lat to it.lng) }
        assertEquals(all.size, all.map { it.first }.toSet().size)
        assertEquals(all.size, all.map { it.second }.toSet().size)
    }

    @Test fun `every point is a valid coordinate in greater Melbourne and the Peninsula`() {
        val points = NAMED_FIXED_POINTS.map { it.lat to it.lng } + MONASH_CAMPUSES.map { it.lat to it.lng }
        points.forEach { (lat, lng) ->
            assertTrue(lat in -38.30..-37.70 && lng in 144.80..145.30, "($lat, $lng) is outside the expected region")
        }
    }

    @Test fun `every campus counts as a campus and only Caulfield Station overlaps a campus radius`() {
        MONASH_CAMPUSES.forEach { assertTrue(isNearMonashCampus(it.lat, it.lng), "${it.name} must count as a campus") }
        // Characterisation of the current data: Caulfield Station sits ~210 m from Monash Caulfield,
        // inside the 300 m campus radius, so the endpoint rule treats it as campus-adjacent. Every
        // other hub is clear of every campus. If this list changes, review MonashCampuses.kt.
        val overlapping = NAMED_FIXED_POINTS.filter { isNearMonashCampus(it.lat, it.lng) }.map { it.name }
        assertEquals(listOf("Caulfield Station"), overlapping)
    }

    @Test fun `every hub and campus resolves back to its own name`() {
        NAMED_FIXED_POINTS.forEach { assertEquals(it.name, nearestNamedPointLabel(it.lat, it.lng)) }
        MONASH_CAMPUSES.forEach { assertEquals(it.name, nearestNamedPointLabel(it.lat, it.lng)) }
    }

    @Test fun `a coordinate far from every known point has no label`() {
        assertNull(nearestNamedPointLabel(-33.8688, 151.2093))
    }

    @Test fun `distance is zero for the same point and symmetric`() {
        val a = MONASH_CAMPUSES[0]; val b = MONASH_CAMPUSES[1]
        assertEquals(0.0, haversine(a.lat, a.lng, a.lat, a.lng), 1e-6)
        assertEquals(haversine(a.lat, a.lng, b.lat, b.lng), haversine(b.lat, b.lng, a.lat, a.lng), 1e-6)
    }

    @Test fun `Clayton to Caulfield is about nine kilometres`() {
        val clayton = MONASH_CAMPUSES.single { it.name == "Monash Clayton" }
        val caulfield = MONASH_CAMPUSES.single { it.name == "Monash Caulfield" }
        val km = haversine(clayton.lat, clayton.lng, caulfield.lat, caulfield.lng) / 1000
        assertTrue(km in 8.5..9.2, "got $km km")
    }

    @Test fun `a trip must have a Monash campus at one end either way round`() {
        val campus = MONASH_CAMPUSES.first()
        val hub = NAMED_FIXED_POINTS.first()
        val otherHub = NAMED_FIXED_POINTS.last()
        requireMonashEndpoint(campus.lat, campus.lng, hub.lat, hub.lng)
        requireMonashEndpoint(hub.lat, hub.lng, campus.lat, campus.lng)
        requireMonashEndpoint(campus.lat, campus.lng, MONASH_CAMPUSES.last().lat, MONASH_CAMPUSES.last().lng)
        val failure = assertFailsWith<ValidationException> { requireMonashEndpoint(hub.lat, hub.lng, otherHub.lat, otherHub.lng) }
        assertNotNull(failure.message)
    }
}
