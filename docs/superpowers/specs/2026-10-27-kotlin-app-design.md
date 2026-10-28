# Kotlin Android app design (v1)

Date: 2026-10-27
Status: approved in chat, pending user review of this file

## Goal

A native Android app that lets a driver see:

- which parking lots exist
- how many spots are free right now
- exactly which spots are free

The app can also open a lot's address in Google Maps. It reads from the REST API v1 (`docs/api-status.md`).

## Decisions

| Topic | Decision |
|---|---|
| Language / UI | Kotlin + Jetpack Compose, Material 3 |
| Screens | Lot list → lot detail (spot grid, option A from the mockups) |
| Architecture | One ViewModel per screen exposing `StateFlow<UiState>`, a repository, and Retrofit |
| Networking | Retrofit + kotlinx.serialization (`ignoreUnknownKeys = true`) |
| Navigation | Navigation Compose, routes `lots` and `lots/{id}` |
| Dependency wiring | Manual `AppContainer`, no Hilt/Dagger |
| Refresh | Polls every 5 s while the screen is visible (stops in background), plus pull-to-refresh |
| Auth | None (the API has none) |
| Location in repo | `android-app/` at repo root, next to `parking-app/` |
| SDK | minSdk 26, compileSdk/targetSdk 37 (installed locally) |
| Application id / package | `au.projectwork.parking` |

## API used

`GET /parking-lots` returns:

```json
[{ "id": 1,
   "address": { "street": "Finlandsgade", "houseNr": "22", "city": "Aarhus" },
   "totalSpots": 16, "freeSpots": 12, "carsInside": 4 }]
```

`GET /parking-lots/{id}` returns the same fields plus:

```json
"rows": [{ "id": 1, "freeSpots": 0, "spots": [{ "id": 1, "occupied": true }] }]
```

It returns `404` for an unknown lot.

## Structure

```
android-app/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew
└── app/
    ├── build.gradle.kts              BuildConfig API_BASE_URL per build type
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml   INTERNET permission
        │   └── java/au/projectwork/parking/
        │       ├── MainActivity.kt       sets content, NavHost
        │       ├── AppContainer.kt       builds Retrofit + ParkingRepository once
        │       ├── data/
        │       │   ├── Dtos.kt           @Serializable classes matching the JSON above
        │       │   ├── ParkingApi.kt     Retrofit interface (2 GETs)
        │       │   └── ParkingRepository.kt
        │       └── ui/
        │           ├── lots/  LotListScreen.kt, LotListViewModel.kt
        │           ├── lot/   LotDetailScreen.kt, LotDetailViewModel.kt
        │           └── Maps.kt           openInMaps(context, address)
        ├── debug/res/xml/network_security_config.xml   cleartext to 10.0.2.2 + localhost only
        └── test/                       unit tests (see Testing)
```

## Screens

### Lot list (`lots`)

- Top bar title: "Parking".
- One card per lot, showing:
  - street + house number in bold, with the city below
  - large `freeSpots`, then "of `totalSpots` spots free · `carsInside` cars inside"
  - an "Open in Google Maps" button
- Tapping the card body navigates to `lots/{id}`. Tapping the Maps button opens Maps and does not navigate.
- Pull-to-refresh is available.

### Lot detail (`lots/{id}`)

- The top bar has a back arrow and the street + house number as the title.
- The header shows a large `freeSpots`, "of `totalSpots` free", and a Maps button.
- Each row has:
  - a label "Row `id` · `freeSpots` free"
  - one line of tiles, one per spot, in API order. Each tile shows the spot id, coloured green for free or red for occupied.
  - wrapping onto more lines if the row doesn't fit the screen width
- Each tile has a content description "Spot `id`, free" / "Spot `id`, occupied".
- There is a legend: free / occupied.
- Pull-to-refresh is available.

### States (both screens)

```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Content<T>(val data: T, val offline: Boolean = false) : UiState<T>
    data class Error(val notFound: Boolean = false) : UiState<Nothing>
}
```

| Situation | UI |
|---|---|
| The first load is in progress | Centered spinner |
| The first load fails (IO / HTTP 5xx) | "Can't reach server" + Retry button |
| A refresh fails after content was shown | Keep the content and show a banner "Offline, showing last update" (`offline = true`). The banner clears on the next successful fetch. |
| Detail returns HTTP 404 | "Parking lot not found" + Back button |

