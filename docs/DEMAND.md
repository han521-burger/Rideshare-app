# Demand zones (prototype)

**SYNTHETIC DATA — prototype/development only.** The app has no organic ride-demand history yet
(cold start), so the "Demand zones" card in Request Ride and Offer Ride is fed by a generated,
reproducible history. It is labelled *Prototype demand · synthetic data* in the UI and must not be
described as real student usage.

## What it shows

The Route step of Request Ride and Offer Ride (`CampusRoutePicker`) has an informational
`DemandZonesCard`: a small Yandex map with one translucent circle per approved hub that has demand,
a Lower/Higher legend, the three busiest hubs, the time bucket in use, and a "Show demand zones"
switch (default on). It never selects a hub, changes the route or intercepts a tap (circles have no
listeners and sit beneath the pins). The Route step comes before the When step, so the card uses
the **current** time bucket (Melbourne time), e.g. "Weekday afternoon".

Yandex MapKit 4.42.0-full has no heatmap layer (only layers/tiles and map objects), so the layer is
the smallest supported equivalent: `CircleMapObject`s, <= 30 of them, radius 350–1,300 m and
opacity 0.14–0.42 by intensity, blue → amber → red.

## Definition

Demand = passenger intent at an approved hub (seat request, standalone request, later a search).
Driver offers are supply and are never counted. Events are `DemandEvent(hubName, timestamp, weight)`
— no people. The hub name is the identity (the approved lists have no separate IDs on this branch).

## Pipeline (all pure Kotlin, `core/demand/`)

```
DemandDataSource.events()            // synthetic today, real later
  -> keep events at approved hubs with weight > 0 (optionally newer than a cut-off)
  -> keep events in the chosen DemandPeriod (weekday/weekend x morning|midday|afternoon|evening)
  -> weighted sum per hub                        = rawDemand
  -> intensity = min(1, sqrt(rawDemand / reference))
  -> rank by rawDemand desc, then hub name
```

- `reference` is the 90th-percentile (nearest-rank) hub among hubs with any demand.
- `sqrt` compresses the heavy tail; the percentile reference stops one extreme hub flattening the rest
  (the busiest ~10 % saturate at 1.0). No demand → 0.0; empty or all-zero input is all zeros.
- Blocks: morning 05–10, midday 10–15, afternoon 15–19, evening 19–05; weekend = Sat/Sun.
- `DemandModel` caches the 8 possible results, so nothing is recomputed while the camera moves.

## The synthetic history (`SyntheticDemand`)

75 days ending 2026-09-30, fixed seed, about 2,500 events (fixed inputs → identical output).
Per hub and hour, events are Poisson draws of
`rate = scale × clusterFactor × hubWeight × hourProfile(role, weekend) × dayFactor × hubJitter`.

- **Clusters** (busiest first): Clayton, Caulfield, City, Parkville, Peninsula.
- **Roles:** campuses peak weekday mornings; stations and shopping centres peak weekday
  afternoon/evening; weekends are far quieter (shopping hubs hold up best).
- **Variation:** a shared per-day factor (day of week × a random good/bad day) and a fixed per-hub jitter.
- Every approved hub is modelled exactly once (a test checks this against the approved dataset).

`DemandSummary.of(events, hubNames)` computes the headline figures (total, top 5, lowest 3, peak
period/hour, weekday/weekend split) from the data; `SyntheticDemandTest` prints them
(`./gradlew :app:testDebugUnitTest --tests "*.SyntheticDemandTest" -i`). Nothing is typed in by hand.

## Replacing synthetic with real data

1. Add a `DemandDataSource` with `provenance = OBSERVED` that turns persisted passenger requests
   (standalone `ride_requests` rows with origin/destination coordinates, `createdAt` /
   `desiredDepartureTime`) into `DemandEvent`s: snap the passenger's hub endpoint to its approved
   hub (nearest approved point) and use the request time. Searches are not stored today; persisting
   them would add a second signal.
2. Pass it to `DemandModel` in `DemandProvider`. The aggregator, normalisation, palette and card are unchanged,
   and the disclosure disappears automatically (`disclosure` is null unless the source is synthetic).
3. Keep driver offers out of it; if supply is wanted later, aggregate rides separately.

## Forecast

The card also shows **predicted** demand from a trained model; see [DEMAND_FORECAST.md](DEMAND_FORECAST.md).

## Tests

Pure JVM (`app/src/test/.../domain/`): `DemandAggregationTest`, `SyntheticDemandTest`,
`DemandPaletteTest`. Compose (`app/src/androidTest/.../core/ui/DemandZonesCardTest`): default state,
period label, disclosure, toggle on/off, route markers unaffected, empty history, 200 % text, 48 dp target.
