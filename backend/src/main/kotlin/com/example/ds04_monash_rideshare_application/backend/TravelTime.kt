package com.example.ds04_monash_rideshare_application.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

// Where the backend gets "how long does it take to drive from approved point A to approved point B".
//
// The production answer is a precomputed DIRECTIONAL matrix (MatrixTravelTimeProvider over the
// travel-times.json resource), generated once from OpenStreetMap road data with OSRM - see
// docs/TRAVEL_TIMES.md. There is no live routing call, no straight-line estimate and no assumed speed
// anywhere: a pair with no entry has no answer, and callers must treat that as "cannot verify", never as
// zero. Where the numbers came from is the file's business (its "source" and "provenance"); nothing that
// uses a TravelTimeProvider needs to know or care.
//
// This is the BACKEND SCHEDULING duration. The duration the Android app shows next to a route preview is
// MapKit's own estimate and can differ; it is presentation only and never overrides this.
interface TravelTimeProvider {
    // Seconds to drive from one approved point to another, or null when the provider has no entry for that
    // pair. A -> B and B -> A are independent values; nothing is inferred from the opposite direction.
    fun travelSeconds(fromPointId: String, toPointId: String): Long?
}

// Time needed to reposition between two approved points: zero for the same point, otherwise the
// provider's directional value (null = unknown).
fun TravelTimeProvider.repositioningSeconds(fromPointId: String, toPointId: String): Long? =
    if (fromPointId == toPointId) 0L else travelSeconds(fromPointId, toPointId)

// Every ordered pair of DIFFERENT approved points: what a complete matrix must contain. N points give
// N * (N - 1) pairs (30 points -> 870). Every pair is needed, not only the campus ones: a passenger's next
// pickup can be any approved point after any drop-off.
fun requiredTravelPairs(points: List<ApprovedPoint> = APPROVED_POINTS): List<Pair<String, String>> =
    points.flatMap { from -> points.filter { it.id != from.id }.map { to -> from.id to to.id } }

const val TRAVEL_MATRIX_SCHEMA_VERSION = 1
const val TRAVEL_MATRIX_UNPOPULATED = "UNPOPULATED"
const val TRAVEL_MATRIX_POPULATED = "POPULATED"

// The directional durations, from point id -> to point id -> seconds. Self pairs are never stored (same
// point is 0 by definition).
class TravelMatrix(
    private val durations: Map<String, Map<String, Long>>,
    val status: String,
    val source: String? = null,
    val metric: String? = null,
    val generatedAt: String? = null,
    // Where the numbers came from (routing engine, road-data licence and attribution, extract, ...). Free-form
    // on purpose: the file records it, the code never branches on it.
    val provenance: JsonObject? = null
) {
    fun seconds(fromPointId: String, toPointId: String): Long? = durations[fromPointId]?.get(toPointId)

    val populatedCount: Int get() = durations.values.sumOf { it.size }

    // Required pairs with no value. Empty means complete.
    fun missingPairs(points: List<ApprovedPoint> = APPROVED_POINTS): List<Pair<String, String>> =
        requiredTravelPairs(points).filter { (from, to) -> seconds(from, to) == null }

    fun isComplete(points: List<ApprovedPoint> = APPROVED_POINTS): Boolean = missingPairs(points).isEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private class FileModel(
            val schemaVersion: Int,
            val status: String,
            val source: String? = null,
            val metric: String? = null,
            val generatedAt: String? = null,
            val generator: String? = null,
            val provenance: JsonObject? = null,
            val durationsSeconds: Map<String, Map<String, Long>> = emptyMap()
        )

        // Strict: a malformed or half-filled file is a data bug and must stop the server from starting rather
        // than quietly planning with holes.
        fun parse(text: String, points: List<ApprovedPoint> = APPROVED_POINTS): TravelMatrix {
            val file = json.decodeFromString(FileModel.serializer(), text)
            require(file.schemaVersion == TRAVEL_MATRIX_SCHEMA_VERSION) { "Unsupported travel-times schemaVersion ${file.schemaVersion}" }
            val known = points.map { it.id }.toSet()
            file.durationsSeconds.forEach { (from, row) ->
                require(from in known) { "travel-times.json: unknown approved point id '$from'" }
                row.forEach { (to, seconds) ->
                    require(to in known) { "travel-times.json: unknown approved point id '$to'" }
                    require(from != to) { "travel-times.json: '$from' -> '$to' - same-point travel is implicitly 0 and must not be listed" }
                    require(seconds > 0) { "travel-times.json: '$from' -> '$to' is $seconds s; a drive between two different places takes time" }
                }
            }
            val matrix = TravelMatrix(file.durationsSeconds, file.status, file.source, file.metric, file.generatedAt, file.provenance)
            when (file.status) {
                TRAVEL_MATRIX_UNPOPULATED -> require(matrix.populatedCount == 0) {
                    "travel-times.json says $TRAVEL_MATRIX_UNPOPULATED but contains ${matrix.populatedCount} durations"
                }
                TRAVEL_MATRIX_POPULATED -> matrix.missingPairs(points).let { missing ->
                    // Numbers with no stated origin cannot be audited or attributed.
                    require(!file.source.isNullOrBlank()) { "travel-times.json says $TRAVEL_MATRIX_POPULATED but does not say where the numbers came from (source)" }
                    require(missing.isEmpty()) {
                        "travel-times.json says $TRAVEL_MATRIX_POPULATED but is missing ${missing.size} of ${requiredTravelPairs(points).size} pairs, e.g. ${missing.first()}"
                    }
                }
                else -> throw IllegalArgumentException("travel-times.json: unknown status '${file.status}'")
            }
            return matrix
        }
    }
}

class MatrixTravelTimeProvider(val matrix: TravelMatrix) : TravelTimeProvider {
    override fun travelSeconds(fromPointId: String, toPointId: String): Long? =
        if (fromPointId == toPointId) 0L else matrix.seconds(fromPointId, toPointId)
}

object ProductionTravelTimes {
    const val RESOURCE = "/travel-times.json"
    private val logger = LoggerFactory.getLogger("TravelTimes")

    fun load(): MatrixTravelTimeProvider {
        val stream = ProductionTravelTimes::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing resource $RESOURCE - see docs/TRAVEL_TIMES.md")
        val matrix = TravelMatrix.parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val required = requiredTravelPairs().size
        if (matrix.populatedCount < required) {
            logger.warn(
                "Travel-time matrix is {} ({} of {} directed pairs). Creating an open ride request needs its trip duration, " +
                    "so those requests will be refused with {} until the matrix is generated - see docs/TRAVEL_TIMES.md.",
                matrix.status, matrix.populatedCount, required, CODE_TRAVEL_TIME_UNAVAILABLE
            )
        } else {
            logger.info("Travel-time matrix loaded: {} directed pairs from {}", matrix.populatedCount, matrix.source ?: "unknown source")
        }
        return MatrixTravelTimeProvider(matrix)
    }
}
