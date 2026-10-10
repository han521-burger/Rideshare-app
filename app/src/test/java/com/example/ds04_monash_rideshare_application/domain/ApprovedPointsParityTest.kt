package com.example.ds04_monash_rideshare_application.domain

import com.example.ds04_monash_rideshare_application.ui.APPROVED_POINTS
import com.example.ds04_monash_rideshare_application.ui.FIXED_POINTS
import com.example.ds04_monash_rideshare_application.ui.MONASH_CAMPUSES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Domain logic (pure JVM): the app's approved pickup/drop-off points must be exactly the backend's. The
// backend's single list (ApprovedPoints.kt) is the source of truth; the app keeps its own copy because the two
// modules cannot import each other. If a name or coordinate drifts, the backend would reject (or mis-identify)
// a point the app offers. Gradle runs unit tests with the module (app/) as working directory.
class ApprovedPointsParityTest {
    private data class BackendPoint(val id: String, val name: String, val lat: Double, val lng: Double, val isCampus: Boolean)

    private val backendFile = File("../backend/src/main/kotlin/com/example/ds04_monash_rideshare_application/backend/ApprovedPoints.kt")

    private fun backendPoints(): List<BackendPoint> {
        assertTrue("backend source not found at ${backendFile.absolutePath}", backendFile.exists())
        val pattern = Regex("""ApprovedPoint\("([^"]+)", "([^"]+)", (-?[0-9.]+), (-?[0-9.]+), isCampus = (true|false)\)""")
        return pattern.findAll(backendFile.readText()).map {
            BackendPoint(it.groupValues[1], it.groupValues[2], it.groupValues[3].toDouble(), it.groupValues[4].toDouble(), it.groupValues[5].toBoolean())
        }.toList()
    }

    private val backend get() = backendPoints()

    @Test fun theBackendListWasParsedInFull() {
        assertEquals(30, backend.size)
        assertEquals(25, backend.count { !it.isCampus })
        assertEquals(5, backend.count { it.isCampus })
    }

    @Test fun theAppOffersExactlyTheBackendsApprovedPoints() {
        assertEquals(30, APPROVED_POINTS.size)
        assertEquals(backend.map { it.name }.sorted(), APPROVED_POINTS.map { it.name }.sorted())
    }

    @Test fun everyCoordinateMatchesToTheLastDigit() {
        val byName = backend.associateBy { it.name }
        APPROVED_POINTS.forEach { point ->
            val expected = byName.getValue(point.name)
            assertEquals("${point.name} latitude", expected.lat, point.point.latitude, 0.0)
            assertEquals("${point.name} longitude", expected.lng, point.point.longitude, 0.0)
        }
    }

    @Test fun campusesAreCampusesOnBothSides() {
        assertEquals(backend.filter { it.isCampus }.map { it.name }.sorted(), MONASH_CAMPUSES.map { it.name }.sorted())
        assertEquals(backend.filter { !it.isCampus }.map { it.name }.sorted(), FIXED_POINTS.map { it.name }.sorted())
        assertTrue(MONASH_CAMPUSES.all { it.isCampus })
        assertTrue(FIXED_POINTS.none { it.isCampus })
    }

    @Test fun backendIdsAreUniqueSlugs() {
        assertEquals(backend.size, backend.map { it.id }.toSet().size)
        assertTrue(backend.all { Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(it.id) })
    }
}
