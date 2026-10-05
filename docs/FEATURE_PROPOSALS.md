# Feature Proposals

## Where these stand (2026-10-06)

Most of this list has since been built on `complete-app-trial`. The original proposals below are
kept as written, for the reasoning behind each.

| # | Proposal | Status |
|---|---|---|
| 1 | Wire ride matching into the UI | **Built.** Find a ride calls `GET /rides/match` and shows ranked results; the unfiltered "Rides I can join" list stays as a secondary path. |
| 2 | Vehicle management | **Built.** One vehicle per driver, required before offering a ride or switching to Driver mode; seats offered are capped by it and its fuel type feeds CO2/fuel estimates. |
| 3 | Map fixed tap points | **Replaced** by an approved-places list: 5 Monash campuses + 25 stations/shopping centres, chosen via postcode/suburb search or a type-to-search field. One end of every trip must be a campus (campus-to-campus allowed). |
| 4 | Messaging improvements | **Open.** Still manual Refresh; no auto-poll while open or unread badge. On hold while the Messages screen is being restyled. |
| 5 | Analytics | **Built** (rule-based): outcome funnel, response time, hotzone, weekday pattern, common route, occupancy, CO2 leaderboard. ETA estimation and prediction not done (need new data). |

Open questions from the end of this doc, as decided: matching keeps browsing as a fallback;
offering a ride requires a saved vehicle; the place list is the 30 approved places above;
messaging poll interval still undecided.

Real Monash SSO (FR-01) was out of scope for this pass. Email + password accounts (Monash emails
only) now stand in for it - see `docs/BACKEND.md`.

---

*Original draft (written before any of the above was built):*

## 1. Wire ride matching into the UI (FR-04)

**What already exists:**
- Backend: `backend/.../matching/MatchingEngine.kt` is a complete rule-based pipeline. Hard
  gates first (enough seats, same calendar day, within a 45-minute window, same rough direction
  within ±45°, pickup within 3 km), then survivors get a weighted score — route similarity 40%,
  time compatibility 30%, pickup proximity 20%, detour cost 10%. If nothing survives the gates it
  falls back to just direction + time window. Exposed at `GET /rides/match`.
- Android: `BackendApi.matchRides()` in `Repository/Remote/BackendApi.kt` is already defined and
  ready to call. Nothing calls it yet.
- What passengers actually see today: the Requests tab's "Rides I can join" sub-tab
  (`RequestsScreen.kt` → `BrowseRidesList(openRides, onOpenRide)`) just lists every `OPEN` ride,
  unfiltered and unranked.

**Idea:** add a small search step in front of "Rides I can join" — reuse the origin/destination +
time picker already built for `RequestRideFlowScreen`, and a "Find rides" action that calls
`matchRides()` instead of the plain open-rides list.

- Each result card gets a match-quality badge — bucket `score.total` into high/med/low bands
  rather than showing a raw number like "78%", which doesn't mean much on its own.
- An optional "why this match" expansion shows the four component scores the backend already
  returns (`MatchScoreDto`: route/time/pickup/detour) — effectively free, the numbers already
  exist.
- Keep a "Show everything" toggle that falls back to today's unfiltered list. Cheap safety net,
  and useful when a passenger is flexible on time or route.
- The driver's accept/reject stays exactly as it is — matching only ranks candidates, it never
  auto-books (see `docs/BACKEND.md`).

**Effort read:** almost entirely a UI task. No backend changes, no new network plumbing — the
Retrofit method already exists and is unused.

## 2. Vehicle management

**Current state:** `vehicleId` is hardcoded to `null` in `OfferRideFlowScreen.kt` — there is no
vehicle picker anywhere in the app. Only the seeded backend demo data (`demo-vehicle-001`) has a
vehicle attached to a ride. The `vehicles` table itself is complete server-side (`Vehicles` in
`Tables.kt`: make, model, variant, year, seat capacity, fuel type, masked registration, owned by
a user) — this is a client-side gap, not a schema gap.

**Idea, smallest useful version first:**
- **Profile → My Vehicles**: a list + "Add vehicle" form (make, model, year, seat capacity, fuel
  type, optional registration). No new backend table, no validation concepts beyond what's
  already covered for other forms in `docs/TESTING.md`.
- **Offer-a-Ride step**: once a driver has at least one saved vehicle, let them pick from it
  instead of always posting `vehicleId = null`.
