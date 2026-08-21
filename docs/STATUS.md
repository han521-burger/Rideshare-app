# Weekly Plan Status Notes

Owner: Tony Doan. Tracks what's outstanding from earlier weeks. Update this whenever an item lands.
Editor: Keng Hong. Updates what have been done for myself.

Last reviewed: 2026-08-21 on `complete-app-trial`.

## Build baseline

- Android and backend are both included in the Gradle build.
- The version catalog currently uses AGP 8.13.2 and Gradle 9.5.0; the old 9.3.1 note is obsolete.
- The complete Gradle `check`, debug APK build, and Week 4 emulator flow passed locally on
  2026-08-21. Repeat these checks before merging later changes.

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

## Team-level items that are not Member A deliverables

- Real Monash SSO remains blocked on institutional access; the app uses test authentication.
- HTTPS/hosted deployment and real token verification are not complete.
- The current app has no Room/offline cache; the RTM documents this deliberate trial limitation.
- Allocate+ integration and push notifications remain deferred.

