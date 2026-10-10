package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.APPROVED_POINTS
import com.example.ds04_monash_rideshare_application.backend.FakeTravelTimeProvider
import com.example.ds04_monash_rideshare_application.backend.ImportTravelTimes
import com.example.ds04_monash_rideshare_application.backend.MatrixTravelTimeProvider
import com.example.ds04_monash_rideshare_application.backend.ProductionTravelTimes
import com.example.ds04_monash_rideshare_application.backend.SyntheticTravelTimes
import com.example.ds04_monash_rideshare_application.backend.TRAVEL_MATRIX_POPULATED
import com.example.ds04_monash_rideshare_application.backend.TRAVEL_MATRIX_UNPOPULATED
import com.example.ds04_monash_rideshare_application.backend.TravelMatrix
import com.example.ds04_monash_rideshare_application.backend.approvedPointById
import com.example.ds04_monash_rideshare_application.backend.routes.haversine
import com.example.ds04_monash_rideshare_application.backend.repositioningSeconds
import com.example.ds04_monash_rideshare_application.backend.requiredTravelPairs
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The directional travel-time matrix: the model, the strict file format, completeness, and the importer.
// None of these use routing data: matrices here are built in the test or come from SyntheticTravelTimes.
class TravelTimeMatrixTest {
    private val a = "huntingdale-station"
    private val b = "monash-clayton"
    private val c = "oakleigh-station"

