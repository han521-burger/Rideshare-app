# Backend (trial)

Owner: whoever picks this up next. Kotlin + Ktor + Exposed + SQLite, in the `backend/` Gradle
module. Chosen so the team could unblock FR-08 ("central DB technology not yet chosen in-repo")
without a hosting decision - it runs locally with zero setup, and only `Database.kt` needs to
change to point at a real hosted Postgres later.

## Running it

```
./gradlew :backend:run
```

Starts on `http://localhost:8080`, using a SQLite file at `backend/rideshare-backend.db`
(gitignored, recreated automatically). On first run it seeds:
- Four demo accounts (Test Student, Amara Singh, Ben Nguyen, Priya Kapoor) that sign in with
  their `@student.monash.edu` email and the password `rideshare123` (`DEMO_ACCOUNT_PASSWORD` in
  Database.kt), with rides, requests and messages already set up.
- Vehicles for the demo drivers and a handful of open, confirmed and completed rides between the
  approved places, so there's something to search/match against immediately.

Existing databases are migrated in place on startup (new tables/columns are added; nothing is
deleted). Delete `backend/rideshare-backend.db` and restart if you want fresh demo data.

From an Android emulator, the backend is reachable at `http://10.0.2.2:8080`. For testing across
machines, the team runs one developer's backend and reaches it over Tailscale (that machine's
`100.x` address in each person's `BACKEND_BASE_URL`) - see `docs/SETUP.md`.

## Auth (trial-only, read this before assuming it's secure)

There's no real Monash SSO yet (RTM FR-01 - blocked on Gate G1, SAML metadata from Monash IT).
Instead, accounts use a Monash email + password:
- `POST /auth/register` `{displayName, email, password}` - Monash emails only, password 8+ chars.
- `POST /auth/login` `{email, password}` - both return `{token, user}`.
- `POST /auth/logout` - ends the session for the token sent.
- `POST /auth/change-password` `{currentPassword, newPassword}` - signs out every other device.
- `POST /auth/delete-account` `{password}` - cancels the person's upcoming rides/requests (seats
  go back to others), wipes their name/email/phone/plate so past trips show "Deleted user", ends
  all sessions and frees the email (`AccountAdmin.kt`).

**Wrong passwords:** 5 wrong attempts for one email within 15 minutes lock that email for 15
minutes (429, "try again in N minutes") - `LoginThrottle.kt`, in memory, cleared on restart.

**Forgotten password** (no email sending yet): whoever runs the backend resets it from a terminal,
which works while the server is running:

```
./gradlew :backend:resetPassword --args="someone@student.monash.edu"
```

It prints a 12-character temporary password and signs that account out everywhere; pass it on and
ask them to change it.

**Demo completed-ride history** (synthetic, for presenting Activity / Impact): an operator can give
one existing account a deterministic set of ~32 COMPLETED rides over ~10 weeks, from a terminal:

```
./gradlew :backend:seedDemoHistory --args="someone@student.monash.edu"            # seed (idempotent)
./gradlew :backend:seedDemoHistory --args="someone@student.monash.edu --remove"   # delete it again
```

The target (email or user id) must already exist and have a registered vehicle - it is never created
or edited. Driven rides use that vehicle; the rest are rides the account took as a passenger on
clearly-named synthetic "Demo Driver/Passenger" accounts (no password, `@demo.invalid`). Everything
is ordinary rows in an id namespace starting `demohist-`, so `--remove` deletes only demo records,
and re-running the seed does nothing. There is no HTTP endpoint and no automatic seeding. Impact
figures are NOT stored: the normal dashboard/analytics code computes them from the rows
(`DemoHistoryGenerator.kt` plans, `DemoHistorySeeder.kt` writes). Like `resetPassword` it opens
`rideshare-backend.db` in the working directory - check which backend you are pointing at first.

Passwords are stored as PBKDF2 hashes (`Passwords.kt`). Every other request must send
`Authorization: Bearer <token>`; `Auth.kt`'s `requireUserId()` resolves it via the `sessions`
table (30-day expiry). Backend tests may send a userId as the token instead - only because the
test task sets `rideshare.test.trustUserIdTokens`. Still missing before any real deployment:
HTTPS and email verification (self-service password reset needs email too). Swap this for real OIDC/JWT
verification once Monash SSO access is granted.

## Endpoints

Everything except `/health` and `/auth/*` needs `Authorization: Bearer <token>`.

