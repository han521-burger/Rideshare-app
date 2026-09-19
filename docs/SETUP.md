# Setup & Launch Guide

For anyone opening the `complete-app-trial` branch for the first time and trying to get it
running end to end (backend + Android app talking to each other, on an emulator or a real
device).

## What you need before you start

- **Android Studio**, a release compatible with AGP 8.13.2 (that's the version pinned in
  `gradle/libs.versions.toml` right now — if your Studio complains about the AGP version, update
  Studio rather than downgrading AGP).
- **JDK 11** (the app module's `sourceCompatibility`/`jvmTarget` — Android Studio usually bundles
  a compatible JDK already, you don't need to install one separately).
- An **Android emulator or physical device running Android 8.0 (API 26) or newer** — `minSdk 26`
  is a hard requirement of Yandex MapKit, not an arbitrary choice.
- A **Yandex MapKit API key**. The repo now ships a shared team key in
  `secrets.defaults.properties` (committed), so a fresh clone builds with a working map and you
  don't need to do anything. You can still override it with your own key in `local.properties`.

## 1. Clone and check out the branch

```
git clone <repo-url>
cd ds04_monash_rideshare_application
git checkout complete-app-trial
```

## 2. Set your local config

Create (or edit) `local.properties` in the project root — this file is gitignored, every
developer has their own copy:

```properties
sdk.dir=<path to your Android SDK>
# YANDEX_MAPKIT_API_KEY=<your own key>   # optional — overrides the shared team key
```

`sdk.dir` is normally filled in automatically the first time you open the project in Android
Studio. The Yandex MapKit key already comes from the committed `secrets.defaults.properties`, so
you only add `YANDEX_MAPKIT_API_KEY` here if you want to use a different key. If you're testing against a **physical device** rather than an emulator, also add:

```properties
BACKEND_BASE_URL=http://<your computer's LAN IP>:8080/
```

(Leave this out for emulator use — it defaults to `http://10.0.2.2:8080/`, which is how the
Android emulator reaches your host machine's `localhost`. A physical device can't resolve
`10.0.2.2`, so it needs your machine's real LAN IP instead, and both device and computer need to
be on the same network.)

## 3. Start the backend

The backend is a separate Gradle module (`backend/`) — Kotlin + Ktor + Exposed + SQLite, chosen
specifically so it runs locally with zero external setup (see `docs/BACKEND.md` for why).

```
./gradlew :backend:run
```

- Starts on `http://localhost:8080`.
- Uses a SQLite file at `backend/rideshare-backend.db` — gitignored, created automatically on
  first run.
- On first run it seeds two accounts so there's something to actually test against:
  - `fake-user-001` — matches the Android app's `FakeAuthenticationProvider`, so signing in on
    the app and hitting the backend works immediately with no manual account setup.
  - `demo-driver-001` with one seeded vehicle (`demo-vehicle-001`), so there's a ride to
    search/match against right away.
- Confirm it's actually up before moving on: `curl http://localhost:8080/health` should return
  `"API online"`.

Leave this running in its own terminal — the Android app has nothing to talk to without it.

## 4. Run the Android app

Open the project root in Android Studio, let it sync Gradle, then run the `app` configuration on
your emulator/device.

- On an emulator, no extra config needed — it already points at `10.0.2.2:8080` by default.
- On a physical device, make sure step 2's `BACKEND_BASE_URL` override is set and that your
  computer's firewall allows inbound connections on port 8080 from your local network.

You should land on a login screen backed by `FakeAuthenticationProvider` — there's no real Monash
SSO in this trial (that's intentional, see `docs/RTM.md` FR-01). A test-account switcher exists on
the Login screen if you need to test as more than one seeded user.

## 5. Sanity-check it actually works

A quick end-to-end smoke test once both are running:

1. Log in on the app (uses the seeded `fake-user-001`).
2. Switch the top-bar mode toggle to Driver, go to the Requests tab → My Offers, and offer a ride
   (the map/location step uses the fixed named locations — Melbourne CBD, Oakleigh, Chadstone,
   Glen Waverley, Monash Clayton — not freeform search).
3. Switch back to Passenger mode, check the Requests tab's "Rides I can join" — your own new ride
   is excluded from this list on purpose (drivers don't see their own posted rides as
   joinable), but the seeded `demo-driver-001` ride should show up.
4. Request a seat, then switch back to Driver mode and accept it from the Requests tab.
5. Open the ride and confirm Messages, Share, and the SOS control are all reachable from
   `RideDetailScreen`.

If any of that breaks, check the backend terminal for a stack trace before assuming it's an app
bug — most first-run failures are the app not being able to reach the backend at all (wrong
`BACKEND_BASE_URL`, backend not started, or a firewall blocking the device).

## Known rough edges (don't lose time chasing these as new bugs)

- **No HTTPS** — the backend only runs on localhost/emulator right now by design (see
  `docs/BACKEND.md`). This is expected in dev, not a bug to fix locally.
- **No offline mode** — the Android-side Room cache was removed entirely; every screen needs the
  backend reachable. If the backend terminal isn't running, expect friendly error messages
  app-side (`NetworkErrorMapper`), not a crash — but nothing will actually load.
- **Everything is polling, nothing is push** — message refresh, request badges, and ride status
  updates only refresh when you navigate to a screen or manually refresh. This is intentional for
  the trial (see `docs/RTM.md`'s "Deferred" section), not a missing feature.
- Real Monash SSO isn't wired up (`FakeAuthenticationProvider` stands in) and isn't currently a
  priority for this branch.

## Where to go next

- `docs/RTM.md` — up-to-date requirement-by-requirement status (what's done, in progress, or
  deliberately deferred).
- `docs/BACKEND.md` — endpoint list, matching algorithm details, and known backend limitations.
- `docs/FEATURE_PROPOSALS.md` — draft ideas for the next round of work (matching UI, vehicle
  management, map simplification, messaging, analytics) — not yet built, open for review.
- `docs/TESTING.md` / `docs/SECURITY_CHECKLIST.md` — conventions and specs for tests that mostly
  don't exist yet; a good place to start contributing if you're not sure where to jump in.
