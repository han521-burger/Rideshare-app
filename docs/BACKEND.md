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
- `GET/POST /rides/{rideId}/messages` (driver + accepted passengers only)
- `GET/POST /rides/{rideId}/ratings`
- `GET /favorites`, `POST/DELETE /favorites/{driverId}`
- `GET /users/me/dashboard`, `GET /users/me/analytics`, `GET /users/me/trip-history`, `GET /leaderboard`

## Trip rules (enforced server-side)

- **Places:** one end of every ride/request must be a Monash campus (`MonashCampuses.kt`, within
  300 m of its exact coordinates). The app only offers the 5 campuses + 25 approved points
  (`NamedPoints.kt`, mirrored in the app's `ui/NamedLocations.kt` - keep the two in sync by hand).
- **Booking time:** scheduled rides/requests must leave at least 30 minutes from now
  (`BookingPolicy.kt`, with 2 minutes' grace for phone clock drift). ASAP requests are exempt.
- **One trip at a time:** a person can't be in two trips whose times overlap, counting the trip
  length plus a 30-minute buffer either side (`Lifecycle.kt`'s `findTripConflicts`). Driving or
  being an accepted passenger blocks; your own still-waiting requests block offering a ride, and
  on accept they trigger the `OWN_PENDING_REQUEST` conflict (409 with that `code`) so the app can
  ask "Drive them instead?" and retry with `cancelMyConflictingRequests: true`. Repeating
  rides/requests skip clashing days instead of failing.

## Driver approval flow (FR-03 / FR-05)

1. `POST /rides/{rideId}/requests` or `POST /requests` creates a `PENDING` request. It does not
   reserve a seat or change the ride status.
2. Drivers receive pending requests through `GET /requests`. The Android app quietly refreshes
   this inbox every eight seconds while a driver is on a main tab and shows an animated banner.
3. The owning driver accepts or rejects with `PUT /requests/{requestId}`. Acceptance checks trip
   conflicts for both people, performs the authoritative capacity check inside the transaction,
   decrements seats and marks the ride `CONFIRMED`; rejection leaves capacity unchanged.
   Accepting a standalone ask with no ride creates one from the ask's route and time. Once
   accepted, the passenger's other waiting asks for the same time are cancelled.
4. Within 60 seconds, the driver can `POST /requests/{id}/undo-accept`: the request goes back to
   `PENDING`, the seat is returned, a ride created just for that accept is removed, and anything
   the accept auto-cancelled is restored.
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
  estimated arrival, so it appears in trip history and can be rated.

This is polling for the local trial, not a push-notification service. A hosted version should
replace polling with an authenticated realtime channel or platform push notification.

## Matching (FR-04)

Rule-based only, no ML, per the weekly plan (Week 5). `matching/MatchingEngine.kt`:
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
