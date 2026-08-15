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
