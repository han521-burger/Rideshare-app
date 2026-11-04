# Weekly Plan Status Notes

Owner: Tony Doan. Tracks what's outstanding from earlier weeks. Update this whenever an item lands.
Editor: Keng Hong. Updates what have been done for myself.

## Build baseline

- Android and backend are both included in the Gradle build.
- The version catalog currently uses AGP 8.13.2 and Gradle 9.5.0; the old 9.3.1 note is obsolete.
- The complete Gradle `check`, debug APK build, and Week 4 emulator flow passed locally.
  Repeat these checks before merging later changes.

## Keng Hong's progress through Week 4

### Week 1 — Android foundation and navigation

Implemented: Kotlin/Compose application, MVVM-style ViewModel/service separation, splash and login,
four-tab navigation, Passenger/Driver mode switching, Home, Requests, Activity, and Profile.
The separate one-time Role Selection screen from the first sketch was formally replaced by the
always-visible Passenger/Driver mode toggle documented under FR-09.

### Week 2 — Profile and driver/passenger forms

Implemented: remotely saved/reloaded profile with User ID, Monash email, display name, preferred
name and passenger availability; Offer a Ride form with route, date/time, seat count and notes;
passenger route/date/time/passenger-count inputs.

Vehicle selection and a stored driver detour-tolerance field are not implemented. Vehicle CRUD is
explicitly deferred in the current RTM, and the backend has no detour-tolerance field to persist.
Do not present decorative UI fields as complete until those cross-stream contracts are agreed.

### Week 3 — Map-based ride creation

Implemented: Yandex map origin/destination selection, route distance and duration, review summary,
publish action, and remote storage. A local Yandex API key is still required for live map routing.
Screens that only preview a route now show a labelled fallback instead of crashing when that local
key is absent.

### Week 4 — Passenger search and request

Implemented on this branch: direction plus station/pickup/drop-off selection, preferred date/time,
passenger count, ranked match results, ride details, seat-request confirmation and PENDING status.
Drivers can review and accept/reject pending requests; acceptance is the point at which capacity is
decremented.

## Since Week 4 — landed on `complete-app-trial`

Some notes above predate these (e.g. Week 2's "vehicle selection not implemented").

- **Vehicles:** drivers register one vehicle before offering a ride; seats offered are capped by
  it, and its fuel type feeds the CO2/fuel estimates.
- **Activity insights:** rule-based analytics (outcome funnel, response time, hotzone, weekday
  pattern, common route, occupancy) and the redesigned Activity tab.
- **Accounts:** email + password sign-up/sign-in, Monash emails only, hashed passwords and
  session tokens; the fixed test user and "sign in as" buttons are gone. Demo accounts use
  password `rideshare123`.
- **Booking rules:** scheduled times must be 30+ minutes ahead; clear "One-time only / Repeat
  weekly" choice when offering or requesting.
- **Lifecycle:** stale requests/rides expire automatically, rides with passengers auto-complete;
  expired asks show "Request again".
- **Conflicts:** nobody can be in two places at once - every request, ride and acceptance is checked for whether the
  person can physically get from one trip to the next (travel time plus a 10-minute buffer). Accepting while you
  have your own waiting request asks "Drive them instead?"; accepting a trip that makes another waiting request
  impossible marks that one "Schedule conflict" (with the reason); Undo for a few seconds after accepting.
- **Scheduling model (Stages 1-9.1):** trip times come from a precomputed directional OSRM/OpenStreetMap matrix on the
  backend (Yandex MapKit only draws maps and previews routes); requests for several rides from one search share an explicit
  journey intent and are alternatives (accepting one cancels the rest), while separate journeys are judged by physical
  feasibility alone; a repeating request reports every date (created / duplicate / doesn't fit) and a retry with the same
  id replays the original answer exactly; when a driver completes a ride its real completion time becomes the
  passenger's and driver's location/time history (`rides.completed_at`; the system's own auto-completion records none),
  waiting requests that no longer fit become "Schedule conflict", and an already-accepted trip that is now hard to reach
  stays accepted with a private "Schedule warning". A seat completes only when its ride does. Details: `docs/BACKEND.md`,
  `docs/TRAVEL_TIMES.md`.
- **Places:** 5 campuses + 25 approved stations/shopping centres with a type-to-search picker;
  campus-to-campus trips allowed. 12 of the new coordinates still need checking on a map.
- **Fixes:** a driver never sees their own request in the banner; swiping the banner just
  dismisses it.
- **Multi-device testing:** debug builds talk to a backend at any address; teammates share one
  backend over Tailscale (`docs/SETUP.md` step 2).

In progress: Keng Hong is restyling the remaining old-style screens (Ride/Request detail,
Messages, Leaderboard, Find a ride, the Offer/Request/Request-seat flows, Map) to match the new
Home/Activity/Profile design.

## Team-level items that are not Member A deliverables

- Real Monash SSO remains blocked on institutional access; email + password accounts stand in.
- HTTPS/hosted deployment is not done - the backend runs on a developer's machine. Session tokens
  are real now, but without HTTPS they travel in plain text outside Tailscale.
- Login lockout (5 wrong passwords → 15 min) is in; change password and delete account are on
  Profile's Security card; forgotten passwords use an admin reset command.
  No email verification or self-service reset (needs email sending).
- The current app has no Room/offline cache; the RTM documents this deliberate trial limitation.
- Allocate+ integration and push notifications remain deferred.

