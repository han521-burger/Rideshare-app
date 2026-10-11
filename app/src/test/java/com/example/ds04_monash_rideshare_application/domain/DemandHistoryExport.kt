package com.example.ds04_monash_rideshare_application.domain

import com.example.ds04_monash_rideshare_application.core.demand.DEMAND_ZONE
import com.example.ds04_monash_rideshare_application.core.demand.SyntheticDemand
import com.example.ds04_monash_rideshare_application.ui.APPROVED_POINTS
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

// Not a check - the export step of the demand-forecast analysis (analysis/README.md). It writes the
// demand history the app uses to CSV for the notebook, and only runs when asked to:
//
//   DEMAND_EXPORT_DIR=../analysis/data ./gradlew :app:testDebugUnitTest --tests "*.DemandHistoryExport"
//
// (the path is relative to the app module). Two files: one row per demand event, and one row per hub.
class DemandHistoryExport {
    @Test fun exportDemandHistory() {
        val target = System.getenv("DEMAND_EXPORT_DIR")
        assumeTrue("set DEMAND_EXPORT_DIR to export", !target.isNullOrBlank())
        val dir = File(target!!).apply { mkdirs() }

        val roles = SyntheticDemand.HUBS.associateBy { it.hubName }
        File(dir, "hubs.csv").printWriter().use { out ->
            out.println("hub,role,cluster_factor,latitude,longitude,is_campus")
            APPROVED_POINTS.forEach { hub ->
                val profile = roles.getValue(hub.name)
                out.println(listOf(hub.name.csv(), profile.role, profile.clusterFactor, hub.point.latitude, hub.point.longitude, hub.isCampus).joinToString(","))
            }
        }

        File(dir, "demand_events.csv").printWriter().use { out ->
            out.println("hub,local_time,date,hour,day_of_week,weekend,weight")
            SyntheticDemand.generate().forEach { event ->
                val time = Instant.ofEpochMilli(event.timestampMillis).atZone(DEMAND_ZONE)
                val weekend = time.dayOfWeek.value >= 6
                out.println(listOf(event.hubName.csv(), time.toLocalDateTime(), time.toLocalDate(), time.hour, time.dayOfWeek.value, weekend, event.weight).joinToString(","))
            }
        }
        println("Demand history exported to ${dir.absolutePath}")
    }

    private fun String.csv() = if (contains(',') || contains('"')) "\"" + replace("\"", "\"\"") + "\"" else this
}
