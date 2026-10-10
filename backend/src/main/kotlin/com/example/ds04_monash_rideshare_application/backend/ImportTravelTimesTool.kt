package com.example.ds04_monash_rideshare_application.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.time.Instant

// Turns a raw export - driving times between every ordered pair of approved points, keyed by display NAME,
// from ANY generator (the OSRM one, GenerateTravelTimesTool.kt, is the production source) - into the
// backend's travel-times.json (keyed by approved-point ID). It copies numbers; it never makes one up, and it
// refuses anything that is not a complete, clean run. The raw export's "source", "metric", "generator" and
// "provenance" are carried into the file unchanged so the result says where its numbers came from.
//
//   ./gradlew :backend:importTravelTimes --args="path/to/raw-export.json"
//
// See docs/TRAVEL_TIMES.md for the whole procedure.
object ImportTravelTimes {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    // Returns the text of travel-times.json for a raw export, or throws IllegalArgumentException saying
    // exactly what is wrong with it.
    fun convert(rawJson: String, points: List<ApprovedPoint> = APPROVED_POINTS): String {
        val raw = Json.parseToJsonElement(rawJson).jsonObject
        require(raw["partial"]?.jsonPrimitive?.boolean != true) { "The export is partial (a connectivity probe, not a full run). Run the generator without exportPairLimit." }
        val failures = raw["failures"]?.jsonArray ?: JsonArray(emptyList())
        require(failures.isEmpty()) {
            "The export has ${failures.size} failed route requests (e.g. ${failures.first()}). Re-run the generator; nothing was written."
        }

        val idByName = points.associate { it.name to it.id }
        val seconds = LinkedHashMap<Pair<String, String>, Long>()
        raw["entries"]?.jsonArray?.forEach { element ->
            val entry = element.jsonObject
            val fromName = entry["from"]!!.jsonPrimitive.content
            val toName = entry["to"]!!.jsonPrimitive.content
            val from = idByName[fromName] ?: throw IllegalArgumentException("Unknown approved point name '$fromName' in the export")
            val to = idByName[toName] ?: throw IllegalArgumentException("Unknown approved point name '$toName' in the export")
            require(from != to) { "The export contains a same-point entry for '$fromName'" }
            val value = entry["seconds"]!!.jsonPrimitive.long
            require(value > 0) { "'$fromName' -> '$toName' is $value s in the export" }
            require(seconds.put(from to to, value) == null) { "'$fromName' -> '$toName' appears twice in the export" }
        } ?: throw IllegalArgumentException("The export has no 'entries'")

        val missing = requiredTravelPairs(points).filter { it !in seconds }
        require(missing.isEmpty()) {
            "The export is missing ${missing.size} of ${requiredTravelPairs(points).size} directed pairs (e.g. ${missing.first()}). Nothing was written."
        }

        // Deterministic order (the order of APPROVED_POINTS) so regenerating gives a clean, reviewable diff.
        val durations = JsonObject(points.associate { from ->
            from.id to JsonObject(points.filter { it.id != from.id }.associate { to -> to.id to JsonPrimitive(seconds.getValue(from.id to to.id)) })
        })
        fun text(key: String): JsonElement = raw[key]?.takeIf { it !is JsonNull }?.let { JsonPrimitive(it.jsonPrimitive.content) } ?: JsonNull
        val generatedAt = raw["generatedAtEpochMillis"]?.jsonPrimitive?.long?.let { Instant.ofEpochMilli(it).toString() }
        val output = JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(TRAVEL_MATRIX_SCHEMA_VERSION),
            "status" to JsonPrimitive(TRAVEL_MATRIX_POPULATED),
            "source" to text("source"),
            "metric" to text("metric"),
            "generator" to text("generator"),
            "generatedAt" to (generatedAt?.let { JsonPrimitive(it) } ?: JsonNull),
            "pointCount" to JsonPrimitive(points.size),
            "provenance" to (raw["provenance"] ?: JsonNull),
            "durationsSeconds" to durations
        ))
        val result = pretty.encodeToString(JsonElement.serializer(), output) + "\n"
        // Never write something the server would refuse to load.
        TravelMatrix.parse(result, points)
        return result
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: importTravelTimes <travel-times-raw.json> [output travel-times.json]")
        kotlin.system.exitProcess(2)
    }
    val input = File(args[0])
    val output = File(args.getOrNull(1) ?: "src/main/resources/travel-times.json")
    try {
        val text = ImportTravelTimes.convert(input.readText(Charsets.UTF_8))
        output.writeText(text, Charsets.UTF_8)
        val matrix = TravelMatrix.parse(text)
        println("Wrote ${matrix.populatedCount} directed durations for ${APPROVED_POINTS.size} approved points to ${output.absolutePath}")
    } catch (e: IllegalArgumentException) {
        System.err.println("Not imported: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}
