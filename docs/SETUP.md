# Setup & Launch Guide

For anyone opening the `complete-app-trial` branch for the first time and trying to get it
running end to end (backend + Android app talking to each other, on an emulator or a real
device).

## What you need before you start

- **Android Studio**, a release compatible with AGP 8.13.2 (that's the version pinned in
  `gradle/libs.versions.toml` right now — if your Studio complains about the AGP version, update
  Studio rather than downgrading AGP).
- **JDK 17** for the backend (`jvmToolchain(17)` in `backend/build.gradle`); the app module itself
  targets Java 11. Android Studio's bundled JDK is usually fine for both.
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
you only add `YANDEX_MAPKIT_API_KEY` here if you want to use a different key.

`BACKEND_BASE_URL` tells the app where the backend is. Pick one:

| Situation | `BACKEND_BASE_URL` |
|---|---|
| Emulator, backend on the same computer | leave it out (defaults to `http://10.0.2.2:8080/`, the emulator's alias for your computer) |
| Phone on the same Wi-Fi as the computer running the backend | `http://<that computer's Wi-Fi IPv4 from ipconfig>:8080/` - changes when you switch networks |
| Testing together from different places (recommended) | `http://<backend computer's Tailscale 100.x address>:8080/` - never changes |

**Tailscale setup (for testing across houses/networks):** everyone installs Tailscale and joins the
same tailnet (the backend owner invites the others from the Tailscale admin console → Users →
Invite users). The backend owner allows port 8080 from Tailscale only, once, in an
administrator terminal:

```
netsh advfirewall firewall add rule name="Rideshare backend (Tailscale)" dir=in action=allow protocol=TCP localport=8080 remoteip=100.64.0.0/10
```

Check from another laptop: `http://<100.x address>:8080/health` should show `API online`.
Debug builds allow plain HTTP to any address, so no other app changes are needed.

**After changing `BACKEND_BASE_URL`, rebuild the app** - the address is baked in at build time.

## 3. Start the backend

The backend is a separate Gradle module (`backend/`) — Kotlin + Ktor + Exposed + SQLite, chosen
specifically so it runs locally with zero external setup (see `docs/BACKEND.md` for why).

```
./gradlew :backend:run
```

- Starts on `http://localhost:8080`.
- Uses a SQLite file at `backend/rideshare-backend.db` — gitignored, created automatically on
  first run.
- On first run it seeds four demo accounts with rides, requests and messages, all signing in
  with the password `rideshare123`: `test.student@student.monash.edu`,
  `amara.singh@student.monash.edu`, `ben.nguyen@student.monash.edu` and
  `priya.kapoor@student.monash.edu`. Or create your own account from the app.
- Confirm it's actually up before moving on: `curl http://localhost:8080/health` should return
  `"API online"`.

Leave this running in its own terminal — the Android app has nothing to talk to without it.

## 4. Run the Android app

Open the project root in Android Studio, let it sync Gradle, then run the `app` configuration on
your emulator/device.

- On an emulator with the backend on the same computer, no extra config needed.
- Otherwise make sure step 2's `BACKEND_BASE_URL` is set, the backend computer's firewall allows
  port 8080, and you rebuilt after changing it.

You should land on a sign-in screen. Sign in with a demo account (see step 3) or tap "New here?
Create an account" - Monash emails only. Real Monash SSO isn't wired up yet (`docs/RTM.md` FR-01).
To test both sides of a request, sign in as a different account on a second device.

## 5. Sanity-check it actually works

A quick end-to-end smoke test once both are running:

1. Sign in on the app as `test.student@student.monash.edu` / `rideshare123`.
2. Switch the top-bar mode toggle to Driver, go to the Requests tab → My Offers, and offer a ride:
   pick a campus, search a postcode or suburb and choose one of the suggested approved points
   (5 campuses + 25 stations/shopping centres - not freeform addresses), then a time at least
   30 minutes ahead.
3. On a second device (or after signing out), sign in as `priya.kapoor@student.monash.edu` and
   find that ride under "Rides I can join" or Find a ride. Your own rides never appear as
   joinable to you.
4. Request a seat. Back on the first account in Driver mode, the request banner should appear
   within ~8 seconds; accept it (Undo is offered for a few seconds).
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
- **Everything is polling, nothing is push** — the driver request inbox refreshes every ~8
  seconds on the main tabs; messages and other lists refresh when you navigate to a screen or tap
  Refresh. This is intentional for the trial (see `docs/RTM.md`'s "Deferred" section).
- **Old requests and rides tidy themselves up** — anything whose time has passed shows as
  Expired (or Completed, if it had passengers). That's the expiry rule, not data loss.
- **"Couldn't load" / "Can't reach the server" after switching Wi-Fi** — your `BACKEND_BASE_URL`
  probably points at an old LAN IP. Use the Tailscale address instead (step 2).
- Real Monash SSO isn't wired up (email + password accounts stand in) and isn't currently a
  priority for this branch. There's no email verification or password reset yet.

## Where to go next

- `docs/RTM.md` — up-to-date requirement-by-requirement status (what's done, in progress, or
  deliberately deferred).
- `docs/BACKEND.md` — endpoint list, matching algorithm details, and known backend limitations.
- `docs/FEATURE_PROPOSALS.md` — the earlier feature ideas and what became of each (most are now
  built; messaging upgrades remain open).
- `docs/TESTING.md` / `docs/SECURITY_CHECKLIST.md` — test conventions, what's covered so far, and
  the security items still open.
