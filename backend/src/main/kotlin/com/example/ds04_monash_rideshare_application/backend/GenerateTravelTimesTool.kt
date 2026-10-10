package com.example.ds04_monash_rideshare_application.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.roundToLong

// Generates the production travel-time matrix from a LOCAL OSRM server running on OpenStreetMap data.
//
// One call to OSRM's Table service (/table/v1/driving/...) returns the whole 30 x 30 matrix of driving
// durations, so there are no per-pair route requests. The answer is turned into the raw-export shape and handed
// to ImportTravelTimes, the same strict importer every generator goes through, which refuses anything
// incomplete. No number is ever adjusted, filled in or estimated here.
//
//   ./gradlew :backend:generateTravelTimes --args="--osrm http://localhost:5000"
//
// tools/osrm/generate-travel-times.ps1 runs the whole procedure (download, prepare, serve, generate).
// See docs/TRAVEL_TIMES.md.
object OsrmTravelTimes {
    const val SOURCE = "osrm-table"
    const val METRIC = "Seconds to drive from one approved point to another: OSRM car profile over OpenStreetMap road data, " +
        "free-flow (no live traffic), from a single /table/v1/driving request"
    const val GENERATOR = "GenerateTravelTimes (backend tool) -> OSRM table service"

    // A point whose nearest drivable road is further than this is effectively off the road network, and a
    // duration measured from that road would be meaningless. A sanity guard, not a tuning knob: nothing is
    // adjusted when it passes.
    const val MAX_SNAP_DISTANCE_METERS = 250.0

    private val json = Json

    fun tableUrl(baseUrl: String, points: List<ApprovedPoint>): String {
        val coordinates = points.joinToString(";") { "${it.lng},${it.lat}" } // OSRM wants longitude,latitude
        return baseUrl.trimEnd('/') + "/table/v1/driving/" + coordinates + "?annotations=duration,distance"
    }

    // Turns an OSRM /table response into a raw export (the shape ImportTravelTimes reads). Throws
    // IllegalArgumentException, saying what is wrong, for anything but a complete, clean answer.
    fun toRawExport(tableResponse: String, provenance: JsonObject, points: List<ApprovedPoint> = APPROVED_POINTS, generatedAtMillis: Long): String {
        val response = json.parseToJsonElement(tableResponse).jsonObject
        val code = response["code"]?.jsonPrimitive?.content
        require(code == "Ok") { "OSRM answered code '$code' (${response["message"]?.jsonPrimitive?.content ?: "no message"})" }

        val durations = response["durations"]?.jsonArray ?: throw IllegalArgumentException("The OSRM response has no durations")
        val distances = response["distances"]?.jsonArray ?: throw IllegalArgumentException("The OSRM response has no distances")
        val sources = response["sources"]?.jsonArray ?: throw IllegalArgumentException("The OSRM response has no sources")
        require(durations.size == points.size && distances.size == points.size && sources.size == points.size) {
            "Expected ${points.size} rows but OSRM returned ${durations.size} durations, ${distances.size} distances, ${sources.size} sources"
        }

        // How far each point had to be moved to reach a drivable road - recorded, and capped.
        val snaps = sources.map { it.jsonObject["distance"]!!.jsonPrimitive.double }
        points.forEachIndexed { i, point ->
            require(snaps[i] <= MAX_SNAP_DISTANCE_METERS) {
                "${point.name} is ${snaps[i].roundToLong()} m from the nearest drivable road in this OSRM data (limit ${MAX_SNAP_DISTANCE_METERS.toInt()} m)"
            }
        }

        val entries = mutableListOf<JsonObject>()
        for (i in points.indices) {
            val durationRow = durations[i].jsonArray
            val distanceRow = distances[i].jsonArray
            require(durationRow.size == points.size && distanceRow.size == points.size) { "Row $i of the OSRM matrix is not ${points.size} wide" }
            for (j in points.indices) {
                val duration = durationRow[j]
                val distance = distanceRow[j]
                require(duration !is JsonNull && distance !is JsonNull) {
                    "OSRM found no route from ${points[i].name} to ${points[j].name}"
                }
                if (i == j) {
                    require(duration.jsonPrimitive.double == 0.0) { "OSRM reported a non-zero time from ${points[i].name} to itself" }
                    continue
                }
                entries += JsonObject(linkedMapOf(
                    "from" to JsonPrimitive(points[i].name),
                    "to" to JsonPrimitive(points[j].name),
                    "seconds" to JsonPrimitive(duration.jsonPrimitive.double.roundToLong()), // whole seconds; the only transformation
                    "meters" to JsonPrimitive(distance.jsonPrimitive.double)
                ))
            }
        }

        val fullProvenance = JsonObject(
            provenance + linkedMapOf(
                "service" to JsonPrimitive("table/v1/driving?annotations=duration,distance"),
                "requestCount" to JsonPrimitive(1),
                "roundingOfSeconds" to JsonPrimitive("nearest whole second"),
                "maxSnapDistanceMeters" to JsonPrimitive((snaps.max() * 10).roundToLong() / 10.0),
                "snapDistanceMetersByPoint" to JsonObject(points.indices.associate { points[it].id to JsonPrimitive((snaps[it] * 10).roundToLong() / 10.0) })
            )
        )
        return JsonObject(linkedMapOf(
            "source" to JsonPrimitive(SOURCE),
            "metric" to JsonPrimitive(METRIC),
            "generator" to JsonPrimitive(GENERATOR),
            "generatedAtEpochMillis" to JsonPrimitive(generatedAtMillis),
            "pointCount" to JsonPrimitive(points.size),
            "partial" to JsonPrimitive(false),
            "provenance" to fullProvenance,
            "entries" to JsonArray(entries),
            "failures" to JsonArray(emptyList())
        )).toString()
    }
}

