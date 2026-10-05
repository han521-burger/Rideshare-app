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
- `demo-driver-001` with one vehicle (`demo-vehicle-001`), so there's something to search/match
  against immediately.

From an Android emulator, the backend is reachable at `http://10.0.2.2:8080`.

## Auth (trial-only, read this before assuming it's secure)

There's no real Monash SSO yet (RTM FR-01 - blocked on Gate G1, SAML metadata from Monash IT).
Instead, accounts use a Monash email + password:
- `POST /auth/register` `{displayName, email, password}` - Monash emails only, password 8+ chars.
- `POST /auth/login` `{email, password}` - both return `{token, user}`.
- `POST /auth/logout` - ends the session for the token sent.

Passwords are stored as PBKDF2 hashes (`Passwords.kt`). Every other request must send
`Authorization: Bearer <token>`; `Auth.kt`'s `requireUserId()` resolves it via the `sessions`
table (30-day expiry). Backend tests may send a userId as the token instead - only because the
test task sets `rideshare.test.trustUserIdTokens`. Still missing before any real deployment:
HTTPS, email verification, password reset and login rate limiting. Swap this for real OIDC/JWT
verification once Monash SSO access is granted.

## Endpoints

- `GET /health` -> `"API online"`
- `GET/PUT /users/me`, `GET /users/{id}/rides` (self only)
- `GET /rides` (filter: `date`, `originLat/Lng`, `destinationLat/Lng`, `radiusMeters`, `availableOnly`)
- `GET /rides/match` (ranked search - see Matching below)
- `POST /rides`, `GET/PUT/DELETE /rides/{id}`
- `POST /rides/{rideId}/requests`, `GET /requests` (driver, own rides), `GET /requests/mine`
  (passenger), `PUT /requests/{requestId}` (accept/reject/cancel)
- `GET/POST /rides/{rideId}/messages` (driver + accepted passengers only)
- `GET /users/me/dashboard`, `GET /users/me/trip-history`

## Driver approval flow (FR-03 / FR-05)

1. `POST /rides/{rideId}/requests` creates a `PENDING` request. It does not reserve a seat or
   change the ride status.
2. Drivers receive pending requests through `GET /requests`. The Android app quietly refreshes
   this inbox every eight seconds while a driver is on a main tab and shows an animated banner.
3. The owning driver accepts or rejects with `PUT /requests/{requestId}`. Acceptance performs the
   authoritative capacity check inside the transaction, decrements seats and marks the ride
   `CONFIRMED`; rejection leaves capacity unchanged.
4. A passenger can cancel a pending request. Cancelling an accepted request returns its seats.

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
- No HTTPS - this only runs on localhost/emulator right now. TLS termination is a hosting decision
  the team hasn't made yet.
