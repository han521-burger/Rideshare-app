# Testing Conventions

Owner:Tony Doan (QA / security / integration / documentation).
Status: conventions defined ahead of code landing. Concrete test files should be added
alongside the User/Ride/Vehicle/DriverApplication models as Members A/B create them 

## Frameworks

| Layer | Framework | Notes                                                                                              |
|---|---|----------------------------------------------------------------------------------------------------|
| Backend (Spring Boot, Kotlin) | JUnit 5 + MockK + `spring-boot-starter-test` | Pure unit tests for services/validators; reserve that for repository/controller integration tests. |
| Android — domain/ViewModel/repository (JVM, no Android framework dependency) | JUnit 5 + MockK + `kotlinx-coroutines-test` | Runs on the JVM, fast, no emulator needed.                                                         |
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
| Departure equals now (boundary) | departure time == current time | Define and document the chosen boundary rule, then test it |
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
Allowed transitions per the weekly plan's status model:
`PENDING → ACCEPTED`, `PENDING → REJECTED`, `PENDING → CANCELLED`.
Disallowed examples: `REJECTED → ACCEPTED`, `COMPLETED → PENDING`.

| Case | Transition | Expected result               |
|---|---|-------------------------------|
| Accept a pending request | PENDING → ACCEPTED | Allowed                       |
| Reject a pending request | PENDING → REJECTED | Allowed                       |
| Cancel a pending request | PENDING → CANCELLED | Allowed                       |
| Re-accept a rejected request | REJECTED → ACCEPTED | Rejected                      |
| Revert a completed ride | COMPLETED → PENDING | Rejected  |

