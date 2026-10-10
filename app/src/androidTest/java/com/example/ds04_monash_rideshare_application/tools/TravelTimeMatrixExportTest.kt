package com.example.ds04_monash_rideshare_application.tools

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ds04_monash_rideshare_application.BuildConfig
import com.example.ds04_monash_rideshare_application.core.location.requestDrivingRoute
import com.example.ds04_monash_rideshare_application.ui.APPROVED_POINTS
import com.yandex.mapkit.MapKitFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// DEV-ONLY COMPARISON TOOL, not a test of the app, and NOT the source of the production travel-time matrix.
//
// The backend's scheduling matrix (backend/src/main/resources/travel-times.json) is generated from OpenStreetMap
// data with OSRM (tools/osrm/generate-travel-times.ps1), because the terms for Yandex MapKit routing results
// limit how long they may be stored. This tool exists only so MapKit's drive times can be compared with the OSRM
// ones during development. Its output file:
//   * is NOT the authoritative persisted matrix and must never be used as one;
//   * must NOT be committed or kept beyond what the applicable Yandex licence permits - delete it after use;
//   * does not change what the backend plans with. MapKit durations are presentation (route preview) only.
//
// What it does: asks Yandex (through the app's own requestDrivingRoute, the same call that produces the route
// durations the app shows) for the driving time between EVERY ordered pair of approved pickup/drop-off points,
// and writes the raw answers to a JSON file on the device.
//
// It does nothing unless asked to - it needs the network, a Yandex key and about ten minutes - so it never runs
// as part of a normal connectedAndroidTest. See docs/TRAVEL_TIMES.md.
//
//   adb shell am instrument -w -e class com.example.ds04_monash_rideshare_application.tools.TravelTimeMatrixExportTest \
//       -e exportTravelMatrix true  [-e exportPairLimit 3]  \
//       com.example.ds04_monash_rideshare_application.test/androidx.test.runner.AndroidJUnitRunner
//
// exportPairLimit is for a quick connectivity probe: only the first N pairs are requested and the file
// is marked as partial (the importer refuses partial files).
@RunWith(AndroidJUnit4::class)
class TravelTimeMatrixExportTest {
    @Test
    fun exportTravelTimeMatrix() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipped: this is the travel-time matrix generator. Run it with -e exportTravelMatrix true (see docs/TRAVEL_TIMES.md).",
            args.getString("exportTravelMatrix") == "true"
        )
        assertTrue("YANDEX_MAPKIT_API_KEY is not set in local.properties - the build has no routing key", BuildConfig.YANDEX_MAPKIT_API_KEY.isNotBlank())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Same startup the app does (RideshareApplication sets the key; MainActivity initialises and starts MapKit).
        instrumentation.runOnMainSync {
            MapKitFactory.initialize(context)
            MapKitFactory.getInstance().onStart()
        }

        val limit = args.getString("exportPairLimit")?.toIntOrNull()
        val pairs = APPROVED_POINTS.flatMap { from -> APPROVED_POINTS.filter { it.name != from.name }.map { to -> from to to } }
        val selected = if (limit != null) pairs.take(limit) else pairs
        val entries = JSONArray()
        val failures = JSONArray()
        val startedAt = System.currentTimeMillis()

        selected.forEachIndexed { index, (from, to) ->
            var lastError: Throwable? = null
            var done = false
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    // MapKit must be driven from the main thread; the suspend call resumes on its callback.
                    val route = runBlocking { withContext(Dispatchers.Main) { requestDrivingRoute(context, from.point, to.point) } }
                    entries.put(
                        JSONObject()
                            .put("from", from.name).put("to", to.name)
                            .put("seconds", route.estimatedDurationSeconds)
                            .put("meters", route.distanceMeters)
                    )
                    done = true
                    break
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "attempt $attempt failed for ${from.name} -> ${to.name}: ${t.message}")
                    runBlocking { delay(RETRY_DELAY_MILLIS * attempt) }
                }
            }
            if (!done) {
                failures.put(JSONObject().put("from", from.name).put("to", to.name).put("error", lastError?.message ?: "unknown"))
            }
            if ((index + 1) % 25 == 0 || index + 1 == selected.size) {
                Log.i(TAG, "progress ${index + 1}/${selected.size}, ${failures.length()} failed")
            }
            runBlocking { delay(PAUSE_BETWEEN_REQUESTS_MILLIS) } // be polite to the routing service
        }

        val output = JSONObject()
            .put("source", "yandex-mapkit-driving")
            .put("metric", "Weight.time in seconds (no live traffic) - the value the app shows as the drive time")
            .put("mapkit", "4.42.0-full")
            .put("generatedAtEpochMillis", startedAt)
            .put("pointCount", APPROVED_POINTS.size)
            .put("partial", limit != null)
            .put("entries", entries)
            .put("failures", failures)

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, OUTPUT_FILE_NAME)
        file.writeText(output.toString(2))
        Log.i(TAG, "wrote ${entries.length()} durations (${failures.length()} failures) to ${file.absolutePath}")
        assertTrue("${failures.length()} route requests failed - see logcat tag $TAG; the file was still written", failures.length() == 0)
    }

    private companion object {
        const val TAG = "TravelMatrix"
        const val OUTPUT_FILE_NAME = "travel-times-raw.json"
        const val MAX_ATTEMPTS = 4
        const val RETRY_DELAY_MILLIS = 2_000L
        const val PAUSE_BETWEEN_REQUESTS_MILLIS = 150L
    }
}
