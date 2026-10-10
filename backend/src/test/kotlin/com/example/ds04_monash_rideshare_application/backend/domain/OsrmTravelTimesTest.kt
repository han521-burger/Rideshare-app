package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.APPROVED_POINTS
import com.example.ds04_monash_rideshare_application.backend.ImportTravelTimes
import com.example.ds04_monash_rideshare_application.backend.OsrmTravelTimes
import com.example.ds04_monash_rideshare_application.backend.SyntheticTravelTimes
import com.example.ds04_monash_rideshare_application.backend.TRAVEL_MATRIX_POPULATED
import com.example.ds04_monash_rideshare_application.backend.TravelMatrix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The OSRM Table-service generator (GenerateTravelTimesTool.kt). OSRM-shaped responses are built in the test from
// the SYNTHETIC test numbers - no OSRM, no network, and none of this is production data.
class OsrmTravelTimesTest {
    private val points = APPROVED_POINTS
    private val provenance = Json.parseToJsonElement(
        """{"routingEngine":"OSRM (Open Source Routing Machine)","engineVersion":"v0.0.0-test","roadData":"OpenStreetMap"}"""
    ).jsonObject

    // What OSRM answers to /table/v1/driving/...?annotations=duration,distance: seconds as decimals, metres, snapped sources.
    private fun tableResponse(
        code: String = "Ok",
        durationAt: (Int, Int) -> String = { i, j -> if (i == j) "0" else "${SyntheticTravelTimes.seconds(points[i].id, points[j].id)}.4" },
        snapMeters: (Int) -> Double = { 12.5 },
        rows: Int = points.size
    ): String {
        val durations = (0 until rows).joinToString(",") { i -> "[" + points.indices.joinToString(",") { j -> durationAt(i, j) } + "]" }
        val distances = (0 until rows).joinToString(",") { i -> "[" + points.indices.joinToString(",") { j -> if (i == j) "0" else "${1000 + i * 10 + j}.5" } + "]" }
        val sources = points.indices.joinToString(",") { """{"hint":"x","distance":${snapMeters(it)},"name":"","location":[${points[it].lng},${points[it].lat}]}""" }
        return """{"code":"$code","durations":[$durations],"distances":[$distances],"sources":[$sources],"destinations":[$sources]}"""
    }

    private fun convert(response: String): String =
        ImportTravelTimes.convert(OsrmTravelTimes.toRawExport(response, provenance, points, generatedAtMillis = 1_700_000_000_000L), points)

    // --- the request ---

    @Test fun oneTableRequestCoversAllThirtyPointsInLongitudeLatitudeOrder() {
        val url = OsrmTravelTimes.tableUrl("http://localhost:5000/", points)
        assertTrue(url.startsWith("http://localhost:5000/table/v1/driving/"), url)
        val coordinates = url.substringAfter("/driving/").substringBefore("?").split(";")
        assertEquals(30, coordinates.size)
        assertEquals("${points[0].lng},${points[0].lat}", coordinates[0], "OSRM wants longitude,latitude")
        assertTrue(url.endsWith("?annotations=duration,distance"))
    }

    // --- a clean answer becomes a valid, complete, directional matrix ---

    @Test fun aCleanAnswerBecomesAnImportedPopulatedMatrixWithNothingAltered() {
        val matrix = TravelMatrix.parse(convert(tableResponse()), points)
        assertEquals(TRAVEL_MATRIX_POPULATED, matrix.status)
        assertEquals(870, matrix.populatedCount)
        assertEquals(OsrmTravelTimes.SOURCE, matrix.source)
        // 123.4 s -> 123 s: the only transformation is rounding to a whole second.
        val a = points[0].id; val b = points[1].id
        assertEquals(SyntheticTravelTimes.seconds(a, b), matrix.seconds(a, b))
        assertEquals(SyntheticTravelTimes.seconds(b, a), matrix.seconds(b, a))
    }