- Fuel type isn't just cosmetic — it directly feeds `Emissions.kt`'s CO2/fuel-saved calculation.
  Real fuel types instead of `null` make the sustainability dashboard and leaderboard numbers
  mean something per-driver, instead of only reflecting the seeded demo data.
- **Not** proposing driver evidence/verification (licence, registration upload, reviewer
  approval) yet — `SECURITY_CHECKLIST.md`'s SAML section already flags that as needing protected
  remote object storage, and it's tangled up with real auth, which isn't a priority right now.

**Open question:** should offering a ride require at least one saved vehicle, or keep allowing
`vehicleId = null` the way it works today? Changes onboarding friction either way.

## 3. Simplify the map to fixed tap points

**Current state:** `MapScreen.kt`'s `InputListener.onMapTap` accepts a tap **anywhere** on the
map as origin, then anywhere else as destination — full freeform lat/lng, routed through Yandex
driving directions. Separately, `NAMED_LOCATIONS` (`ui/NamedLocations.kt`) already exists — 5
points (Melbourne CBD, Oakleigh, Chadstone, Glen Waverley, Monash Clayton) shared with the
backend's seed data — but today it's only used for the map's "jump to" camera shortcuts and as a
plain button list inside `RequestRideFlowScreen` / `OfferRideFlowScreen`, not as tappable points
on the map itself.

**Idea:**
- Render each `NAMED_LOCATION` as a placemark directly on the map.
- Change `onMapTap` so it only accepts a tap that lands on (or within a small radius of) one of
  those placemarks — a tap elsewhere becomes a no-op or a "pick one of the marked points" hint,
  instead of dropping a pin anywhere.
- **Grow the list.** 5 points is thin for a Clayton-campus-centered app and clusters everyone's
  pickup point onto only 5 spots. Worth adding more train stations and landmarks along common
  routes — but which ones should come from actual route/survey data the team already has, not
  guesses.
- This also simplifies `describeLocation()` (already snaps to the nearest named location within
  1.5 km) and removes the "no route found" / stray-tap failure mode entirely, since every
  tappable point is guaranteed reachable.
- The underlying Yandex driving-route request between the two confirmed points doesn't change —
  only how origin/destination get *selected* is constrained, not the routing itself.

**Open question:** source of truth for the expanded location list — is there survey data on
common routes to pull from, or should it just be "stations within N km of Monash Clayton"?

## 4. Messaging — current workflow and where it's thin

**Current workflow:** `MessagesScreen` opens from `RideDetailScreen` once a seat is confirmed —
driver plus accepted passengers only, enforced server-side (see `docs/BACKEND.md`'s messages
endpoint). It's a flat list of text bubbles (sender, body, timestamp) with one text field and a
Send button. No websocket or push — refreshed by a manual "Refresh" action or by re-entering the
screen, consistent with the rest of the app's polling model (the RTM's "Deferred" list explicitly
rules push out of scope for this trial).

**Gaps worth knowing before deciding what to add:**
- No auto-refresh while the screen is open — two people mid-conversation have to keep tapping
  Refresh.
- No unread indicator anywhere else in the app (Requests tab, ride card) — a new message is
  invisible until you open that specific ride's chat.
- No read receipts, typing indicator, or delivery state — this matches the original "basic text
  messaging" scope, not a regression.
- Group chat isn't a concept, and doesn't need to be — it's one ride's driver plus its accepted
  passengers in a single thread, which is already the right model.

**Reasonable next steps, roughly in order of value for effort:**
1. **Poll while the screen is open** — e.g. every 5–10s via a coroutine loop, cancelled on screen
   exit. Closes the "keep tapping Refresh" gap with no new infra.
2. **Unread badge** — cheapest version is a `hasUnreadMessages` flag on the ride summary, derived
   by comparing the latest message timestamp against a per-user "last read" marker. Surface it on
   the Requests/Home ride card.
3. Real push (FCM) stays explicitly out of scope per the RTM's deferred list — flagging this so
   the above doesn't quietly grow into that project.

**Open question:** is a 5–10s poll interval fine for battery/traffic at trial scale, or should it
back off when the app isn't in the foreground?

## 5. More data-driven / analytics features ("data science stuff")

**What's already collected and computed:** completed-trip distance, fuel type, and seats filled
feed `Emissions.kt` for CO2-avoided and fuel-saved; the Activity tab's dashboard shows totals plus
a monthly breakdown; the leaderboard ranks users campus-wide by CO2 avoided, computed on the fly
per request.

In the same rule-based, no-ML spirit the matching engine already sets (deliberately simple,
explainable, no training infra — see `docs/BACKEND.md`):