fun main(args: Array<String>) {
    fun option(name: String): String? = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val osrm = option("osrm") ?: "http://localhost:5000"
    // tools/osrm/generate-travel-times.ps1 writes this file (what OSRM, which profile, which OpenStreetMap extract).
    val provenanceFile = option("provenance") ?: "../tools/osrm/work/provenance.json"
    if (!File(provenanceFile).exists()) {
        System.err.println("No provenance file at $provenanceFile. Run tools/osrm/generate-travel-times.ps1, or pass --provenance <file.json>.")
        System.err.println("usage: generateTravelTimes [--osrm http://localhost:5000] [--provenance <provenance.json>] [--out travel-times.json] [--raw-out raw-export.json]")
        kotlin.system.exitProcess(2)
    }
    val out = File(option("out") ?: "src/main/resources/travel-times.json")
    try {
        // (A leading byte-order mark, which Windows editors and PowerShell like to add, is not part of the JSON.)
        val provenance = Json.parseToJsonElement(File(provenanceFile).readText(Charsets.UTF_8).removePrefix("\uFEFF")).jsonObject
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val url = OsrmTravelTimes.tableUrl(osrm, APPROVED_POINTS)
        val response = client.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build(),
            HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)
        )
        require(response.statusCode() == 200) { "OSRM answered HTTP ${response.statusCode()}: ${response.body().take(200)}" }

        val raw = OsrmTravelTimes.toRawExport(response.body(), provenance, generatedAtMillis = System.currentTimeMillis())
        option("raw-out")?.let { File(it).writeText(raw, Charsets.UTF_8) }
        val text = ImportTravelTimes.convert(raw)
        out.writeText(text, Charsets.UTF_8)
        val matrix = TravelMatrix.parse(text)
        println("Wrote ${matrix.populatedCount} directed durations for ${APPROVED_POINTS.size} approved points to ${out.absolutePath}")
    } catch (e: IllegalArgumentException) {
        System.err.println("Not generated: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}
