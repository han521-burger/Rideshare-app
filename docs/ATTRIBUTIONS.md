# Attributions and data provenance

This file records where third-party data and software used by the project comes from and what attribution each
asks for. It states provenance and the attribution requirements; it does not make a legal determination about how
the project may be used or distributed.

## OpenStreetMap road data (travel-time matrix)

- **What:** the backend's travel-time matrix (`backend/src/main/resources/travel-times.json`) is computed from
  OpenStreetMap road data.
- **Attribution:** Contains information from OpenStreetMap: © OpenStreetMap contributors.
  See https://www.openstreetmap.org/copyright
- **Licence of the data:** Open Data Commons Open Database License (ODbL) 1.0 -
  https://opendatacommons.org/licenses/odbl/1-0/
- **Extract used:** Geofabrik's Victoria extract (`victoria-latest.osm.pbf`, https://download.geofabrik.de/). The exact
  file, its size, SHA-256 and the OpenStreetMap data timestamp are recorded in the `provenance` block of
  `travel-times.json`.
- **Where the attribution travels:** the file carries `roadDataAttribution`, `roadDataLicence`, `roadDataLicenceUrl`
  and `roadDataCopyrightUrl` in its `provenance`, and `TravelTimeMatrixTest` fails if they are removed.
- **Open question for the project owner:** ODbL attaches conditions (attribution, and share-alike for certain
  publicly used derived databases) that depend on how the derived data is used and distributed. The matrix is
  used internally by the backend and is not shown to users; if the repository, the matrix or anything derived from it
  is published, or if matrix-derived values are displayed to users, check the ODbL conditions and add attribution
  where required. This project has not made that assessment.

## OSRM (routing engine)

- **What:** durations were computed with OSRM v26.10.0 and its unmodified `car.lua` profile.
- **Source:** https://github.com/Project-OSRM/osrm-backend
- **Licence:** the project's `LICENSE.TXT` ("Copyright (c) 2017, Project OSRM contributors", BSD 2-Clause terms).
  OSRM is a build/dev tool here: it is downloaded by `tools/osrm/generate-travel-times.ps1` into a git-ignored
  folder and is not part of the shipped backend or app.

## Geofabrik (extract provider)

- OpenStreetMap extracts are downloaded from https://download.geofabrik.de/ (Geofabrik GmbH), which redistributes
  OpenStreetMap data under the ODbL.

## Yandex MapKit (maps and route preview only)

- The Android app uses Yandex MapKit to draw maps and preview routes. MapKit results are **not** part of the
  persisted travel-time matrix and must not be stored beyond what the applicable Yandex licence allows.
  `TravelTimeMatrixExportTest` is a development-only comparison tool (see `docs/TRAVEL_TIMES.md`).
