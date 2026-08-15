# Weekly Plan Status Notes

Owner: Tony Doan. Tracks what's outstanding from earlier weeks. Update this whenever an item lands.

## Known build issues

- **AGP version**: `gradle/libs.versions.toml` on `main` still pins `agp = "9.3.1"`. Han
  (Discord, 2026-08-13 ~23:22) reported 9.3.1 is an experimental release he couldn't build
  against, and said he changed it locally to `8.9.1` plus some other Gradle file
  configuration — but that fix is not yet pushed to `main` as of the latest fetch
  (`aae9a1e`). Don't assume a clean build from `main` until this lands; flag it if CI or a
  fresh clone fails to build.

## Week 1 (closed 2026-08-09) — outstanding



## Week 2 (closed 2026-08-16) — outstanding



## Blocker

Both weeks are blocked on the same root cause: no Android or backend Gradle project has been
pushed to `main` yet. Raise this at the next Monday planning sync — Weeks 3+ compound the gap
if it isn't closed soon.

## Week 3 (2026-08-17–23) — status