    private fun matrix(vararg entries: Triple<String, String, Long>): TravelMatrix {
        val rows = entries.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, v) -> v.toMap() }
        return TravelMatrix(rows, status = "TEST")
    }

    // --- direction, same point, missing ---

    @Test fun aToBAndBToAAreIndependentValues() {
        val provider = MatrixTravelTimeProvider(matrix(Triple(a, b, 600L), Triple(b, a, 780L)))
        assertEquals(600L, provider.travelSeconds(a, b))
        assertEquals(780L, provider.travelSeconds(b, a))
    }

    @Test fun nothingIsInferredFromTheOppositeDirection() {
        val provider = MatrixTravelTimeProvider(matrix(Triple(a, b, 600L)))
        assertEquals(600L, provider.travelSeconds(a, b))
        assertNull(provider.travelSeconds(b, a), "B -> A must not be assumed to equal A -> B")
        assertNull(provider.repositioningSeconds(b, a))
    }

    @Test fun samePointIsZeroSecondsWithoutAnyEntry() {
        val provider = MatrixTravelTimeProvider(matrix())
        assertEquals(0L, provider.travelSeconds(a, a))
        assertEquals(0L, provider.repositioningSeconds(b, b))
    }

    @Test fun aMissingEntryIsNullNeverZeroAndNeverAGuess() {
        val provider = MatrixTravelTimeProvider(matrix(Triple(a, b, 600L)))
        assertNull(provider.travelSeconds(a, c))
        assertNull(provider.travelSeconds("not-a-point", a))
        assertNull(provider.repositioningSeconds(a, c))
    }

    // --- the fake used by other tests stays usable ---

    @Test fun theFakeProviderStillBehavesTheSameWay() {
        val fake = FakeTravelTimeProvider(mapOf((a to b) to 600L, (b to a) to 900L))
        assertEquals(600L, fake.repositioningSeconds(a, b))
        assertEquals(900L, fake.repositioningSeconds(b, a))
        assertEquals(0L, fake.repositioningSeconds(a, a))
        assertEquals(2, fake.lookups) // the two real lookups; the same-point call needed none
        assertNull(fake.repositioningSeconds(a, c))
        assertEquals(3, fake.lookups)
    }

    @Test fun theSyntheticTestProviderIsCompleteDirectionalAndPositive() {
        val pairs = requiredTravelPairs()
        assertTrue(pairs.all { (from, to) -> (SyntheticTravelTimes.travelSeconds(from, to) ?: 0L) > 0 })
        assertTrue(pairs.any { (from, to) -> SyntheticTravelTimes.travelSeconds(from, to) != SyntheticTravelTimes.travelSeconds(to, from) })
        assertEquals(0L, SyntheticTravelTimes.travelSeconds(a, a))
    }

    // --- completeness arithmetic ---

    @Test fun thirtyPointsNeedEightHundredAndSeventyDirectedPairs() {
        assertEquals(30, APPROVED_POINTS.size)
        assertEquals(30 * 29, requiredTravelPairs().size)
        assertEquals(870, requiredTravelPairs().size)
        assertTrue(requiredTravelPairs().none { (from, to) -> from == to })
        assertEquals(requiredTravelPairs().size, requiredTravelPairs().toSet().size)
    }

    @Test fun missingPairsListsExactlyWhatIsNotThere() {
        val partial = matrix(Triple(a, b, 600L))
        val missing = partial.missingPairs()
        assertEquals(869, missing.size)
        assertFalse((a to b) in missing)
        assertTrue((b to a) in missing)
        assertFalse(partial.isComplete())
    }

    // --- file format ---

    private fun json(status: String, durations: String = "{}", schema: Int = 1) =
        """{"schemaVersion":$schema,"status":"$status","source":"test-generator","durationsSeconds":$durations}"""

    @Test fun anUnpopulatedFileLoadsAndHasNoDurations() {
        val parsed = TravelMatrix.parse(json("UNPOPULATED"))
        assertEquals(0, parsed.populatedCount)
        assertNull(MatrixTravelTimeProvider(parsed).travelSeconds(a, b))
    }

    @Test fun anUnpopulatedFileMayNotQuietlyCarryNumbers() {
        assertFailsWith<IllegalArgumentException> { TravelMatrix.parse(json("UNPOPULATED", """{"$a":{"$b":600}}""")) }
    }

    @Test fun aPopulatedFileMustBeComplete() {
        val error = assertFailsWith<IllegalArgumentException> { TravelMatrix.parse(json("POPULATED", """{"$a":{"$b":600}}""")) }
        assertTrue(error.message!!.contains("missing 869 of 870"), error.message)
    }

    @Test fun malformedFilesAreRefused() {
        assertFailsWith<IllegalArgumentException> { TravelMatrix.parse(json("UNPOPULATED", schema = 2)) }
        assertFailsWith<IllegalArgumentException> { TravelMatrix.parse(json("MAYBE")) }
        assertFailsWith<IllegalArgumentException> { TravelMatrix.parse(json("POPULATED", """{"$a":{"nowhere":600}}""")) }
        assertFailsWith<IllegalArgumentException> { TravelMatrix.parse(json("POPULATED", """{"$a":{"$a":0}}""")) }
        assertFailsWith<IllegalArgumentException> { TravelMatrix.parse(json("POPULATED", """{"$a":{"$b":0}}""")) }
        assertFailsWith<IllegalArgumentException> { TravelMatrix.parse(json("POPULATED", """{"$a":{"$b":-5}}""")) }
    }

    // --- the production resource ---

    @Test fun theProductionResourceLoadsAndIsInternallyConsistent() {
        val provider = ProductionTravelTimes.load()
        val matrix = provider.matrix
        assertTrue(matrix.status == TRAVEL_MATRIX_UNPOPULATED || matrix.status == TRAVEL_MATRIX_POPULATED)
        if (matrix.status == TRAVEL_MATRIX_UNPOPULATED) assertEquals(0, matrix.populatedCount)
    }

    // Skipped (visibly) until the real matrix has been generated; once it has, any missing required pair fails it.
    @Test fun theProductionMatrixCoversEveryRequiredDirectedPair() {
        val matrix = ProductionTravelTimes.load().matrix
        assumeTrue(
            matrix.status == TRAVEL_MATRIX_POPULATED,
            "Production travel-time matrix is not generated yet - see docs/TRAVEL_TIMES.md"
        )
        val missing = matrix.missingPairs()
        assertTrue(missing.isEmpty(), "travel-times.json is missing ${missing.size} of 870 required pairs, e.g. ${missing.take(3)}")
        assertEquals(870, matrix.populatedCount)
    }

    // A sanity net against a unit mix-up or a failed request stored as a number - NOT a way to produce values:
    // straight-line distance is used here only as a yardstick for "could a drive plausibly take this long".
    @Test fun theProductionMatrixValuesArePlausibleDrivingTimes() {
        val matrix = ProductionTravelTimes.load().matrix
        assumeTrue(matrix.status == TRAVEL_MATRIX_POPULATED, "Production travel-time matrix is not generated yet - see docs/TRAVEL_TIMES.md")
        requiredTravelPairs().forEach { (from, to) ->
            val seconds = matrix.seconds(from, to)!!
            val a = approvedPointById(from)!!
            val b = approvedPointById(to)!!
            val straightLineMetres = haversine(a.lat, a.lng, b.lat, b.lng)
            val straightLineKmh = straightLineMetres / seconds * 3.6
            assertTrue(seconds in 20..10_800, "$from -> $to is $seconds s")
            // Never faster than a motorway over the straight line; never slower than a walking pace over it (a
            // one-way system can make a 200 m hop a lot longer, which is why the floor is so low).
            assertTrue(straightLineKmh in 2.0..120.0, "$from -> $to: $seconds s over ${straightLineMetres.toInt()} m straight-line is $straightLineKmh km/h")
        }
    }

    // --- where the production numbers came from ---
    // The persisted production matrix must come from open, persistently-usable data (OpenStreetMap road data
    // routed with OSRM), not from a service whose terms limit storing its results.

    private fun productionText(): String =
        ProductionTravelTimes::class.java.getResourceAsStream(ProductionTravelTimes.RESOURCE)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun provenance(key: String): String =
        kotlinx.serialization.json.Json.parseToJsonElement(productionText()).jsonObject["provenance"]!!.jsonObject[key]!!.jsonPrimitive.content

    @Test fun theProductionMatrixIsPopulatedAndNamesTheOpenRoutingSource() {
        val matrix = ProductionTravelTimes.load().matrix
        assertEquals(TRAVEL_MATRIX_POPULATED, matrix.status)
        assertEquals("osrm-table", matrix.source)
        assertTrue(matrix.metric!!.contains("OSRM") && matrix.metric!!.contains("OpenStreetMap"), matrix.metric)
        assertNotNull(matrix.generatedAt)
    }

    @Test fun theProductionMatrixRecordsItsProvenanceAndAttribution() {
        assertTrue(provenance("routingEngine").contains("OSRM"))
        assertTrue(provenance("engineVersion").startsWith("v"))
        assertEquals("MLD", provenance("algorithm"))
        assertTrue(provenance("profile").startsWith("car.lua"))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(provenance("profileSha256")), "profile hash")
        assertEquals("OpenStreetMap", provenance("roadData"))
        assertTrue(provenance("roadDataAttribution").contains("OpenStreetMap contributors"))
        assertTrue(provenance("roadDataLicence").contains("ODbL"))
        assertEquals("https://www.openstreetmap.org/copyright", provenance("roadDataCopyrightUrl"))
        assertTrue(provenance("extractFile").endsWith(".osm.pbf"))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(provenance("extractSha256")), "extract hash")
        assertTrue(provenance("extractBytes").toLong() > 1_000_000L)
        assertTrue(provenance("osmDataTimestamp").matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")), provenance("osmDataTimestamp"))
        assertEquals("1", provenance("requestCount"), "the whole matrix comes from a single Table request")
    }

    @Test fun theProductionResourceNoLongerIdentifiesYandexAsItsSource() {
        val text = productionText().lowercase()
        assertFalse(text.contains("yandex"), "travel-times.json must not describe itself as Yandex-derived")
        assertFalse(text.contains("mapkit"), "travel-times.json must not describe itself as MapKit-derived")
    }

    @Test fun theProductionDurationsAreDirectionalAndPositive() {
        val matrix = ProductionTravelTimes.load().matrix
        assumeTrue(matrix.status == TRAVEL_MATRIX_POPULATED, "Production travel-time matrix is not generated yet - see docs/TRAVEL_TIMES.md")
        assertTrue(requiredTravelPairs().all { (from, to) -> matrix.seconds(from, to)!! > 0 })
        // Roads are not symmetric (one-way streets, turn restrictions): most pairs must differ by direction,
        // so a mirrored or symmetrised matrix is caught.
        val unordered = requiredTravelPairs().filter { (a, b) -> a < b }
        val differing = unordered.count { (a, b) -> matrix.seconds(a, b) != matrix.seconds(b, a) }
        assertEquals(435, unordered.size)
        assertTrue(differing * 2 > unordered.size, "only $differing of ${unordered.size} pairs differ by direction")
    }

    @Test fun samePointTravelIsStillZeroInTheProductionProvider() {
        val provider = ProductionTravelTimes.load()
        APPROVED_POINTS.forEach { assertEquals(0L, provider.travelSeconds(it.id, it.id)) }
        APPROVED_POINTS.forEach { assertEquals(0L, provider.repositioningSeconds(it.id, it.id)) }
    }

    @Test fun aPopulatedFileThatDoesNotSayWhereItsNumbersCameFromIsRefused() {
        val complete = ImportTravelTimes.convert(rawExport())
        val withoutSource = complete.replace("\"source\": \"test-generator\"", "\"source\": null")
        assertTrue(withoutSource != complete, "test setup: the source line was not found")
        val error = assertFailsWith<IllegalArgumentException> { TravelMatrix.parse(withoutSource) }
        assertTrue(error.message!!.contains("source"), error.message)
    }

    @Test fun theImporterCarriesTheExportsSourceAndProvenanceIntoTheFile() {
        val raw = rawExport().replace("\"source\":\"test-generator\"", "\"source\":\"some-open-source\",\"provenance\":{\"routingEngine\":\"E\",\"roadData\":\"OpenStreetMap\"}")
        val file = kotlinx.serialization.json.Json.parseToJsonElement(ImportTravelTimes.convert(raw)).jsonObject
        assertEquals("some-open-source", file["source"]!!.jsonPrimitive.content)
        assertEquals("E", file["provenance"]!!.jsonObject["routingEngine"]!!.jsonPrimitive.content)
        assertEquals("OpenStreetMap", TravelMatrix.parse(ImportTravelTimes.convert(raw)).provenance!!["roadData"]!!.jsonPrimitive.content)
    }

    // --- importer (synthetic raw export; never touches the production file) ---

    private fun rawExport(skip: Set<Pair<String, String>> = emptySet(), extra: String = "", partial: Boolean = false, failures: String = ""): String {
        val entries = APPROVED_POINTS.flatMap { from -> APPROVED_POINTS.filter { it.id != from.id }.map { to -> from to to } }
            .filter { (f, t) -> (f.id to t.id) !in skip }
            .joinToString(",") { (f, t) -> """{"from":"${f.name}","to":"${t.name}","seconds":${SyntheticTravelTimes.seconds(f.id, t.id)},"meters":1000.0}""" }
        return """{"source":"test-generator","metric":"test","generatedAtEpochMillis":1700000000000,"pointCount":30,
            "partial":$partial,"entries":[$entries$extra],"failures":[$failures]}"""
    }

    @Test fun aCompleteRawExportBecomesAValidatedByIdMatrix() {
        val text = ImportTravelTimes.convert(rawExport())
        val matrix = TravelMatrix.parse(text)
        assertEquals(TRAVEL_MATRIX_POPULATED, matrix.status)
        assertEquals(870, matrix.populatedCount)
        assertEquals(SyntheticTravelTimes.seconds(a, b), matrix.seconds(a, b))
        assertEquals(SyntheticTravelTimes.seconds(b, a), matrix.seconds(b, a))
        assertTrue(text.contains("\"generatedAt\": \"2023-11-14T22:13:20Z\""), "generatedAt should be an ISO instant")
    }

    @Test fun importingIsDeterministic() {
        assertEquals(ImportTravelTimes.convert(rawExport()), ImportTravelTimes.convert(rawExport()))
    }

    @Test fun anIncompleteExportIsRefused() {
        val error = assertFailsWith<IllegalArgumentException> { ImportTravelTimes.convert(rawExport(skip = setOf(a to b))) }
        assertTrue(error.message!!.contains("missing 1 of 870"), error.message)
    }

    @Test fun aPartialOrFailedExportIsRefused() {
        assertFailsWith<IllegalArgumentException> { ImportTravelTimes.convert(rawExport(partial = true)) }
        assertFailsWith<IllegalArgumentException> {
            ImportTravelTimes.convert(rawExport(failures = """{"from":"x","to":"y","error":"timeout"}"""))
        }
    }

    @Test fun anExportWithUnknownDuplicateOrNonPositiveEntriesIsRefused() {
        val unknown = """,{"from":"Atlantis","to":"Monash Clayton","seconds":600,"meters":1.0}"""
        assertFailsWith<IllegalArgumentException> { ImportTravelTimes.convert(rawExport(extra = unknown)) }
        val duplicate = """,{"from":"Huntingdale Station","to":"Monash Clayton","seconds":601,"meters":1.0}"""
        assertFailsWith<IllegalArgumentException> { ImportTravelTimes.convert(rawExport(extra = duplicate)) }
        val zero = """,{"from":"Huntingdale Station","to":"Huntingdale Station","seconds":0,"meters":0.0}"""
        assertFailsWith<IllegalArgumentException> { ImportTravelTimes.convert(rawExport(extra = zero)) }
    }
}