- **Personal insight cards** on the Activity tab: most-used route ("You've ridden Clayton → CBD 6
  times this month"), a driver's own seat-fill-rate trend over their last N rides.
- **Route heatmap / demand signal**: aggregate origin/destination pairs across completed + open
  rides into a "busiest routes this week" list — helps a driver decide *where* to offer a ride
  instead of only confirming one after the fact. Pure aggregation, no prediction needed for v1.
- **Acceptance-rate stat per driver**, shown to passengers before they request (e.g. "responds to
  ~80% of requests within a day") — turns history already sitting in `RideRequests` into a trust
  signal, in the same spirit as the existing rating system.
- **Leaderboard trend line**, not just a snapshot rank — the dashboard already computes
  `monthlyTripSummary`; the same shape extends to "your CO2-avoided rank over the last 3 months"
  as a small sparkline.
- **Simple demand forecasting** (stretch, only with appetite for it): a naive "same weekday/time
  last week" baseline for expected ride volume on a route. Deliberately descriptive stats over
  `Rides`/`RideRequests`, not a trained model — same reasoning the matching engine used to avoid
  ML (explainability, no training infra, trial-scale data).

All five read directly off tables that already exist (`Rides`, `RideRequests`, `Vehicles`,
completed trips) — none need new data collection to get a first version working.

### Latest brainstorm (2026-09-20)

Raw list from the team: visualisation, prediction, informative dashboard/board (estimates from
known values, no real data needed), data analysis of patterns over time, matching funnel
analysis, hotzone, ETA estimation based on time/conditions. Mapped against what the schema
actually supports:

**Buildable now, no schema changes:**
- **Matching funnel analysis** — `RideRequests` already has `status`
  (PENDING → ACCEPTED/REJECTED/CANCELLED/COMPLETED) plus `createdAt`/`respondedAt`. Gives
  conversion rate at each stage, average time-to-response, and per-driver acceptance rate
  straight from existing rows.
- **Hotzone** — since drop points are constrained to the 5 `NAMED_LOCATIONS`
  (`ui/NamedLocations.kt`), this is a group-by-count over `Rides.originLat/Lng` and the
  standalone-request origin fields, matched back to that fixed set. Same underlying data as the
  "route heatmap" idea above — same feature, different name.
- **Data analysis of patterns over time** — extends the existing `monthlyTripSummary` logic in
  `DashboardRoutes.kt` to day-of-week / hour-of-day distributions from `Rides.departureTime`.
  Same underlying data as the "leaderboard trend line" idea above.
- **Informative dashboard/board** — already the established pattern (CO2/fuel-saved dashboard).
  "Estimates from known values, no real data needed" fits this codebase well since trial-scale
  seeded data is thin — more tiles (busiest route, average detour, average response time) reuse
  the same derive-from-existing-rows approach, no new collection required.
- **Visualisation** — not a standalone feature, it's the presentation layer for the above (bar/heat
  view for hotzone, line chart for patterns-over-time, funnel chart for the matching funnel).

**Needs new data collection first:**
- **ETA estimation based on time/conditions** — `Rides.estimatedDurationSeconds` is a one-shot
  static value from the Yandex routing call at ride-creation time; there's no logged *actual*
  completion time to compare against, and no "conditions" signal beyond `departureTime` itself.
  Doing this for real (not just re-displaying the static estimate) needs a small addition: record
  actual completion time and bucket estimated-vs-actual by hour-of-day.
- **Prediction** — cheap if paired with *patterns-over-time* (naive "same weekday/time last week"
  baseline off existing history — this is the same idea as "simple demand forecasting" above).
  Expensive if paired with *ETA*, for the same reason ETA itself needs new data.

**Suggested build order** (effort vs. payoff, and what needs no new instrumentation): matching
funnel → hotzone → patterns-over-time (+ naive next-period prediction) → dashboard tiles → ETA
estimation last, since it's the only one blocked on new data collection rather than analysis of
what's already stored.

---

## Open questions before any of this gets built

- **Matching:** does "Show everything" stay as a permanent fallback, or should Find Rides fully
  replace browsing?
- **Vehicle management:** block offering a ride with zero saved vehicles, or keep allowing
  `vehicleId = null`?
- **Map:** which extra named locations to add, and what's the source for "common routes"?
- **Messaging:** is a 5–10s poll interval acceptable, or worth throttling further?
- **Analytics:** which of the five ideas above is worth doing first, or does this deserve its own
  priority pass instead of riding along with the others?