- `GET /health` -> `"API online"`
- `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `POST /auth/change-password`,
  `POST /auth/delete-account`
- `GET/PUT /users/me`, `GET /users/{id}/rides` (self only)
- `GET /vehicles/mine`, `POST /vehicles`, `PUT/DELETE /vehicles/{id}` (one vehicle per account)
- `GET /rides` (filter: `date`, `originLat/Lng`, `destinationLat/Lng`, `radiusMeters`, `availableOnly`)
- `GET /rides/match` (ranked search - see Matching below)
- `POST /rides`, `GET/PUT/DELETE /rides/{id}`
- `POST /rides/{rideId}/requests` (seat on a posted ride), `POST /requests` (standalone ask with
  no driver yet - ASAP or scheduled, optionally repeating weekly)
- `GET /requests` (driver inbox: requests on own rides + open standalone asks, never your own),
  `GET /requests/mine` (passenger)
- `PUT /requests/{requestId}` (accept/reject/cancel/complete), `POST /requests/{requestId}/undo-accept`
- `GET /schedule-warnings` (the caller's own schedule warnings - see "Actual completion time" below)
- `GET/POST /rides/{rideId}/messages` (driver + accepted passengers only)
- `GET/POST /rides/{rideId}/ratings`
- `GET /favorites`, `POST/DELETE /favorites/{driverId}`
- `GET /users/me/dashboard`, `GET /users/me/analytics`, `GET /users/me/trip-history`, `GET /leaderboard`

## Trip rules (enforced server-side)

- **Places:** both ends of every new ride and request must be an **approved point** - one of the 25
  hubs or 5 campuses listed once in `ApprovedPoints.kt` (matched by coordinates within 75 m; the app sends
  exact coordinates) - and **at least one end must be a campus**, and the two ends must be **different**
  (`RoutePolicy.kt`). Anything else is a 400 with a code: `UNAPPROVED_LOCATION`,
  `CAMPUS_ENDPOINT_REQUIRED` or `SAME_ENDPOINT`. Approved to approved, with a campus: accepted (campus to
  campus is fine). Approved to unapproved, unapproved to approved and unapproved to unapproved: refused. This is
  the rule the app's pickers already follow. It replaces "within 300 m of a campus", which let a hub through
  just for being near one (Caulfield Station is ~210 m from Monash Caulfield) and accepted any coordinate for
  the other end. Rows already in the database are never re-checked. The app keeps its own copy of the points
  (`ui/NamedLocations.kt`); `ApprovedPointsParityTest` in the app fails if the two differ.
- **Booking time:** scheduled rides/requests must leave at least 30 minutes from now
  (`BookingPolicy.kt`, with 2 minutes' grace for phone clock drift). ASAP requests are exempt.
- **Can the person get there?** (replaces the old "30-minute buffer either side" rule) A new request or ride must
  fit the person's whole timeline - see "Schedule feasibility" below. Repeating requests/rides judge each day on its
  own, and every day is reported - see "Creating several requests at once" below.
- **Accepting** a request (`PUT /requests/{id}`) is judged by the same model, not a time-overlap rule: see "Accepting a
  request" below.

## Duplicate requests, retries and concurrency

**Duplicates.** The same journey sent twice never becomes a second request or a second driver card
(`DuplicateRules.kt`). Two *open* requests (`POST /requests`) from one passenger are the same
journey when they have the same origin and destination (compared as approved-point ids, never as raw
latitude/longitude) and depart within ±1 minute (`SchedulingConfig.DUPLICATE_TOLERANCE_MILLIS`);
ASAP departs at the server time it was made. Two *seat* requests are the same only for the same posted
ride - requests for different rides are never duplicates of each other, however close the rides are (whether they
are alternatives is a separate question: see "Schedule feasibility"). Only PENDING and
ACCEPTED requests count; cancelled, rejected, expired and completed ones never block a new request.
A duplicate is refused with HTTP 409, `code: "DUPLICATE_OPEN_REQUEST"` and `existingRequestId`.
This is a *different* thing from schedule feasibility (can the passenger physically make it), which
`ScheduleFeasibility.kt` decides ("Schedule feasibility" below).

**Approved-point ids.** `ApprovedPoints.kt` is the single list of the 30 approved points: a permanent id
(`huntingdale-station`, `monash-clayton`, ...), the display name and the coordinates. `NAMED_FIXED_POINTS` and
`MONASH_CAMPUSES` are views derived from it. A coordinate resolves to a point when it is within
`APPROVED_POINT_SNAP_RADIUS_METERS` (75 m - kept below half the distance between the two closest points,
Frankston Station and Bayside Shopping Centre at 181 m, so a coordinate can never be in range of two). New requests
store the ids (`origin_point_id`, `destination_point_id`) and the approved point's own coordinates. The rounded-
coordinate identity used before enforcement is gone for new requests; `legacyPointRef` survives only to *read and
compare* old rows. The app still sends coordinates.

**Idempotent create.** `POST /requests` and `POST /rides/{id}/requests` accept an optional
`clientRequestId` (8-64 chars of letters, digits, `-`, `_`) that identifies the whole logical submission. The first
call that is *processed* is recorded in `request_submissions`: `result` holds the **complete response** it returned
(`CreateRequestsResponse` as JSON - the counts, every date's outcome, rejected ones included, and the created requests as
they were when created). A retry with the same id **replays that snapshot verbatim** and creates nothing. It is an
idempotent replay of the command, not a read of the latest state: a request that has since been accepted or cancelled still
replays as it was created, and a rejected date that would now fit is still reported as rejected. (To see the latest state
the app reads My Requests, which it does after every submission anyway.) A single request that was
refused outright records nothing, so it can be retried and is judged afresh. Reusing an id for a different request
(different dates, weeks, ride...) is `409 IDEMPOTENCY_KEY_REUSED`. A NEW id is a new submission and is judged against
the state at that time. The Android ViewModel keeps one id per logical submission, re-sends it on a retry and
starts a fresh one after any answer it received. Older apps simply omit the field.

*Older stored rows, never rewritten:* new rows replay the exact snapshot. A row from the previous version (`result` is a
JSON list of just the dates) replays those dates verbatim, with the requests read back as they are now; a row from before
results were stored (`result` null) is reconstructed as "these requests were created". Both are best-effort.

## Creating several requests at once (repeating requests, recurring rides)

A request that repeats, and a seat request across a recurring group, are a *batch* of independent **occurrences**. Each
occurrence goes through the whole pipeline on its own - duplicate check, planned interval, physical feasibility
(Schedule feasibility below), insert - in order of departure, and reports its own outcome. An occurrence made earlier in the
same batch is part of the passenger's pending timeline for the later ones. The whole submission (idempotency lookup,
every occurrence, the record of the result) runs under the passenger's scheduling lock, so nothing can interleave
halfway through; a rejected occurrence inserts nothing and rolls nothing back, while an unexpected failure rolls back the
transaction as usual. A rejected occurrence is not a `CONFLICTED` row: nothing was created.

**Recurrence rules** (`RecurrenceExpansion.kt`). `repeatDaysOfWeek`: ISO weekdays 1 (Monday) - 7 (Sunday), empty = no
repeat. `repeatWeeks`: optional, default 4, must be 1-12. Either out of range is `400 INVALID_RECURRENCE`, before anything is
processed. The first occurrence is the requested departure itself (whatever its weekday); the others are each later date,
starting the day after, up to but excluding first-date + repeatWeeks weeks, whose weekday is in the list, at the same
*local* time of day in Australia/Melbourne - so 8:00 stays 8:00 across a clock change (that day is 23 or 25 hours long);
a wall time that does not exist on some date moves to the next valid time that day. ASAP never repeats. For a seat request
the occurrences are the ride picked plus every other *upcoming* ride in its recurring group, whatever its status.

**The answer** to both create endpoints is always a `CreateRequestsResponse`: `requested`, `created`, `rejected`, `requests`
(the requests made, chronological) and `occurrences` - one per date in the order processed: `departureTime`, `outcome`
(`CREATED` | `REJECTED`), `rideId` (seat requests), `requestId` or, if rejected, `code` + `message` + (`existingRequestId` |
`conflict`). Codes reuse the single-request ones: `DUPLICATE_OPEN_REQUEST` (+ `existingRequestId`), `SCHEDULE_INFEASIBLE`
(+ the structured `conflict`), `TRAVEL_TIME_UNAVAILABLE`, and for seats `RIDE_FULL`, `RIDE_UNAVAILABLE` (not open any more),
`OWN_RIDE`.

**Status codes.** A submission that expands to exactly **one** occurrence behaves as a single request always has: success is
`200` with 1 requested / 1 created, and a rejection is the ordinary HTTP error (`409` duplicate or schedule conflict, ...).
One that expands to **more than one** is a processed batch and is answered `200` with the body above - even when nothing could
be made (`created = 0`). Problems with the submission as a whole stay ordinary errors: malformed input, `400
INVALID_RECURRENCE`, an unapproved endpoint, an unknown ride (`404`), no routing data for the route (`503`), unauthorised.

**Seat series and journey intents.** Each ride in the group is its own occurrence. The `journeyIntentId` belongs to the ride
the passenger picked; the other dates are different journeys and carry none, so separate dates are never alternatives of one
another. Processing is by departure, so if the picked ride is later in time than a sibling it can be the one rejected.

**Legacy duplicates.** Rows created before duplicates were refused are never deleted or rewritten.
`GET /requests` hides all but the oldest of any equivalent PENDING requests from the same passenger, so a
driver never sees one journey twice; history (non-PENDING) is untouched. A later one-off migration could
cancel them for good; nothing does so today.

**Concurrency model** (`Concurrency.kt`, `Database.kt`). There are two kinds of transaction:
- **Write** - the default `transaction { }`, and `schedulingTransaction` / `schedulingTransactionFor` for anything
  that reads a person's schedule and then changes it: creating, accepting, undoing and cancelling a request;
  creating, editing, cancelling, completing and deleting a ride; and the expiry of stale records. A scheduling
  transaction first locks every person (users, ascending by id) and then the rides involved (ascending by id),
  then checks and writes. When the affected people are only known by reading data (a ride's passengers, whoever
  went stale) they are discovered, locked, and discovered again under the lock, retrying with the larger set if
  someone new appeared - that retry is about lock coverage, never about database errors.
- **Read** - `readTransaction { }` for handlers that only read. They never take the write lock.

On SQLite a write transaction is `BEGIN IMMEDIATE` with a 10-second busy timeout: a second writer waits for the
first instead of racing it. A read transaction is an ordinary deferred one on a second `query_only` connection to
the same file, so reads proceed alongside a writer, and a read handler that tries to write fails loudly. Exposed's
automatic transaction retry is switched off, so correctness never depends on a silently retried `SQLITE_BUSY`.
The same code is written for PostgreSQL, where there is one ordinary connection and the scheduling lock becomes
`SELECT ... FOR UPDATE` on the `users` and `rides` rows in the same order. A partial unique index
(`ride_requests_live_journey_unique` over `passenger_id, journey_key` for live rows) is only the backstop for an
exact duplicate that got past the check. A new endpoint that only reads must use `readTransaction`; one that
writes must keep the plain `transaction` (or a scheduling transaction if it decides based on a schedule).

**Lazy expiry.** `expireStaleRecords` (run before API calls) first works out, read-only, whether anything is stale.
Normally nothing is, and it returns without ever taking the write lock. Only when something is stale does it apply
the changes through a scheduling transaction, re-checking under the lock. Policy is unchanged: a scheduled PENDING
request expires at its departure, an ASAP one 30 minutes after it was made.

## Travel times and planned trip intervals

The backend's only source of "how long does this drive take" is a precomputed **directional matrix** between
the approved points (`src/main/resources/travel-times.json`, loaded by `TravelTime.kt`). A to B and B to A are
independent values; same-point travel is 0; a missing pair is *unknown* - never zero, never inferred from the
opposite direction, never estimated from distance or a fixed speed. The numbers are routed over **OpenStreetMap**
road data with **OSRM** (one local Table request for the whole 30 x 30 matrix); the file records exactly what was
used. How it is generated, its provenance and the attribution it carries: `docs/TRAVEL_TIMES.md` and
`docs/ATTRIBUTIONS.md`. Nothing that uses a `TravelTimeProvider` needs to know where the numbers came from.

**Planned duration vs displayed ETA.** Two different durations exist on purpose. The **planned duration** is what
the backend schedules with: the matrix value, stored on the request (`planned_duration_seconds`) and used for
feasibility. The **displayed ETA** is MapKit's own estimate, shown by the Android app next to a route preview (and
carried by a driver's posted ride as `estimatedDurationSeconds`). They can differ, and the displayed one never
overrides the planned one. A posted ride stores both: `planned_duration_seconds` / `planned_arrival_time` from the
matrix (what scheduling uses) and the app's MapKit figure in `estimated_duration_seconds` (display only).

When an open request is created it records its own trip interval, once, and never recomputes it:
`planned_duration_seconds` (the matrix value for origin to destination) and `planned_arrival_time` (effective
departure + duration; an ASAP request departs at the **server's** time of creation and any departure a client
sends with it is ignored). Each occurrence of a repeating request gets the same duration and its own arrival.
If the matrix has no value for the pair, the request is refused with **503** and code
`TRAVEL_TIME_UNAVAILABLE` (recoverable; nothing is stored). Duplicates and idempotent replays are answered before
this check. Seat requests carry no planning fields: the posted ride's own stored duration is the plan. A ride
created by accepting an open request keeps that request's planned duration.

Repeating requests and rides expand "every Monday at 8:30" in **Australia/Melbourne** (`SchedulingConfig.SERVICE_ZONE`),
never the server JVM's zone. (Still on the JVM zone, deliberately untouched: server-written text in the legacy
overlap messages, the `GET /rides?date=` filter and the demo-history seeder.)

**Legacy rows** - requests made before these columns existed have null point ids and null planning fields. They are
never rewritten or deleted and stay readable. A legacy row is *never* trusted for planning fields it does not have: its
endpoints are re-resolved from its coordinates and, only if both resolve to approved points and the matrix has the
pair, a planned interval is derived **in memory** (not stored). One that can't be placed is judged by time overlap alone
(see "Schedule feasibility"). New requests never read planning values from a legacy row. Accepting a legacy request
that has no stored plan uses the same derivation for the ride it creates, and only if that is impossible the old
straight-line estimate (counted by `LegacyScheduling`).

## Schedule feasibility (`ScheduleFeasibility.kt`)

**The question:** can this person physically get through their ordered journeys, using the backend's planned
durations, the matrix's repositioning times and a transfer buffer? It replaces "same route within N minutes"; that
behaviour now follows from the two rules below.

**The timeline** is the person's PENDING and ACCEPTED requests and the rides they are driving (OPEN/CONFIRMED).
Rejected, cancelled, expired and completed ones reserve nothing. A request attached to a ride is the *ride's*
interval. Every journey has canonical origin and destination points, a departure, and a planned arrival =
departure + the planned duration (open request: stored on the request; posted ride: stored on the ride; ASAP:
departs at server time). MapKit's displayed ETA is never used.

**The rule.** A new journey fits when it can be placed in the ordered timeline with room on *both* sides:

    previous.arrival + reposition(previous.destination -> new.origin) + buffer <= new.departure
    new.arrival      + reposition(new.destination -> next.origin)      + buffer <= next.departure

`reposition` is the directional matrix value (0 for the same point); `buffer` is 10 minutes
(`SchedulingConfig.TRANSFER_BUFFER_MINUTES`) and applies even when the places are the same. Only the nearest journey
on each side is checked. If a needed matrix value is missing the request is refused with 503
`TRAVEL_TIME_UNAVAILABLE` - never guessed. Worked example (B -> A takes 30 minutes): after A -> B at 2:00-2:30, another
A -> B is refused at 2:30, 2:45, 3:00 and accepted at 3:10. After A -> B arriving 2:30, a B -> C journey needs 2:40.

**Alternatives.** A passenger may ask several drivers' rides for one intended journey (2:00, 2:05, 2:10); those
are not three trips. What makes requests alternatives is an **explicit journey intent**, never route and time:

- The Android app creates one `journeyIntentId` (a UUID) per ride search and sends it with every seat request made
  from that search's results (`POST /rides/{id}/requests`, field `journeyIntentId`). Searching the same journey again
  (same pickup, drop-off and departure time) keeps the id; changing any of those starts a new one. A ride that is not
  in the current search results is requested without one.
- PENDING seat requests with the **same intent and the same canonical origin and destination** share ONE timeline slot.
  A slot is checked from its *earliest* departure against the journey after it and its *latest* arrival against the
  journey before it, so the plan holds whichever alternative is finally taken.
- The same route without that intent is *not* grouped, however close the times: two same-route seat requests 25
  minutes apart under different intents are two real journeys, each of which must fit (they are allowed when
  physically feasible). Open (standalone) requests never carry an intent and are always their own journey; so are
  accepted journeys, rides you drive, requests from older apps, and legacy rows (`journey_intent_id` null - never
  inferred, never rewritten). That is stricter for old pending data, by design.
- An intent id reused on a different canonical route is refused (400), never merged. There is deliberately no time
  window inside an intent: the backend does not know the time the passenger searched for, and one search can
  legitimately return rides up to ~90 minutes apart.
- Repeating ("all rides in this series"): the intent belongs to the ride the passenger picked; the other days are
  different journeys and carry none.
- Accepting a request cancels the passenger's other PENDING requests with the same intent (same timestamp, so Undo
  restores them). Other journeys are never cancelled for "looking similar": see "Accepting a request".

**Errors.** A journey that does not fit is `409` with code `SCHEDULE_INFEASIBLE` and a structured `conflict`
object (never a duplicate: those are `DUPLICATE_OPEN_REQUEST`). Its `type` is `TOO_SOON_AFTER_PREVIOUS`
(`earliestFeasibleDeparture`), `TOO_LATE_FOR_NEXT` (`latestFeasibleDeparture`) or
`OVERLAPS_UNVERIFIED_LEGACY`, with the conflicting journey's id/kind/times, the two points (id and display name),
`repositioningSeconds` and `transferBufferSeconds`. Times are epoch milliseconds; the Android app formats them in
the viewer's zone. Order of processing: validate, idempotency replay, canonicalise endpoints, duplicate check,
build the proposed journey, feasibility, insert - all under the person's scheduling lock, so two requests that are
each fine but cannot both happen leave only one.

**Legacy rows** (made before planning fields existed) are never rewritten. One whose endpoints are approved points
takes part with an interval derived *in memory* from the matrix. One that cannot be placed on the map (e.g. a
retired hub) cannot be reasoned about by location, so a journey is judged against it by time overlap alone (the old
+-30 minute margin, `TRIP_CONFLICT_BUFFER_MILLIS`). That, and the straight-line 40 km/h estimate it needs, are the
only remaining uses of the old model, whether a journey is being created or accepted; new rows never reach them (`LegacyScheduling` counts uses and a test asserts zero for new journeys).

**Limits of the model.** Planned durations are OSRM's free-flow car times, about a quarter shorter than MapKit's on
the same trips and with no live traffic. The 10-minute buffer is the only slack. There is no calibration; it is a
deterministic planning model, not a traffic prediction.

## Accepting a request (`Acceptance.kt`)

A driver accepting a request is decided by the same feasibility model as making one. It runs in the scheduling
transaction that holds the passenger's, the driver's and the rides' locks, so every read and write below is one atomic
step. A person's journeys are **committed** (ACCEPTED requests, rides they drive) or **pending** (wishes); a commitment
always outranks a wish.

1. **Reload and refuse** a request that is no longer actionable: any terminal status (including `CONFLICTED`), expired
   (a request whose deadline passed between the lazy expiry and this lock is also refused), or your own.
2. **Choose the ride.** The one it is attached to; or one of the driver's posted rides (`targetRideId`), which must
   leave within 45 minutes of the asked-for time **and serve the passenger's route**; or a new ride made from the
   request (it keeps the request's approved points and planned duration).
3. **The passenger's committed journeys win.** The accepted journey (the ride's own interval) must fit around what the
   passenger has already accepted or drives, by the two inequalities above. If not, the accept is refused:
   `409 SCHEDULE_INFEASIBLE` with the message "This passenger is no longer available for this ride." and **no**
   `conflict` details - they would describe the passenger's other trips to the driver. Nothing is changed.
4. **A new ride must fit the driver's own committed journeys** (this one is the driver's own schedule, so the structured
   `conflict` is returned). Joining a ride that already exists adds nothing to the driver's schedule, so it is not checked
   again. The driver's own *pending* requests that the new ride would make impossible need their confirmation
   (`OWN_PENDING_REQUEST`, retry with `cancelMyConflictingRequests`); with it they are `CANCELLED` (their own choice,
   restorable by Undo).
5. **Write** the acceptance (seat, status, ride `CONFIRMED`).
6. **Same intent -> CANCELLED.** The passenger's other PENDING requests with the same `journeyIntentId` were options for
   the journey just accepted; they become `CANCELLED`, stamped with the accept time.
7. **Different journey that no longer fits -> CONFLICTED.** The passenger's remaining PENDING journeys are re-checked
   against the new timeline in order of departure (earliest first, ties by id - never row order). Each that still fits is
   kept and becomes part of the timeline the later ones are judged against; each that does not becomes `CONFLICTED`. So
   invalidating one early can let a later one survive (A conflicts with B, B with C: dropping B can keep C). The rule is
   greedy and deterministic: maximal (nothing dropped could be added back), not guaranteed to drop the fewest possible in
   every contrived case. A journey that still fits is untouched.

**Two kinds of race, two different answers.**
* *Two drivers, the SAME open request.* There is one underlying request, so exactly one acceptance can win. Both
  transactions take the passenger's lock, so one runs after the other; the second reloads the request, sees it is no longer
  `PENDING` and is refused: `409 REQUEST_ALREADY_ACCEPTED`, "This request has already been accepted by another driver."
  (to the same driver pressing twice: "You have already accepted this request."). The transition itself is also a
  compare-and-set on `status = PENDING`, so it cannot happen twice even if a lock were ever missed. The winner is whoever
  commits first - never decided by a timestamp - and nothing (no ride, no seat) is created for the loser. The message names
  nobody. Any other ending - cancelled (including a lost alternative), declined, completed, `CONFLICTED` - is
  `409 REQUEST_UNAVAILABLE`, "This request is no longer available." and says nothing about why.
* *One passenger, two DIFFERENT journeys.* Accepting one leaves the other `PENDING` if it is still physically possible
  and makes it `CONFLICTED` only if the accepted interval (the ride's, which can differ from what was asked) no longer
  leaves room for it. So the outcome of a race between two different requests depends on which is accepted first - e.g.
  `ACCEPTED + PENDING` is correct when the later-timed one wins and the other still fits as asked. What is guaranteed is the
  final state: accepted journeys fit each other, every remaining pending journey still fits, every conflicted one really does
  not. A request is judged at the time it asked for, so one whose own time can no longer be met is `CONFLICTED` even if a
  ride at some other time within the 45-minute attach window would have worked.
* Different passengers never affect each other.

**`CONFLICTED`** means "valid when made, now physically impossible because of something stronger". It is terminal: set
only by the system, never returned to `PENDING` (not by Undo, not by the stronger trip later being cancelled), and the
passenger simply makes a new request. It differs from `CANCELLED` (deliberate, or an alternative that lost),
`REJECTED` (a driver said no) and `EXPIRED` (the time passed). The reason is stored (`ride_requests.conflict_details`,
the same `ScheduleConflictDetails` a refused create returns) and shown to the passenger in `GET /requests/mine`; it is
removed from what a driver sees, because it describes the passenger's other trips. A `CONFLICTED` request is never in
an actionable feed (`GET /requests` only offers standalone requests while `PENDING`); a driver still sees one aimed at
their ride, as history, without the reason.

**Undo** (60 seconds, the accepting driver) restores exactly what that accept cancelled: the passenger's same-intent
alternatives, and the driver's own requests they agreed to cancel. It never restores a `CONFLICTED` request, nor one that
was cancelled/rejected/expired for another reason, nor an alternative the passenger has since asked for again (that
would be the duplicate refused everywhere else).

**Route compatibility** (an open request joining an existing ride). A ride serves one route: its canonical origin to its
canonical destination. Its intermediate stops are display-only free-form coordinates - not approved points, and the
ride's duration is always the direct origin->destination one - so they can't be a pickup or drop-off, and there is no
sub-route matching. The rule is exact: the passenger's canonical pickup is the ride's origin and their drop-off is its
destination; anything else (a different end, or the reverse) is refused (400) and nothing changes. A request that can't be
reduced to approved points (legacy data) can't be verified, so it can only be accepted by creating a new ride from it.

## Actual completion time, completed history and completion-time revalidation

Until a ride is completed, scheduling uses **planned** intervals. When the driver explicitly completes a ride, its **real**
completion time replaces the planned arrival for everything that comes after it.

**`rides.completed_at`** - the server's clock at the moment of an explicit Complete (`PUT /rides/{id}` `status=COMPLETED`),
written in the same transaction as the status change (so never for a completion that rolls back) and never touched again.
It is never taken from a client (a `completedAt` in the request body is ignored). `NULL` means "no observed completion time":
rides from before the column existed, rides that are not completed, and rides the **system** completed on its own. Requests
carry no completion timestamp of their own; a passenger's journey is the ride's, so they use the ride's `completed_at`.
`RideDto.completedAt` exposes it (it is shared by everyone on the ride, not private).

**Manual vs automatic completion.** Only the driver pressing Complete is an observed arrival. The lazy auto-completion
(`Lifecycle.kt`, 30 minutes after planned arrival) still moves the ride and its accepted requests to `COMPLETED`, but leaves
`completed_at` null: the moment a cleanup happened to run is not when anyone arrived. It also revalidates nothing - it
learns nothing the planned arrival had not already said.

**The ride owns journey completion .** A seat attached to a ride becomes `COMPLETED` only when its ride does - in
the same transaction as the driver's Complete, or through the system's auto-completion - never on its own.
`PUT /requests/{id}` `COMPLETED` (by the passenger or the driver) on a seat whose ride is not `COMPLETED` is refused:
`409`, code `RIDE_NOT_COMPLETED`, "This trip is completed when the driver completes the ride.", nothing changes (the seat
stays `ACCEPTED` and on the person's timeline). Before this rule it set the status and recorded nothing else, which let a
passenger leave the schedule while the ride was still travelling.
*Legacy / inconsistent rows:* a request `ACCEPTED` on a ride that is already `COMPLETED` is reconciled to `COMPLETED` by that
same call (no timestamp of its own; `respondedAt` untouched). A request that is `COMPLETED` while its ride is still live is not
history - `latestCompletedJourneyOf` only uses a request whose ride is `COMPLETED` - and it stays on the live timeline as an
accepted commitment; when the ride really completes, that passenger is revalidated with the ACCEPTED ones (cancelled,
rejected and expired seats never took the ride and are not). A seat's history is always its ride's destination and
`ride.completedAt ?: plannedArrival`. COMPLETED -> COMPLETED is refused like any terminal transition, as before.

**A ride cannot be completed before it has started.** `serverNow < departureTime` (the later of the stored departure and any
departure sent in the same call) is refused: `409`, code `RIDE_NOT_STARTED`, nothing changes. A repeat Complete on a ride that
is already `COMPLETED` keeps the existing contract - `200` with the ride, no writes, the first `completed_at` retained.

**History is part of the timeline.** A person's most recent completed journey (the one that ended last, as driver or as
passenger) joins their timeline as a `COMPLETED`, committed node whose arrival is `completed_at ?: plannedArrival`. A new
journey is checked against it by the same inequality as any previous journey: arrival + directional matrix reposition +
10-minute buffer <= new departure. So there is no cooldown - once there is time to get from where they ended to the new
pickup, it fits - and an old completed journey constrains nothing. History is only ever the journey *before*: a journey
starting before a completed one began is never refused for it. A completed journey that cannot be placed on the map (a legacy
endpoint that is not an approved point) establishes nothing, as before; a legacy completed ride without planning fields is
placed from its coordinates in memory (and does not count as a legacy estimate); old rows are never rewritten and no
`completed_at` is ever invented for them.

**Completion transaction.** The ride's driver and every person with a request on it are locked in the established order
(users ascending, then the ride), then, in one transaction: reload the ride; refuse if terminal (idempotent retry) or not a
legal move; refuse if not started; take `ServerClock.now()` once; set the ride `COMPLETED` with `completed_at`; complete the
`ACCEPTED` requests and cancel the `PENDING` ones (existing rule); then revalidate each person who was **on** the ride (the
driver and the passengers whose seat was accepted) - nobody else is read or written:

* their `PENDING` journeys that no longer fit become **`CONFLICTED`** with the structured reason (same rule, same
  departure-then-id order as accepting a request), never `CANCELLED`;
* their **committed** journeys (`ACCEPTED` seat, ride they drive) are **never changed**. If the actual completion leaves too
  little time to reach one (completion + reposition + buffer > its departure) they get a private **schedule warning**;
* `CONFLICTED`/`EXPIRED`/`CANCELLED`/`REJECTED` rows are terminal and not looked at: finishing earlier than planned does not
  bring one back. A pair the matrix cannot judge leaves a pending journey `PENDING` and never aborts the completion.

**Schedule warnings** (`schedule_warnings`) are one row per (person, journey, ride that caused it): `REQUEST` + request id for
an accepted seat, `RIDE` + ride id for a ride the person drives, with the structured `ScheduleWarningDetails` as JSON
(completed-at, planned arrival, where it ended, the next pickup, reposition, buffer, earliest feasible departure, next
departure). They are returned only by **`GET /schedule-warnings`**, scoped to the caller by their token and filtered to
journeys that are still live (a cancelled/completed request or cancelled/completed ride no longer shows its warning). No ride
or request payload that another person can read carries one - a passenger's previous trip is never shown to the next ride's
driver. A warning is not a status: the request stays `ACCEPTED` and every status-based view, feed and funnel count is
unchanged. There is deliberately no recovery engine in this stage (a warning is not recomputed or cleared when things later
change; it is just not shown once its journey is no longer live). Deleting an account deletes its warnings.

**Snapshots stay snapshots.** `request_submissions.result` is the record of what a *creation* call came to; nothing in a
completion touches it. A repeating request's occurrence that later becomes `CONFLICTED` still replays as it was created.

Not here yet: detecting that an in-progress ride is *running* late before anyone presses Complete; clearing or recomputing a
warning when a later edit or cancellation changes the picture.

## Driver approval flow (FR-03 / FR-05)

1. `POST /rides/{rideId}/requests` or `POST /requests` creates a `PENDING` request. It does not
   reserve a seat or change the ride status.
2. Drivers receive pending requests through `GET /requests`. The Android app quietly refreshes
   this inbox every eight seconds while a driver is on a main tab and shows an animated banner.
3. The owning driver accepts or rejects with `PUT /requests/{requestId}`. Acceptance checks physical
   feasibility for both people (see "Accepting a request"), performs the authoritative capacity check inside
   the transaction, decrements seats and marks the ride `CONFIRMED`; rejection leaves capacity unchanged.
   Accepting a standalone ask with no ride creates one from the ask's route and time. Once accepted, the
   passenger's other options for the same journey are cancelled and any other waiting request that no longer
   fits becomes `CONFLICTED`.
4. Within 60 seconds, the driver can `POST /requests/{id}/undo-accept`: the request goes back to
   `PENDING`, the seat is returned, a ride created just for that accept is removed, and the alternatives
   that accept cancelled are restored (never a `CONFLICTED` one).
5. A passenger can cancel a pending request. Cancelling an accepted request returns its seats.
6. Declining (`REJECTED`) a request on your own ride rejects it. Declining an open ask (no ride
   yet) only hides it from your inbox (`declined_requests` table) - it stays `PENDING` for other
   drivers.

## Expiry and auto-completion (FR-05)

There's no scheduler. Before handling every call except `/health`, `expireStaleRecords()`
(`Lifecycle.kt`) tidies up:
- A `PENDING` request becomes `EXPIRED` once its departure time passes (ASAP asks: 30 minutes
  after they were made). Expired requests can't be accepted.
- An `OPEN`/`CONFIRMED` ride past its departure with nobody accepted becomes `EXPIRED`.
- A ride with accepted passengers becomes `COMPLETED` (with those requests) 30 minutes after its
  estimated arrival, so it appears in trip history and can be rated. This is a status change only: the ride
  gets no `completed_at` (nobody observed an arrival) and nothing is revalidated.

This is polling for the local trial, not a push-notification service. A hosted version should
replace polling with an authenticated realtime channel or platform push notification.

## Matching (FR-04)

Rule-based only, no ML, per the weekly plan. `matching/MatchingEngine.kt`:
1. Hard gates: enough seats, same day, within a 45-minute window, same rough direction, pickup
   within 3 km.
2. Survivors get a weighted score: route similarity 40%, time compatibility 30%, pickup proximity
   20%, detour cost 10%.
3. If nothing survives the gates, `GET /rides/match` falls back to just direction + time window
   (the "simple fallback" called out in the spec).

The driver still makes the final accept/reject call - this only ranks candidates, it never
auto-books.

## Known limitations (be honest about these before demoing)

- SQLite + a plain `transaction {}` block for the seat-decrement race condition is fine for one
  local server process, but isn't real row-locking - a Postgres port would need `SELECT ... FOR
  UPDATE` to keep the same guarantee under concurrent writers.
- CORS is wide open (`anyHost()`) for local dev convenience. Tighten before any real deployment.
- No HTTPS - this only runs on a developer's machine right now. TLS termination is a hosting
  decision the team hasn't made yet. Android debug builds allow plain HTTP to any address
  (`app/src/debug/res/xml/network_security_config.xml`) so testing over LAN/Tailscale works;
  release builds only allow the emulator/localhost addresses.
- No email verification or self-service password reset (admin reset command only). The login
  lockout is in memory, so restarting the backend clears it.
- Expiry runs on every request rather than on a schedule - fine at trial scale, but a hosted
  version should move it to a scheduled job.