## Data flow

- `ParkingRepository` has `suspend fun getLots(): List<LotSummary>` and `suspend fun getLot(id: Int): LotDetail`. These throw on failure: `IOException` for network problems, `LotNotFoundException` when the detail endpoint returns 404, and `HttpException` for other non-2xx responses.
- Each ViewModel turns a polling loop into its state:
  - The loop is `flow { while (true) { emit(fetch()); delay(5_000) } }`, with failures mapped to states as in the table above.
  - It becomes a state with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loading)`.
  - The screen collects the state with `collectAsStateWithLifecycle()`, so polling stops when the screen is not visible.
- `refresh()` triggers an immediate fetch, used by pull-to-refresh and Retry.

## Maps

`openInMaps` fires `Intent(ACTION_VIEW, "geo:0,0?q=" + Uri.encode("<street> <houseNr>, <city>"))`.

If no app can handle the intent, it falls back to `https://www.google.com/maps/search/?api=1&query=<encoded address>` in the browser.

## Configuration

- `API_BASE_URL` is a BuildConfig field.
  - Debug: `http://10.0.2.2:8080/`, which is the emulator's address for the host machine.
  - Release: the same value for now, because there is no deployed server yet.
- To use a real phone, change the debug value to the laptop's LAN IP (e.g. `http://192.168.1.20:8080/`). That IP must also be added to the debug network security config.
- Cleartext HTTP is allowed only in the debug source set, and only for `10.0.2.2` and `localhost`.
- **Android 17 local network protection** (found during the emulator run):
  - With targetSdk 37, an app needs the runtime permission `android.permission.ACCESS_LOCAL_NETWORK` to reach private-LAN addresses such as `10.0.2.2`. Without it, connections silently time out.
  - Only the debug manifest (`src/debug/AndroidManifest.xml`) declares the permission. `MainActivity` asks for it at launch in debug builds on API 37+, until it is granted.
  - A public HTTPS backend doesn't need it, so release builds don't ask for it.

## Testing

- **ViewModel unit tests** use a fake repository and `kotlinx-coroutines-test` with virtual time. They check that:
  - a successful load goes from Loading to Content
  - a failed first load goes to Error
  - after Content, a failed refresh gives Content with `offline = true`, and the next success sets `offline = false`
  - an HTTP 404 on detail gives Error with `notFound = true`
  - polling fetches again after 5 s
- **JSON test.** It decodes the exact JSON examples from the "API used" section into the DTOs, so a key renamed on either side fails the test.
- **Manual check** on the `Medium_Phone` emulator (API 37), with the backend running in Docker:
  1. The list shows 12 of 16 free.
  2. Open the detail screen.
  3. Run `curl -X POST localhost:8080/parking-spot-measurements -H 'Content-Type: application/json' -d '{"parkingSpotSensorId": 16, "occupied": true}'`.
  4. Within 5 s, tile 16 turns red and the count shows 11 free.
  5. Post `occupied: false` to restore it.
- There are no Compose UI tests in v1.

## Out of scope for v1 (backlog for v2)

**App features**

- A map view with lot pins. This needs lot coordinates in the DB and a Maps SDK API key.
- Sorting or filtering lots by distance using the phone's location.
- Favourite lots.
- Notifications, e.g. "a spot is free at your favourite lot".
- Reserving a spot. This needs user accounts, login, and backend reservation logic.
- Offline cache of the last data across app restarts (Room or DataStore).
- Dark-mode and visual polish beyond Material 3 defaults, plus an app icon and branding.
- Localisation (Danish/Portuguese strings).
- Compose UI tests and screenshot tests.
- A release build:
  - signing
  - a real HTTPS backend URL, with no cleartext
  - R8 shrinking
  - Play Store listing

**Backend items that block a real deployment** (from `docs/api-status.md`)

- Authentication for the sensor POST endpoints.
- Hosting the backend on a server with HTTPS.
- Push updates (WebSocket/SSE) instead of 5 s polling, if battery use or scale matters.
- Aggregate queries instead of one query per row, needed only for many lots.
- Test gaps:
  - no test asserts the spot-history row
  - state restoration in tests is not failure-safe
