package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.liveScheduleWarningsOf
import com.example.ds04_monash_rideshare_application.backend.readTransaction
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// GET /schedule-warnings - the caller's own schedule warnings (see ScheduleWarnings in Tables.kt): journeys they have
// already committed to that an earlier ride of theirs, finishing late, may leave them too little time to reach. It is the
// only place a warning is ever returned and it is scoped to the caller by the token, never by a parameter, so there is no
// way to ask for someone else's. A warning is never part of a ride or request payload that another person can read.
fun Route.scheduleWarningRoutes() {
    get("/schedule-warnings") {
        val userId = call.requireUserId()
        call.respond(readTransaction { liveScheduleWarningsOf(userId) })
    }
}
