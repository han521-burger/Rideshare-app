# Travel-time matrix

The backend plans trips with a precomputed, directional table of driving times between the 30 approved
pickup/drop-off points (25 hubs + 5 campuses, `backend/.../ApprovedPoints.kt`). It lives in
`backend/src/main/resources/travel-times.json` and is loaded at startup (`TravelTime.kt`).

**This matrix is the backend's scheduling authority.** It is generated from **OpenStreetMap** road data with
**OSRM**, both of which may be stored and reused. It is *not* derived from Yandex: the terms for MapKit routing
results limit how long they may be stored, so MapKit is used for the map and the route preview only (see
"Planned duration vs displayed ETA" below).

## What is in it

- **870 entries** = 30 points x 29 other points: every ordered pair of different points. Every pair is needed,
  not only campus trips, because a passenger's next pickup can be any approved point after any drop-off.
- **Directional.** `A -> B` and `B -> A` are separate values (one-way streets and turn restrictions make them
  differ).
- **Same point = 0**, implicitly. It is never stored.
- **A missing pair is unknown.** The provider returns `null`; the backend refuses the request with
  `TRAVEL_TIME_UNAVAILABLE`. Nothing is ever inferred, mirrored, estimated from distance or given a default speed.
- `"status": "POPULATED"` files must be complete, state their `source`, and use only known point ids and positive
  durations, or the server refuses to start. `"UNPOPULATED"` (no durations at all) is also valid: the server runs,
  logs a warning, and refuses every open request until the matrix is generated.
- Values are **free-flow** road durations (OSRM's car profile). There is no live or time-of-day traffic.
- The file's `provenance` block records exactly what produced it (OSRM version, profile hash, OpenStreetMap
  extract file, size, SHA-256 and data timestamp, snapping distances) together with the attribution and licence
  names below. Regenerating overwrites it.

## Provenance and attribution

| | |
|---|---|
| Routing engine | OSRM (Open Source Routing Machine), https://github.com/Project-OSRM/osrm-backend |
| OSRM version | v26.10.0 (prebuilt Windows tools from that release) |
| OSRM licence | BSD 2-Clause (`LICENSE.TXT` in the osrm-backend release) |
| Routing algorithm | MLD |
| Profile | `car.lua` from osrm-backend v26.10.0, **unmodified** (SHA-256 recorded in the file) |
| Service used | `GET /table/v1/driving/<30 coordinates>?annotations=duration,distance` - one request for the whole matrix |
| Road data | OpenStreetMap |
| Data attribution | Contains information from OpenStreetMap: (c) OpenStreetMap contributors |
| Data licence | Open Data Commons Open Database License (ODbL) 1.0 |
| Extract | Geofabrik Victoria, `victoria-latest.osm.pbf` (file name, size, SHA-256 and OpenStreetMap data timestamp are in the file's `provenance`) |
| Rounding | OSRM's decimal seconds are rounded to the nearest whole second; nothing else is changed |

Nothing was tuned, adjusted or filled in to resemble any other routing source. Attribution requirements and what
this project does and does not claim are in `docs/ATTRIBUTIONS.md`.

## How to regenerate it (Windows, no Docker)

You need Windows PowerShell, about 1 GB of free disk and an internet connection for the downloads (the OSRM
release, its unmodified `car.lua` profile and the ~230 MB OpenStreetMap extract). OSRM then runs **locally** on
127.0.0.1; nothing is sent to a public routing server.

    powershell -ExecutionPolicy Bypass -File tools/osrm/generate-travel-times.ps1

It takes a few minutes, mostly preparing the road graph. The script:

1. downloads the pinned OSRM release and extracts its `car.lua` profile (into `tools/osrm/work`, git-ignored);
2. downloads the Victoria extract from Geofabrik (or use `-OsmFile path/to/other.osm.pbf`), and hashes it;
3. prepares the graph: `osrm-extract -p car.lua`, `osrm-partition`, `osrm-customize` (MLD);
4. starts `osrm-routed` on 127.0.0.1:5000;
5. runs `./gradlew :backend:generateTravelTimes`, which makes the single Table request, checks the response
   (all 30x30 present, no nulls, diagonal 0, every point within 250 m of a drivable road) and passes it through the
   strict importer, which refuses anything incomplete and writes `travel-times.json`;
6. stops `osrm-routed`.

Then run `./gradlew :backend:test` (`TravelTimeMatrixTest` checks all 870 pairs, direction, positivity and
provenance), review the `travel-times.json` diff and commit it. Options: `-OsrmVersion`, `-OsmUrl`, `-OsmFile`,
`-Port`, `-Force` (rebuild the graph). A newer OpenStreetMap extract gives somewhat different numbers; that is
expected.

OSRM's command line cannot read paths containing a space, so the script runs the OSRM tools from inside
`tools/osrm/work` with relative paths; that works wherever the repository is checked out.

### Equivalent manual commands (Linux/macOS or Docker)

Not exercised in this repository - the script above is the tested path. The same steps with the official Docker image:

    docker run -t -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend:v26.10.0 osrm-extract   -p /opt/car.lua /data/victoria-latest.osm.pbf
    docker run -t -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend:v26.10.0 osrm-partition /data/victoria-latest.osrm
    docker run -t -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend:v26.10.0 osrm-customize /data/victoria-latest.osrm
    docker run -t -p 5000:5000 -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend:v26.10.0 osrm-routed --algorithm mld /data/victoria-latest.osrm

then write a provenance file describing what you used (the keys the script writes to
`tools/osrm/work/provenance.json`) and run `./gradlew :backend:generateTravelTimes --args="--osrm http://localhost:5000"`.

## When to regenerate

- A point is added, removed or moved (edit `ApprovedPoints.kt` **and** the app's `NamedLocations.kt`; the matrix
  completeness test fails until the matrix covers the new set).
- You want newer road data (new roads, changed one-ways). There is no automatic refresh.

## Planned duration vs displayed ETA

Two durations exist on purpose, and they will not always agree:

| | Planned duration | Displayed ETA |
|---|---|---|
| Where | backend, `travel-times.json` -> `planned_duration_seconds` | Android, Yandex MapKit route preview |
| Source | OSRM over OpenStreetMap | MapKit |
| Used for | scheduling and feasibility | showing the driver/passenger a route |

The displayed ETA never overrides the planned duration.

## Yandex MapKit comparison tool (development only)

`app/src/androidTest/.../tools/TravelTimeMatrixExportTest.kt` asks MapKit for the same 870 durations so they can
be compared with the OSRM matrix during development. **Its output is not the production matrix**, must not be
committed, and must not be kept beyond what the applicable Yandex licence permits - delete it after use. It does
not run unless invoked with `-e exportTravelMatrix true`. The backend's `importTravelTimes` task can still convert
any raw export of that shape (the OSRM generator uses the same importer), but the persisted production matrix comes
only from the OSRM/OpenStreetMap procedure above.