    @Test fun secondsAreRoundedToTheNearestWholeSecond() {
        val response = tableResponse(durationAt = { i, j ->
            when { i == j -> "0"; i == 0 && j == 1 -> "100.5"; i == 1 && j == 0 -> "100.4"; else -> "${SyntheticTravelTimes.seconds(points[i].id, points[j].id)}" }
        })
        val matrix = TravelMatrix.parse(convert(response), points)
        assertEquals(101L, matrix.seconds(points[0].id, points[1].id))
        assertEquals(100L, matrix.seconds(points[1].id, points[0].id))
    }

    @Test fun directionIsPreservedBecauseOsrmRowsAreOrigins() {
        // Row i = from point i; column j = to point j. A one-way street makes 0 -> 1 differ from 1 -> 0.
        val response = tableResponse(durationAt = { i, j ->
            when { i == j -> "0"; i == 0 && j == 1 -> "300"; i == 1 && j == 0 -> "900"; else -> "${SyntheticTravelTimes.seconds(points[i].id, points[j].id)}" }
        })
        val matrix = TravelMatrix.parse(convert(response), points)
        assertEquals(300L, matrix.seconds(points[0].id, points[1].id))
        assertEquals(900L, matrix.seconds(points[1].id, points[0].id))
    }

    @Test fun provenanceIsCarriedIntoTheFileAndTheGeneratorAddsWhatItObserved() {
        val file = Json.parseToJsonElement(convert(tableResponse(snapMeters = { if (it == 3) 87.0 else 10.0 }))).jsonObject
        assertEquals(OsrmTravelTimes.SOURCE, file["source"]!!.jsonPrimitive.content)
        val prov: JsonObject = file["provenance"]!!.jsonObject
        assertEquals("v0.0.0-test", prov["engineVersion"]!!.jsonPrimitive.content)
        assertEquals("OpenStreetMap", prov["roadData"]!!.jsonPrimitive.content)
        assertEquals("1", prov["requestCount"]!!.jsonPrimitive.content)
        assertEquals("87.0", prov["maxSnapDistanceMeters"]!!.jsonPrimitive.content)
        assertEquals(30, prov["snapDistanceMetersByPoint"]!!.jsonObject.size)
        assertNotNull(file["generatedAt"])
    }

    // --- anything but a clean answer is refused, and nothing is written ---

    @Test fun anErrorCodeFromOsrmIsRefused() {
        val error = assertFailsWith<IllegalArgumentException> { convert(tableResponse(code = "NoTable")) }
        assertTrue(error.message!!.contains("NoTable"), error.message)
    }

    @Test fun aMissingRouteIsRefusedNotFilledIn() {
        val error = assertFailsWith<IllegalArgumentException> { convert(tableResponse(durationAt = { i, j -> if (i == 2 && j == 5) "null" else if (i == j) "0" else "600" })) }
        assertTrue(error.message!!.contains("no route"), error.message)
    }

    @Test fun aWrongSizedMatrixIsRefused() {
        assertFailsWith<IllegalArgumentException> { convert(tableResponse(rows = 29)) }
    }

    @Test fun aNonZeroDiagonalIsRefused() {
        assertFailsWith<IllegalArgumentException> { convert(tableResponse(durationAt = { i, j -> if (i == j) "5" else "600" })) }
    }

    @Test fun aPointFarFromAnyDrivableRoadIsRefused() {
        val error = assertFailsWith<IllegalArgumentException> { convert(tableResponse(snapMeters = { if (it == 7) 400.0 else 10.0 })) }
        assertTrue(error.message!!.contains(points[7].name), error.message)
    }

    @Test fun aZeroSecondDriveBetweenDifferentPlacesIsRefusedByTheImporter() {
        assertFailsWith<IllegalArgumentException> { convert(tableResponse(durationAt = { i, j -> if (i == j) "0" else if (i == 0 && j == 1) "0.2" else "600" })) }
    }
}
