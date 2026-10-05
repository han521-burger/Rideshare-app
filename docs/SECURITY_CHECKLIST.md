# Security Checklist (NFR-01)

Owner: Tony Doan. Run this pass every sprint

## Baseline checklist (from the weekly build plan)

- [ ] HTTPS only, no cleartext traffic allowed in normal builds
- [ ] No credentials, keys, or tokens committed to Git
- [ ] No backend secrets shipped inside the Android APK
- [ ] Authentication required on all protected endpoints
- [ ] A user cannot modify another user's profile
- [ ] A passenger cannot modify a driver's ride
- [ ] A driver cannot view unrelated users' private data
- [ ] Server-side input validation on every write endpoint
- [ ] Error messages returned to the client do not reveal server internals
- [ ] Logs never contain tokens, passwords, or full session identifiers

### Review notes — 2026-10-06 (`complete-app-trial`, code review, not yet a sprint sign-off)

Boxes above are left for the sprint review to tick; this is what the code currently shows.

| Item | Current state |
|---|---|
| HTTPS only | **Not met.** No hosting, so no TLS. Debug builds allow plain HTTP to any host (for LAN/Tailscale testing); release builds allow only emulator/localhost. Cross-machine testing goes over Tailscale, which encrypts traffic between devices. |
| No credentials/keys/tokens in Git | **Partly.** The Yandex MapKit key is committed on purpose (`secrets.defaults.properties`, shared team key). Demo account password `rideshare123` is in the code and docs - demo data only. No session tokens or real user passwords are committed. |
| No backend secrets in the APK | **Met for backend secrets** - the APK holds only the backend URL and the client-side Yandex key. |
| Auth on all protected endpoints | **Met.** Every route calls `requireUserId()` except `/health` and `/auth/register`, `/auth/login`, `/auth/logout`. Tokens are random 32-byte session tokens checked against the `sessions` table with a 30-day expiry (`AuthTest` covers rejection of signed-out tokens and bare userIds). |
| Can't modify another user's profile | **Met by design** - `PUT /users/me` only ever edits the caller. |
| Passenger can't modify a driver's ride | **Met** - ride update/cancel/complete, request responses and undo all check ownership. Tested for non-owners in `RideCompletionLifecycleTest`, `RideCancellationPropagationTest`, `RequestCancellationTest`, `RideRequestApprovalTest`; vehicles in `VehicleRegistrationTest`. |
| Driver can't view unrelated private data | **Mostly** - ride/request summaries expose display name, phone (if set) and rating only. Not yet covered by a dedicated test. |
| Server-side validation on writes | **Mostly** - seats, Monash-only emails, password length, campus endpoint, 30-min booking lead, overlapping trips, vehicle fields are validated server-side. Not every free-text field has a length limit. |
| Errors don't reveal internals | **Met** - `Errors.kt` maps unknown failures to a generic 500 message. |
| Logs never contain tokens/passwords | **Likely met, not yet verified by grepping logs** - backend logs the path on unhandled errors only; the app's debug HTTP log is `BASIC` (method + URL, no headers or bodies). |
| Passwords | Stored as PBKDF2-HMAC-SHA256 hashes (210k iterations, random salt). Wrong email and wrong password give the same error. **Missing:** login rate limiting / lockout, password reset, email verification. |

## Additional items from the SAML/auth architecture guide

Once Work Package A (SAML2 authentication) lands, also verify:

- [ ] Backend validates the SAML assertion's signature, audience, and timing (`NotOnOrAfter`)
- [ ] Real access/refresh tokens never appear in a redirect URL
- [ ] The exchange code is single-use, short-TTL, and redeemable only over an authenticated HTTPS call
- [ ] Backend independently re-checks role/ownership/verification status on every protected call
- [ ] Driver evidence (licence, registration, insurance) is stored in protected remote object storage, never in Room, never in the same bucket as public profile photos
- [ ] Reviewer access to driver evidence is role-restricted and audit-logged

## Week 3 test cases (spec, pending code)

Concrete cases to turn into integration tests once the backend endpoints from the checklist
above exist — same idea as the validation matrix in `docs/TESTING.md`. Not runnable yet; see
`docs/STATUS.md` for the blocker.

| Checklist item | Test case |
|---|---|
| Auth required on protected endpoints | Call `GET /users/me`, `POST /rides`, etc. with no/expired token → expect 401, not data |
| User cannot modify another user's profile | User A sends `PUT /users/me` payload for user B's `userId` → expect 403/ownership rejection, B's data unchanged |
| Passenger cannot modify a driver's ride | Passenger calls update/delete on a ride they don't own → expect 403 |
| Driver cannot view unrelated private data | Driver requests another user's profile/ride-request detail not linked to their own ride → expect 403/404, not silent success |
| Input validation server-side | Resend a request with a client-bypassed invalid payload (e.g. negative seats) directly to the API → expect rejection, not reliance on app-side validation |
| Error messages don't leak internals | Trigger a server error (bad input, DB failure) → response body has no stack trace, SQL, or file paths |
| Logs don't contain tokens | Grep application logs after a login + API call flow → no bearer tokens, passwords, or full session IDs present |
| No backend secrets in APK | Decompile/inspect release APK strings → no API keys, DB credentials, or signing secrets present |

## Process

- Review this checklist against the current `main` branch at the end of each sprint.
- Any unchecked item found during review becomes a P0/P1 issue depending on exploitability,
  filed with the Bug issue template and linked to NFR-01.
