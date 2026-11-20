# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Drivers parking in Aarhus, in two equally important moments:

- **Planning:** before leaving (often a student or staff commuter), checking which lot has space. They return daily to the same few lots.
- **En route / arriving:** in or near the car, deciding where to go right now. Attention is short and often split; one glance must answer "where is there space?".

## Product Purpose

AU Parking shows live free-spot counts for parking lots, down to which individual spots are free, from sensors in the lots. Success means a driver picks a lot with space without circling, in as few taps as possible.

## Positioning

Per-spot sensor truth: the app knows exactly which spots are free right now (spot sensors, plus entrance/exit counters), not an estimate or a crowd report.

## Operating Context

- Data comes from a Spring Boot REST API (`parking-app/`) backed by Postgres (`db/init`, schema owned by a teammate). Live updates come over SSE (`/parking-lots/stream`) with a 60 s safety poll while the stream is connected, and a 5 s poll when the stream is down.
- Used outdoors and in cars: bright sunlight, one hand, possibly poor connectivity.
- University group project (Aarhus University); demoed on a physical Android phone and emulator.

## Capabilities and Constraints

- v1 (shipped): lot list with free/total counts, lot detail with a per-row spot grid, open address in Google Maps, pull-to-refresh, offline banner when a refresh fails.
- v2 (shipped): **favourite lots**, **offline cache across restarts**, **nearby / sort by distance** using the phone's location, plus a forecast card, a theme toggle and live updates (SSE).
- Distance uses the lot coordinates (`latitude`/`longitude` on `addresses`, in the DB and the API).
- Explicitly out of v2: map view with pins, free-spot notifications, reservations, accounts/auth.
- Stack: Kotlin, Jetpack Compose, Material 3, Retrofit + kotlinx.serialization, manual DI (`AppContainer`), minSdk 26, targetSdk 37.

## Brand Commitments

Name: **AU Parking**. AU mark and launcher icon exist; no further brand assets.

## Evidence on Hand

Seed data only: a few lots in Aarhus (`db/init/02_insert.sql`). No real usage data, reviews, or partner claims; don't fabricate any.

## Product Principles

1. The answer is the number: free spots must be readable at a glance, before anything else.
2. Never show stale data as fresh: say when data is old or offline, and how old it is.
3. Fewest taps from launch to a decision; favourites and the nearest lots come first.
4. Works with a bad signal: last known data is always shown, clearly marked.

## Accessibility & Inclusion

Free/occupied must never be told by colour alone (use shape, icon or text as well). Content descriptions on every spot tile. Large-text and TalkBack friendly; usable in sunlight (high contrast).
