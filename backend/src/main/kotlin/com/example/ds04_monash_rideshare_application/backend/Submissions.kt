package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

// Idempotent request creation. The app generates a clientRequestId once per logical submission and
// re-sends the SAME one if it never saw the answer (timeout, dropped connection). The first call
// that succeeds records what it created; a repeat returns that instead of creating anything again.
//
// A key only identifies a call that was PROCESSED: a call that was refused outright (validation, or a single request
// that was a duplicate / did not fit) records nothing, so retrying it is evaluated afresh. A call that expanded to
// several occurrences is processed whatever the individual outcomes were - including "none could be made" - and what
// every occurrence came to is recorded with the key. A retry with the same key gets that answer back verbatim; it never
// re-evaluates a date that was rejected, even if the schedule has changed since. A NEW key is a new submission and is
// evaluated against the state at that time.

const val SUBMISSION_KIND_OPEN = "OPEN"
const val SUBMISSION_KIND_SEAT = "SEAT"

private val CLIENT_REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")

// null stays null (older apps send no key); anything else must look like a client-generated id.
fun requireValidClientRequestId(id: String?): String? {
    if (id == null) return null
    if (!CLIENT_REQUEST_ID_PATTERN.matches(id)) {
        throw ValidationException("clientRequestId must be 8-64 letters, digits, '-' or '_'")
    }
    return id
}

// Same shape rules as a clientRequestId. The two are different things: the clientRequestId identifies one
// SUBMISSION (a retry reuses it); the journeyIntentId identifies one intended JOURNEY (every alternative requested
// for it carries it).
fun requireValidJourneyIntentId(id: String?): String? {
    if (id == null) return null
    if (!CLIENT_REQUEST_ID_PATTERN.matches(id)) {
        throw ValidationException("journeyIntentId must be 8-64 letters, digits, '-' or '_'")
    }
    return id
}

fun fingerprintOf(payload: String): String =
    MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }

// What a call with this key came to - the answer to give for it, and again for any retry.
//  * snapshot: the COMPLETE response the first processing produced (CreateRequestsResponse: counts, occurrences, and the
//    created requests as they were at that moment). Replay returns it as it is; nothing in it is recomputed or refreshed,
//    so a retry is an idempotent replay of the command and never a read of the latest state (a request that has since been
//    accepted or cancelled still replays as it was created). Rows written by this version always have it.
//  * occurrences only: a row from the previous version, which stored just the per-date outcomes - those are replayed
//    verbatim, the requests are read back as they are now (best effort).
//  * neither: a row from before results were stored - reconstructed as "these requests were created" (best effort).
// Old rows are never rewritten.
class StoredSubmission(
    val requestIds: List<String>,
    val occurrences: List<OccurrenceResult>?,
    val snapshot: CreateRequestsResponse? = null
)

private val storedResultJson = Json { ignoreUnknownKeys = true }

// The answer a previous call with this key got, or null if the key is new. Throws if the key was used for a different
// kind of call or payload. Must run inside the passenger's schedulingTransaction.
fun Transaction.findSubmission(passengerId: String, clientRequestId: String, kind: String, fingerprint: String): StoredSubmission? {
    val row = RequestSubmissions.selectAll()
        .where { (RequestSubmissions.passengerId eq passengerId) and (RequestSubmissions.clientRequestId eq clientRequestId) }
        .firstOrNull() ?: return null
    if (row[RequestSubmissions.kind] != kind || row[RequestSubmissions.fingerprint] != fingerprint) {
        throw ConflictException(
            "That request id was already used for a different request",
            CONFLICT_IDEMPOTENCY_KEY_REUSED
        )
    }
    val ids = row[RequestSubmissions.requestIds].split(',').filter { it.isNotBlank() }
    val stored = row[RequestSubmissions.result]?.trimStart()
    return when {
        stored == null -> StoredSubmission(ids, null)
        // The previous format was a JSON list of occurrences; the full snapshot is a JSON object.
        stored.startsWith("[") ->
            StoredSubmission(ids, storedResultJson.decodeFromString(ListSerializer(OccurrenceResult.serializer()), stored))
        else -> storedResultJson.decodeFromString(CreateRequestsResponse.serializer(), stored).let { snapshot ->
            StoredSubmission(ids, snapshot.occurrences, snapshot)
        }
    }
}

// Records the complete response the first processing produced, to be replayed verbatim for this key.
fun Transaction.recordSubmission(
    passengerId: String, clientRequestId: String, kind: String, fingerprint: String, snapshot: CreateRequestsResponse, at: Long
) {
    RequestSubmissions.insert {
        it[RequestSubmissions.passengerId] = passengerId
        it[RequestSubmissions.clientRequestId] = clientRequestId
        it[RequestSubmissions.kind] = kind
        it[RequestSubmissions.fingerprint] = fingerprint
        it[RequestSubmissions.requestIds] = snapshot.occurrences.mapNotNull { occurrence -> occurrence.requestId }.joinToString(",")
        it[RequestSubmissions.result] = storedResultJson.encodeToString(CreateRequestsResponse.serializer(), snapshot)
        it[createdAt] = at
    }
}
