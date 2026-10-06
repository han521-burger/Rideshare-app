# Testing Conventions

Owner:Tony Doan (QA / security / integration / documentation).
Status: conventions plus initial Android and Ktor integration tests are present. Add coverage
beside each new validation or state transition.

## Frameworks

| Layer | Framework | Notes                                                                                              |
|---|---|----------------------------------------------------------------------------------------------------|
| Backend (Ktor, Kotlin) | JUnit 5 + Ktor `testApplication` | Route integration tests use a temporary SQLite database; pure matching/validation logic can use unit tests. |
| Android — domain/ViewModel/repository (JVM, no Android framework dependency) | JUnit 4 (`libs.junit`); fakes are hand-written (e.g. a subclass of `FakeRideApiService`) - no MockK | Runs on the JVM, fast, no emulator needed. `./gradlew :app:testDebugUnitTest` |
| Android — Compose UI / instrumented | JUnit 4 + AndroidX Test + Compose Test rule | AndroidX's instrumented test runner requires JUnit 4, not JUnit 5                                  |

## Naming and structure

- Test class name: `<ClassUnderTest>Test`, e.g. `RideValidatorTest`.
- Test method names use backtick-quoted descriptive sentences (Kotlin convention):
  `` fun `rejects ride with zero seat capacity`() ``.
- Structure each test body as Given / When / Then (as comments or blank-line sections), not
  as an unstructured block.
- Test source mirrors main source: `src/test/kotlin/.../feature/rides/RideValidatorTest.kt`
  next to `src/main/kotlin/.../feature/rides/RideValidator.kt`.
- One behaviour per test. Prefer several small tests over one test with multiple unrelated
  assertions.

## What must have tests before merge (Definition of Done, testing clause)

Per the weekly build plan, any PR/MR touching validation or state-transition logic needs
tests covering both the valid and invalid paths.

## What's covered today (2026-10-06)

Run everything with `./gradlew :backend:test :app:testDebugUnitTest` (JVM, no emulator) and
`./gradlew :app:connectedDebugAndroidTest` (needs a running emulator/device). At last run:
111 backend tests and 114 app unit tests, all passing.

**Backend (`backend/src/test/...`)** — each test boots the real Ktor app on a temporary SQLite
file. The Gradle test task sets `rideshare.test.trustUserIdTokens`, letting tests call as a
seeded user by sending its userId as the token; `AuthTest` switches that off to test real sessions.

| Suite | Covers |
|---|---|
| `AuthTest` | Sign up → token works → sign out → token rejected → sign in again; Monash-only emails, 8+ char passwords, duplicate emails; same error for wrong password and unknown email; bare userIds rejected; demo password works; passwords stored hashed |
| `AccountSecurityTest` | 5-wrong-password lockout and its 15-minute expiry, success clearing attempts, unknown emails locking too; change password keeping this device and signing out others; admin reset; deleting a driver (rides + bookings cancelled, details wiped, email reusable) and a passenger (seat returned) |
| `DeclineRequestTest` | Declining an open ask hides it for that driver only (still PENDING, others can accept, passenger can't decline their own); declining on your own ride still rejects |
| `RideRequestApprovalTest` | Request stays PENDING, capacity unchanged until the owning driver accepts; other drivers get 403; rejection keeps capacity |
| `RequestCancellationTest`, `RideCancellationPropagationTest` | Cancelling requests/rides, seat restoration, who may cancel, what cascades |
| `RideCompletionLifecycleTest` | Completing rides and requests, allowed transitions, ownership |
| `LifecycleTest` | Auto-expiry (past scheduled, 30-min-old ASAP, empty past rides), expired requests can't be accepted; "drive them instead" conflict + undo restoring everything; a booked passenger can't drive or book again at the same time |
| `StandaloneRequestSchedulingTest` | Repeat-weekly asks become one request per day; ASAP never repeats; two drivers racing for the last seat - exactly one wins |
| `VehicleRegistrationTest` | One vehicle per account, field validation, seat caps on offered rides, ownership |
| `MatchingEngineTest`, `MatchingRouteTest` | Matching gates, scoring and the `GET /rides/match` route |
| `AnalyticsRoutesTest`, `AnalyticsCorrectnessTest` | Dashboard/analytics numbers |

**App unit tests (`app/src/test/...`)** — presentation logic and ViewModel state:
`LoginValidationTest` (sign-in/create-account form rules), `AccountSecurityTest` (change-password
form rules; deleting the account signs the device out, a failed delete doesn't), `FindRideSearchTest` (search form
rules), `ApprovedPointSearchTest` + `PlaceSearchFieldTest` (approved points, campus-to-campus,
type-to-search), `ActivityRefreshTest` (switching accounts never shows the previous account's
data), `DriverAccessStateTest` / `DriverGateViewModelTest` (Driver mode needs a vehicle),
`RequestRefreshGateTest`, and the `*PresentationTest` files for Home, Activity, Profile,
Vehicles and Requests.

**Instrumented UI tests (`app/src/androidTest/...`)** — Compose tests for Home, Activity,
Profile, Vehicles, Requests, completing a ride, and accessibility semantics.

**Domain logic suite (`...domain` packages, JVM only, no emulator):** one place to point at when
asked "is the logic tested separately from the UI?" - routing and hub data, nearest-hub selection,
seat and vehicle rules, booking/overlap rules, the CO2 formula, impact aggregation and the demo-history
generator/seeder. Run just these with:

```
./gradlew :app:testDebugUnitTest --tests "*.domain.*"
./gradlew :backend:test --tests "*.domain.*"
```

- App (`app/src/test/.../domain`): `ApprovedLocationLogicTest` (25 hubs + 5 campuses unique and valid,
  haversine, nearest-hub ranking: known references, brute-force agreement, stable ties, prefix
  property) and `VehicleRulesParityTest` (the app's fuel/body/seat choices match the backend's rules
  and emissions factors; the backend source is parsed because the modules cannot import each other).
  `ApprovedLocationLogicTest` also pins that the app's hub/campus lists equal the backend's.
- Backend (`backend/src/test/.../domain`): `EmissionsTest`, `HubDatasetTest`, `TripRulesTest`,
  `SeatAndVehicleRulesTest`, `ImpactAggregationTest` (only COMPLETED driven rides count; passenger and
  non-completed rides do not), `DemoHistoryGeneratorTest`, `DemoHistorySeederTest` (explicit target,
  idempotent, removable, valid under the schema, and the real dashboard/analytics compute from it).

**Not covered by automated tests yet:** the Offer/Request ride step flows (one-time/repeat
choice, 30-minute rule in the pickers), the conflict pop-up and Undo bar in the UI, sign-in
screen UI, expired-request "Request again", and Messages. These are checked by hand on two
devices (see `docs/SETUP.md` step 5).

## Validation test matrix (Week 2 scope)

These are the four validation areas the weekly plan assigns to Member D for Week 2. Fill in
concrete test cases as the corresponding model/validator classes are created; this table is
the spec both the test author and the reviewer check the PR against.

### User validation
| Case | Input | Expected result |
|---|---|---|
| Valid profile | preferred name set, terms accepted | Accepted |
| Missing preferred name | blank/null preferred name | Rejected |
| Terms not accepted | `termsAccepted = false` | Rejected |
| Update by non-owner | user A attempts to update user B's profile | Rejected |

### Ride date/time validation
| Case | Input | Expected result |
|---|---|---|
| Valid future ride | departure time tomorrow | Accepted |
| Past departure time | departure time before now | Rejected |
| Too soon (boundary) | departure less than 30 minutes from now | Rejected - rule: scheduled departures must be ≥ 30 min ahead (`BookingPolicy.kt`, 2 min grace for clock drift; app pickers enforce the same). ASAP requests are exempt |
| Missing date/time | null departure time | Rejected |

### Seat validation
| Case | Input | Expected result |
|---|---|---|
| Valid seat count | `seatCapacity = 3`, `availableSeats = 3` | Accepted |
| Zero seat capacity | `seatCapacity = 0` | Rejected |
| Negative seats | `seatCapacity = -1` | Rejected |
| Request exceeds availability | request for 2 seats, 1 available | Rejected |
| Concurrent accept race | two requests each for the last seat | Exactly one succeeds |

### Status transition validation
Allowed transitions (`RideRequestStatus.transitions` in `Tables.kt`):
`PENDING → ACCEPTED / REJECTED / CANCELLED`, `ACCEPTED → COMPLETED / CANCELLED`.
The server also moves `PENDING → EXPIRED` and `ACCEPTED → COMPLETED` on its own (`Lifecycle.kt`),
and `undo-accept` moves `ACCEPTED → PENDING` within 60 seconds for the accepting driver only.
`REJECTED`, `CANCELLED`, `COMPLETED` and `EXPIRED` are final.

| Case | Transition | Expected result               |
|---|---|-------------------------------|
| Accept a pending request | PENDING → ACCEPTED | Allowed                       |
| Reject a pending request | PENDING → REJECTED | Allowed                       |
| Cancel a pending request | PENDING → CANCELLED | Allowed                       |
| Re-accept a rejected request | REJECTED → ACCEPTED | Rejected                      |
| Revert a completed ride | COMPLETED → PENDING | Rejected  |
| Accept an expired request | EXPIRED → ACCEPTED | Rejected ("This request has expired") |
| Undo an accept after 60 s | ACCEPTED → PENDING via undo | Rejected ("Too late to undo") |

