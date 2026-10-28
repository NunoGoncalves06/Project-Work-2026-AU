# Kotlin Android App v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Jetpack Compose Android app in `android-app/` that lists parking lots, shows a live spot grid per lot, and opens a lot's address in Google Maps. It reads the existing REST API.

**Architecture:**
- A single `:app` module with three layers:
  - `data/`: `@Serializable` DTOs, a Retrofit interface, and a `ParkingRepository` interface implemented by `NetworkParkingRepository`.
  - `ui/`: one ViewModel per screen, plus Compose screens.
  - `AppContainer`: manual wiring of the above.
- Both ViewModels share one helper, `pollingState()`. It turns "fetch every 5 s, plus refresh on demand" into a `Flow<UiState<T>>`. That flow is exposed with `stateIn(WhileSubscribed(5_000))`, so polling stops when the screen is not visible.

**Tech Stack:**
- Kotlin 2.2.10, AGP 9.4.1 (built-in Kotlin, so no `kotlin-android` plugin), Gradle 9.6.0
- Compose BOM 2025.08.00 (Material 3)
- Navigation Compose 2.9.0, Lifecycle 2.9.2
- Retrofit 2.11.0 + converter-kotlinx-serialization, kotlinx-serialization-json 1.7.3
- JUnit 4 + kotlinx-coroutines-test 1.10.2

These versions are copied from the user's working course project `~/Desktop/AU/Mobile App Dev`, which builds with AGP 9.4.1 / Kotlin 2.2.10 / Gradle 9.6.0 / compileSdk 37.

**Spec:** `docs/superpowers/specs/2026-10-27-kotlin-app-design.md`

## Global Constraints

- **NO COMMITS, NO STAGING, NO BRANCHES.** The user commits manually. Where a task would commit, stop and report instead. Use `cp`/`mv`, never `git mv`/`git add`.
- App code lives only under `android-app/`. Do not touch `parking-app/`, `db/` or `docker-compose.yml`.
- Application id and package: `au.projectwork.parking`. minSdk 26, compileSdk/targetSdk 37.
- Use exactly the library versions in Task 1's `libs.versions.toml`. Do not add libraries that aren't in it.
- `API_BASE_URL` = `"http://10.0.2.2:8080/"` (with the trailing slash) for the debug and release build types.
- Cleartext HTTP is allowed only in the debug source set, and only for `10.0.2.2` and `localhost`.
- The JSON parser is `ParkingJson = Json { ignoreUnknownKeys = true }`, used by both the app and the tests.
- Polling period: 5 000 ms. Stop timeout: `SharingStarted.WhileSubscribed(5_000)`.
- UI copy, exactly:
  - "Parking"
  - "of N spots free · M cars inside"
  - "Open in Google Maps"
  - "Maps"
  - "of N free"
  - "Row R · F free"
  - "Can't reach server"
  - "Retry"
  - "Parking lot not found"
  - "Back"
  - "Offline, showing last update"
  - "free" / "occupied"
  - tile content description "Spot N, free" / "Spot N, occupied"
- Spot colours: free `0xFF1E7A3C`, occupied `0xFFB83228`, tile text white.
- Gradle commands run from `android-app/`. Unit tests run with `./gradlew :app:testDebugUnitTest`.

## File map

| File | Responsibility |
|---|---|
| `android-app/settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `.gitignore`, `local.properties` (ignored) | build setup |
| `app/build.gradle.kts` | module config, BuildConfig `API_BASE_URL` |
| `app/src/main/AndroidManifest.xml` | INTERNET permission, activity, network security config |
| `app/src/main/res/xml/network_security_config.xml` | release: no cleartext |
| `app/src/debug/res/xml/network_security_config.xml` | debug: cleartext to 10.0.2.2 + localhost |
| `.../parking/data/Dtos.kt` | DTOs + `ParkingJson` |
| `.../parking/data/ParkingApi.kt` | Retrofit interface |
| `.../parking/data/ParkingRepository.kt` | `ParkingRepository`, `NetworkParkingRepository`, `LotNotFoundException` |
| `.../parking/AppContainer.kt` | builds Retrofit + repository |
| `.../parking/MainActivity.kt` | entry point; NavHost (Task 3) |
| `.../parking/ui/Common.kt` | `UiState`, `pollingState`, `StateContent`, `OfflineBanner` |
| `.../parking/ui/Maps.kt` | `openInMaps` |
| `.../parking/ui/lots/LotListViewModel.kt`, `LotListScreen.kt` | lot list |
| `.../parking/ui/lot/LotDetailViewModel.kt`, `LotDetailScreen.kt` | lot detail |
| `app/src/test/java/au/projectwork/parking/...` | unit tests + `FakeParkingRepository` |
| `README.md` (repo root) | "Android app" section (Task 4) |

`.../parking/` = `android-app/app/src/main/java/au/projectwork/parking/`

---

### Task 1: Project scaffold and data layer

**Files:**
- Create: everything under `android-app/` listed in the file map's build setup rows, plus `app/build.gradle.kts`, the manifest, both network security configs, `data/Dtos.kt`, `data/ParkingApi.kt`, `data/ParkingRepository.kt`, `AppContainer.kt` and a placeholder `MainActivity.kt`
- Test: `app/src/test/java/au/projectwork/parking/data/DtosTest.kt` and `app/src/test/java/au/projectwork/parking/data/NetworkParkingRepositoryTest.kt`

**Interfaces:**
- Produces:
  - DTOs `Address(street: String, houseNr: String, city: String)`, `LotSummary(id: Int, address: Address, totalSpots: Int, freeSpots: Int, carsInside: Int)`, `Spot(id: Int, occupied: Boolean)`, `Row(id: Int, freeSpots: Int, spots: List<Spot>)`, `LotDetail(id: Int, address: Address, totalSpots: Int, freeSpots: Int, carsInside: Int, rows: List<Row>)`, plus `val ParkingJson: Json`
  - `interface ParkingRepository { suspend fun getLots(): List<LotSummary>; suspend fun getLot(id: Int): LotDetail }`
  - `class LotNotFoundException(id: Int) : Exception`
  - `class AppContainer { val repository: ParkingRepository }`

- [ ] **Step 1: Copy the Gradle wrapper from the backend project** (same wrapper jar), then pin the distribution:

```bash
mkdir -p android-app/gradle/wrapper
cp parking-app/gradlew parking-app/gradlew.bat android-app/
cp parking-app/gradle/wrapper/gradle-wrapper.jar android-app/gradle/wrapper/
chmod +x android-app/gradlew
```

Create `android-app/gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 2: Create `android-app/gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.4.1"
kotlin = "2.2.10"
coreKtx = "1.16.0"
activityCompose = "1.10.1"
lifecycle = "2.9.2"
composeBom = "2025.08.00"
navigation = "2.9.0"
retrofit = "2.11.0"
serializationJson = "1.7.3"
coroutines = "1.10.2"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons-core = { group = "androidx.compose.material", name = "material-icons-core" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", version.ref = "retrofit" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serializationJson" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 3: Create the root build files**

`android-app/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ParkingApp"
include(":app")
```

`android-app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

`android-app/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.configuration-cache=true
kotlin.code.style=official
```

`android-app/.gitignore`:

```
.gradle/
.kotlin/
build/
local.properties
.idea/
*.iml
```

`android-app/local.properties` (git-ignored, machine-specific):

```properties
sdk.dir=/home/dremel/Android/Sdk
```

- [ ] **Step 4: Create `android-app/app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "au.projectwork.parking"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "au.projectwork.parking"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // 10.0.2.2 = the host machine as seen from the Android emulator; use your laptop's LAN IP for a real phone
        debug {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 5: Create the manifest and network security configs**

`android-app/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:label="Parking"
        android:networkSecurityConfig="@xml/network_security_config"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android-app/app/src/main/res/xml/network_security_config.xml` (release: platform default, no cleartext):

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

`android-app/app/src/debug/res/xml/network_security_config.xml` (overrides the main one in debug builds):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- debug only: the local backend runs on plain http -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 6: Write the failing tests**

`android-app/app/src/test/java/au/projectwork/parking/data/DtosTest.kt`:

```kotlin
package au.projectwork.parking.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DtosTest {

    // exact examples from docs/api-status.md / the spec, so a renamed key on either side fails here
    @Test
    fun decodesLotList() {
        val json = """
            [{ "id": 1,
               "address": { "street": "Finlandsgade", "houseNr": "22", "city": "Aarhus" },
               "totalSpots": 16, "freeSpots": 12, "carsInside": 4 }]
        """
        val lots = ParkingJson.decodeFromString<List<LotSummary>>(json)
        assertEquals(
            listOf(LotSummary(1, Address("Finlandsgade", "22", "Aarhus"), 16, 12, 4)),
            lots,
        )
    }

    @Test
    fun decodesLotDetailAndIgnoresUnknownKeys() {
        val json = """
            { "id": 1,
              "address": { "street": "Finlandsgade", "houseNr": "22", "city": "Aarhus" },
              "totalSpots": 16, "freeSpots": 12, "carsInside": 4, "addedLater": true,
              "rows": [{ "id": 1, "freeSpots": 0, "spots": [{ "id": 1, "occupied": true }] }] }
        """
        val lot = ParkingJson.decodeFromString<LotDetail>(json)
        assertEquals(listOf(Row(1, 0, listOf(Spot(1, true)))), lot.rows)
        assertEquals("Aarhus", lot.address.city)
    }
}
```

`android-app/app/src/test/java/au/projectwork/parking/data/NetworkParkingRepositoryTest.kt`:

```kotlin
package au.projectwork.parking.data

import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class NetworkParkingRepositoryTest {

    private fun apiThrowing(code: Int) = object : ParkingApi {
        override suspend fun getLots(): List<LotSummary> = throw HttpException(Response.error<Any>(code, "".toResponseBody()))
        override suspend fun getLot(id: Int): LotDetail = throw HttpException(Response.error<Any>(code, "".toResponseBody()))
    }

    @Test(expected = LotNotFoundException::class)
    fun http404OnDetailBecomesLotNotFound() = runTest {
        NetworkParkingRepository(apiThrowing(404)).getLot(99)
    }

    @Test
    fun otherHttpErrorsPassThrough() = runTest {
        val e = runCatching { NetworkParkingRepository(apiThrowing(500)).getLot(1) }.exceptionOrNull()
        assertEquals(500, (e as HttpException).code())
    }
}
```

- [ ] **Step 7: Run them to verify they fail**

Run: `cd android-app && ./gradlew :app:testDebugUnitTest`
Expected: FAIL, compilation errors "Unresolved reference: ParkingJson / LotSummary / ParkingApi / NetworkParkingRepository". The first run also downloads Gradle and the dependencies.

- [ ] **Step 8: Create the data layer**

`.../parking/data/Dtos.kt`:

```kotlin
package au.projectwork.parking.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ignoreUnknownKeys: the backend may add fields without breaking the app
val ParkingJson = Json { ignoreUnknownKeys = true }

@Serializable
data class Address(val street: String, val houseNr: String, val city: String)

@Serializable
data class LotSummary(
    val id: Int,
    val address: Address,
    val totalSpots: Int,
    val freeSpots: Int,
    val carsInside: Int,
)

@Serializable
data class Spot(val id: Int, val occupied: Boolean)

@Serializable
data class Row(val id: Int, val freeSpots: Int, val spots: List<Spot>)

@Serializable
data class LotDetail(
    val id: Int,
    val address: Address,
    val totalSpots: Int,
    val freeSpots: Int,
    val carsInside: Int,
    val rows: List<Row>,
)
```

`.../parking/data/ParkingApi.kt`:

```kotlin
package au.projectwork.parking.data

import retrofit2.http.GET
import retrofit2.http.Path

interface ParkingApi {

    @GET("parking-lots")
    suspend fun getLots(): List<LotSummary>

    @GET("parking-lots/{id}")
    suspend fun getLot(@Path("id") id: Int): LotDetail
}
```

`.../parking/data/ParkingRepository.kt`:

```kotlin
package au.projectwork.parking.data

import retrofit2.HttpException

class LotNotFoundException(id: Int) : Exception("Parking lot $id not found")

/** Throws IOException when the server is unreachable, LotNotFoundException for an unknown lot. */
interface ParkingRepository {
    suspend fun getLots(): List<LotSummary>
    suspend fun getLot(id: Int): LotDetail
}

class NetworkParkingRepository(private val api: ParkingApi) : ParkingRepository {

    override suspend fun getLots(): List<LotSummary> = api.getLots()

    override suspend fun getLot(id: Int): LotDetail =
        try {
            api.getLot(id)
        } catch (e: HttpException) {
            if (e.code() == 404) throw LotNotFoundException(id) else throw e
        }
}
```

`.../parking/AppContainer.kt`:

```kotlin
package au.projectwork.parking

import au.projectwork.parking.data.NetworkParkingRepository
import au.projectwork.parking.data.ParkingApi
import au.projectwork.parking.data.ParkingJson
import au.projectwork.parking.data.ParkingRepository
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AppContainer {

    val repository: ParkingRepository = NetworkParkingRepository(
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(ParkingJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ParkingApi::class.java)
    )
}
```

`.../parking/MainActivity.kt` (placeholder; Task 2 replaces it):

```kotlin
package au.projectwork.parking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Text("Parking") } }
    }
}
```

- [ ] **Step 9: Run the tests and the build**

Run: `cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, with 4 tests passing and `app/build/outputs/apk/debug/app-debug.apk` produced.

If the serialization plugin fails to apply with AGP 9's built-in Kotlin, report it with the exact error. Do not add the `kotlin-android` plugin: AGP 9 rejects it.

- [ ] **Step 10: Stop. Do not commit.** Report to the user.

---

### Task 2: Shared UI state, polling, and the lot list screen

**Files:**
- Create: `.../parking/ui/Common.kt`, `.../parking/ui/Maps.kt`, `.../parking/ui/lots/LotListViewModel.kt`, `.../parking/ui/lots/LotListScreen.kt`
- Modify: `.../parking/MainActivity.kt`
- Test: `app/src/test/java/au/projectwork/parking/ui/FakeParkingRepository.kt`, `app/src/test/java/au/projectwork/parking/ui/lots/LotListViewModelTest.kt`

**Interfaces:**
- Consumes: `ParkingRepository`, `LotSummary`, `Address`, `LotNotFoundException` and `AppContainer` from Task 1.
- Produces:
  - `sealed interface UiState<out T>` with the cases `Loading`, `Content(data, offline)` and `Error(notFound)`
  - `fun <T> pollingState(refreshRequests: Flow<Unit>, periodMs: Long = 5_000, fetch: suspend () -> T): Flow<UiState<T>>`
  - `@Composable fun <T> StateContent(state, onRetry, modifier, onBack, content: @Composable (T, Boolean) -> Unit)`
  - `@Composable fun OfflineBanner()`
  - `fun openInMaps(context: Context, address: Address)`
  - `class LotListViewModel(repository: ParkingRepository)` with `state: StateFlow<UiState<List<LotSummary>>>` and `fun refresh()`
  - `@Composable fun LotListScreen(viewModel: LotListViewModel, onLotClick: (Int) -> Unit)`
  - the test helper `FakeParkingRepository`

- [ ] **Step 1: Write the test helper and the failing tests**

`app/src/test/java/au/projectwork/parking/ui/FakeParkingRepository.kt`:

```kotlin
package au.projectwork.parking.ui

import au.projectwork.parking.data.LotDetail
import au.projectwork.parking.data.LotSummary
import au.projectwork.parking.data.ParkingRepository

/** Each call returns the next queued result; the last one repeats. */
class FakeParkingRepository : ParkingRepository {
    val lotsResults = ArrayDeque<Result<List<LotSummary>>>()
    val lotResults = ArrayDeque<Result<LotDetail>>()
    var lotsCalls = 0
    var lotCalls = 0

    override suspend fun getLots(): List<LotSummary> {
        lotsCalls++
        return next(lotsResults).getOrThrow()
    }

    override suspend fun getLot(id: Int): LotDetail {
        lotCalls++
        return next(lotResults).getOrThrow()
    }

    private fun <T> next(queue: ArrayDeque<T>): T = if (queue.size > 1) queue.removeFirst() else queue.first()
}
```

`app/src/test/java/au/projectwork/parking/ui/lots/LotListViewModelTest.kt`:

```kotlin
package au.projectwork.parking.ui.lots

import au.projectwork.parking.data.Address
import au.projectwork.parking.data.LotSummary
import au.projectwork.parking.ui.FakeParkingRepository
import au.projectwork.parking.ui.UiState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LotListViewModelTest {

    private val lot = LotSummary(1, Address("Finlandsgade", "22", "Aarhus"), 16, 12, 4)
    private val repo = FakeParkingRepository()

    @After
    fun tearDown() = Dispatchers.resetMain()

    // never advanceUntilIdle(): the polling loop never goes idle
    private fun TestScope.subscribedViewModel(): LotListViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = LotListViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun firstLoadShowsContent() = runTest {
        repo.lotsResults += Result.success(listOf(lot))
        val vm = subscribedViewModel()
        assertEquals(UiState.Loading, vm.state.value)
        runCurrent()
        assertEquals(UiState.Content(listOf(lot)), vm.state.value)
    }

    @Test
    fun firstLoadFailureShowsError() = runTest {
        repo.lotsResults += Result.failure(IOException("down"))
        val vm = subscribedViewModel()
        runCurrent()
        assertEquals(UiState.Error(), vm.state.value)
    }

    @Test
    fun refreshFailureKeepsLastContentMarkedOffline() = runTest {
        repo.lotsResults += Result.success(listOf(lot))
        repo.lotsResults += Result.failure(IOException("blip"))
        repo.lotsResults += Result.success(listOf(lot))
        val vm = subscribedViewModel()
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(UiState.Content(listOf(lot), offline = true), vm.state.value)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(UiState.Content(listOf(lot), offline = false), vm.state.value)
    }

    @Test
    fun pollsAgainAfterFiveSeconds() = runTest {
        repo.lotsResults += Result.success(listOf(lot))
        subscribedViewModel()
        runCurrent()
        assertEquals(1, repo.lotsCalls)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(1, repo.lotsCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, repo.lotsCalls)
    }

    @Test
    fun refreshFetchesImmediately() = runTest {
        repo.lotsResults += Result.success(listOf(lot))
        val vm = subscribedViewModel()
        runCurrent()
        vm.refresh(); runCurrent()
        assertEquals(2, repo.lotsCalls)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd android-app && ./gradlew :app:testDebugUnitTest`
Expected: FAIL, compilation errors "Unresolved reference: UiState / LotListViewModel".

- [ ] **Step 3: Create `.../parking/ui/Common.kt`**

```kotlin
package au.projectwork.parking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.projectwork.parking.data.LotNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Content<T>(val data: T, val offline: Boolean = false) : UiState<T>
    data class Error(val notFound: Boolean = false) : UiState<Nothing>
}

/** Fetches now, then every [periodMs], and again whenever [refreshRequests] emits. */
fun <T> pollingState(
    refreshRequests: Flow<Unit>,
    periodMs: Long = 5_000,
    fetch: suspend () -> T,
): Flow<UiState<T>> = channelFlow {
    var last: T? = null
    suspend fun load() {
        val next = try {
            val data = fetch()
            last = data
            UiState.Content(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LotNotFoundException) {
            UiState.Error(notFound = true)
        } catch (e: Exception) {
            // keep showing the last data if we had any; only a failed first load is an error
            last?.let { UiState.Content(it, offline = true) } ?: UiState.Error()
        }
        send(next)
    }
    launch { refreshRequests.collect { load() } }
    while (true) {
        load()
        delay(periodMs)
    }
}

@Composable
fun <T> StateContent(
    state: UiState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable (data: T, offline: Boolean) -> Unit,
) {
    when (state) {
        UiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is UiState.Error -> Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.notFound) {
                Text("Parking lot not found", style = MaterialTheme.typography.titleMedium)
                if (onBack != null) Button(onClick = onBack) { Text("Back") }
            } else {
                Text("Can't reach server", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
        is UiState.Content -> Box(modifier.fillMaxSize()) { content(state.data, state.offline) }
    }
}

@Composable
fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Offline, showing last update",
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}
```

- [ ] **Step 4: Create `.../parking/ui/lots/LotListViewModel.kt`**

```kotlin
package au.projectwork.parking.ui.lots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.projectwork.parking.data.LotSummary
import au.projectwork.parking.data.ParkingRepository
import au.projectwork.parking.ui.UiState
import au.projectwork.parking.ui.pollingState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class LotListViewModel(repository: ParkingRepository) : ViewModel() {

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // WhileSubscribed: polling stops 5 s after the screen stops collecting (app in background)
    val state: StateFlow<UiState<List<LotSummary>>> =
        pollingState(refreshRequests) { repository.getLots() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd android-app && ./gradlew :app:testDebugUnitTest`
Expected: PASS (9 tests: 4 from Task 1 + 5 new).

- [ ] **Step 6: Create `.../parking/ui/Maps.kt`**

```kotlin
package au.projectwork.parking.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import au.projectwork.parking.data.Address

fun openInMaps(context: Context, address: Address) {
    val query = Uri.encode("${address.street} ${address.houseNr}, ${address.city}")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query")))
    } catch (e: ActivityNotFoundException) {
        // no maps app installed: open Google Maps in the browser instead
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$query")))
    }
}
```

- [ ] **Step 7: Create `.../parking/ui/lots/LotListScreen.kt`**

```kotlin
package au.projectwork.parking.ui.lots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.projectwork.parking.data.LotSummary
import au.projectwork.parking.ui.OfflineBanner
import au.projectwork.parking.ui.StateContent
import au.projectwork.parking.ui.openInMaps

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotListScreen(viewModel: LotListViewModel, onLotClick: (Int) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("Parking") }) }) { padding ->
        StateContent(state, onRetry = viewModel::refresh, modifier = Modifier.padding(padding)) { lots, offline ->
            // shortcut: the indicator doesn't wait for the fetch; fine while fetches are fast, track a loading flag if they get slow
            PullToRefreshBox(isRefreshing = false, onRefresh = viewModel::refresh) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (offline) item { OfflineBanner() }
                    items(lots, key = { it.id }) { lot ->
                        LotCard(
                            lot,
                            onClick = { onLotClick(lot.id) },
                            onMaps = { openInMaps(context, lot.address) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LotCard(lot: LotSummary, onClick: () -> Unit, onMaps: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${lot.address.street} ${lot.address.houseNr}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                lot.address.city,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${lot.freeSpots}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    " of ${lot.totalSpots} spots free · ${lot.carsInside} cars inside",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            OutlinedButton(onClick = onMaps) { Text("Open in Google Maps") }
        }
    }
}
```

- [ ] **Step 8: Replace `.../parking/MainActivity.kt`** to show the list. Task 3 adds navigation.

```kotlin
package au.projectwork.parking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import au.projectwork.parking.ui.lots.LotListScreen
import au.projectwork.parking.ui.lots.LotListViewModel

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                LotListScreen(
                    viewModel(factory = viewModelFactory { initializer { LotListViewModel(container.repository) } }),
                    onLotClick = {},
                )
            }
        }
    }
}
```

- [ ] **Step 9: Run the tests and the build**

Run: `cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, with 9 tests passing.

- [ ] **Step 10: Stop. Do not commit.** Report to the user.

---

### Task 3: Lot detail screen and navigation

**Files:**
- Create: `.../parking/ui/lot/LotDetailViewModel.kt`, `.../parking/ui/lot/LotDetailScreen.kt`
- Modify: `.../parking/MainActivity.kt`
- Test: `app/src/test/java/au/projectwork/parking/ui/lot/LotDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `UiState`, `pollingState`, `StateContent`, `OfflineBanner`, `openInMaps`, `FakeParkingRepository` (Task 2), and `LotDetail`/`Row`/`Spot`/`LotNotFoundException` (Task 1).
- Produces:
  - `class LotDetailViewModel(repository: ParkingRepository, lotId: Int)` with `state: StateFlow<UiState<LotDetail>>` and `fun refresh()`
  - `@Composable fun LotDetailScreen(viewModel: LotDetailViewModel, onBack: () -> Unit)`
  - NavHost routes `lots` and `lots/{id}`

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/au/projectwork/parking/ui/lot/LotDetailViewModelTest.kt`:

```kotlin
package au.projectwork.parking.ui.lot

import au.projectwork.parking.data.Address
import au.projectwork.parking.data.LotDetail
import au.projectwork.parking.data.LotNotFoundException
import au.projectwork.parking.data.Row
import au.projectwork.parking.data.Spot
import au.projectwork.parking.ui.FakeParkingRepository
import au.projectwork.parking.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LotDetailViewModelTest {

    private val lot = LotDetail(
        1, Address("Finlandsgade", "22", "Aarhus"), 3, 1, 2,
        listOf(Row(1, 1, listOf(Spot(1, true), Spot(2, true), Spot(3, false)))),
    )
    private val repo = FakeParkingRepository()

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.subscribedViewModel(): LotDetailViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = LotDetailViewModel(repo, lotId = 1)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun loadsLot() = runTest {
        repo.lotResults += Result.success(lot)
        val vm = subscribedViewModel()
        runCurrent()
        assertEquals(UiState.Content(lot), vm.state.value)
        assertEquals(1, repo.lotCalls)
    }

    @Test
    fun unknownLotShowsNotFound() = runTest {
        repo.lotResults += Result.failure(LotNotFoundException(1))
        val vm = subscribedViewModel()
        runCurrent()
        assertEquals(UiState.Error(notFound = true), vm.state.value)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd android-app && ./gradlew :app:testDebugUnitTest`
Expected: FAIL, compilation error "Unresolved reference: LotDetailViewModel".

- [ ] **Step 3: Create `.../parking/ui/lot/LotDetailViewModel.kt`**

```kotlin
package au.projectwork.parking.ui.lot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.projectwork.parking.data.LotDetail
import au.projectwork.parking.data.ParkingRepository
import au.projectwork.parking.ui.UiState
import au.projectwork.parking.ui.pollingState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class LotDetailViewModel(repository: ParkingRepository, lotId: Int) : ViewModel() {

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val state: StateFlow<UiState<LotDetail>> =
        pollingState(refreshRequests) { repository.getLot(lotId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android-app && ./gradlew :app:testDebugUnitTest`
Expected: PASS (11 tests).

- [ ] **Step 5: Create `.../parking/ui/lot/LotDetailScreen.kt`**

```kotlin
package au.projectwork.parking.ui.lot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.projectwork.parking.data.LotDetail
import au.projectwork.parking.data.Spot
import au.projectwork.parking.ui.OfflineBanner
import au.projectwork.parking.ui.StateContent
import au.projectwork.parking.ui.UiState
import au.projectwork.parking.ui.openInMaps

private val FreeColor = Color(0xFF1E7A3C)
private val OccupiedColor = Color(0xFFB83228)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotDetailScreen(viewModel: LotDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = (state as? UiState.Content)?.data?.address?.let { "${it.street} ${it.houseNr}" } ?: ""
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        StateContent(
            state,
            onRetry = viewModel::refresh,
            modifier = Modifier.padding(padding),
            onBack = onBack,
        ) { lot, offline ->
            // shortcut: the indicator doesn't wait for the fetch; fine while fetches are fast, track a loading flag if they get slow
            PullToRefreshBox(isRefreshing = false, onRefresh = viewModel::refresh) {
                LotDetailContent(lot, offline)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LotDetailContent(lot: LotDetail, offline: Boolean) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (offline) OfflineBanner()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${lot.freeSpots}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(" of ${lot.totalSpots} free", Modifier.weight(1f))
            OutlinedButton(onClick = { openInMaps(context, lot.address) }) { Text("Maps") }
        }
        lot.rows.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Row ${row.id} · ${row.freeSpots} free", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.spots.forEach { SpotTile(it) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(FreeColor, "free")
            LegendItem(OccupiedColor, "occupied")
        }
    }
}

@Composable
private fun SpotTile(spot: Spot) {
    val label = if (spot.occupied) "occupied" else "free"
    Box(
        Modifier
            .size(width = 40.dp, height = 52.dp)
            .background(if (spot.occupied) OccupiedColor else FreeColor, RoundedCornerShape(6.dp))
            .clearAndSetSemantics { contentDescription = "Spot ${spot.id}, $label" },
        contentAlignment = Alignment.Center,
    ) {
        Text("${spot.id}", color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(14.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
```

- [ ] **Step 6: Replace `.../parking/MainActivity.kt`** with the NavHost:

```kotlin
package au.projectwork.parking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import au.projectwork.parking.ui.lot.LotDetailScreen
import au.projectwork.parking.ui.lot.LotDetailViewModel
import au.projectwork.parking.ui.lots.LotListScreen
import au.projectwork.parking.ui.lots.LotListViewModel

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "lots") {
                    composable("lots") {
                        LotListScreen(
                            viewModel(factory = viewModelFactory { initializer { LotListViewModel(container.repository) } }),
                            onLotClick = { id -> nav.navigate("lots/$id") },
                        )
                    }
                    composable("lots/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
                        val id = entry.arguments!!.getInt("id")
                        LotDetailScreen(
                            viewModel(factory = viewModelFactory { initializer { LotDetailViewModel(container.repository, id) } }),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 7: Run the tests and the build**

Run: `cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, with 11 tests passing.

- [ ] **Step 8: Stop. Do not commit.** Report to the user.

---

### Task 4: Run on the emulator against the real backend, and document it

**Files:**
- Modify: `README.md` (repo root). Add an "Android app" section after the REST API section.

**Interfaces:**
- Consumes: the debug APK from Task 3 and the backend from `docker compose up`.

- [ ] **Step 1: Append to `README.md`**

````markdown

Android app (`android-app/`):

- Requirements: Android Studio (or the Android SDK at `~/Android/Sdk`) and the backend running via `docker compose up`
- Open the `android-app/` folder in Android Studio and run the `app` configuration on an emulator
- Or from the command line: `cd android-app && ./gradlew :app:installDebug` with an emulator running
- The app calls `http://10.0.2.2:8080/`, which is your laptop as seen from the emulator. For a real phone on the same Wi-Fi, change `API_BASE_URL` in `android-app/app/build.gradle.kts` to your laptop's IP (e.g. `http://192.168.1.20:8080/`) and add that IP to `android-app/app/src/debug/res/xml/network_security_config.xml`
- Unit tests: `cd android-app && ./gradlew :app:testDebugUnitTest`
````

- [ ] **Step 2: Make sure the backend is up**

Run (from the repo root):

```bash
docker compose up -d && sleep 5 && curl -s localhost:8080/parking-lots
```

Expected: the JSON contains `"freeSpots":12`. If it shows 11, a previous test left spot 16 occupied. Post `{"parkingSpotSensorId": 16, "occupied": false}` to restore it.

- [ ] **Step 3: Start the emulator, install the app, and launch it**

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$HOME/Android/Sdk/emulator:$PATH"
adb devices | grep -q emulator || (emulator -avd Medium_Phone -no-window -no-audio -no-snapshot-save > /tmp/claude-1000/-home-dremel-Desktop-AU-Project-Work-Parking/8c1195a1-670d-420d-9958-ea8b9ad116aa/scratchpad/emulator.log 2>&1 &)
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
cd android-app && ./gradlew :app:installDebug && cd ..
adb shell am start -n au.projectwork.parking/.MainActivity
sleep 4
adb exec-out screencap -p > /tmp/claude-1000/-home-dremel-Desktop-AU-Project-Work-Parking/8c1195a1-670d-420d-9958-ea8b9ad116aa/scratchpad/app-list.png
```

Expected: the screenshot shows the "Parking" title and a card for Finlandsgade 22 with **12** "of 16 spots free · 4 cars inside". Look at the PNG with your image-reading tool.

If the emulator can't start (no `/dev/kvm`, for example), report BLOCKED with the log. Do not install system packages.

- [ ] **Step 4: Open the detail screen and check that it updates live**

```bash
adb shell input tap 540 600   # tap the lot card; adjust coordinates from the screenshot if needed
sleep 3
adb exec-out screencap -p > /tmp/claude-1000/-home-dremel-Desktop-AU-Project-Work-Parking/8c1195a1-670d-420d-9958-ea8b9ad116aa/scratchpad/app-detail.png
curl -s -X POST localhost:8080/parking-spot-measurements -H 'Content-Type: application/json' -d '{"parkingSpotSensorId": 16, "occupied": true}'
sleep 7
adb exec-out screencap -p > /tmp/claude-1000/-home-dremel-Desktop-AU-Project-Work-Parking/8c1195a1-670d-420d-9958-ea8b9ad116aa/scratchpad/app-detail-live.png
curl -s -X POST localhost:8080/parking-spot-measurements -H 'Content-Type: application/json' -d '{"parkingSpotSensorId": 16, "occupied": false}'
```

Expected:
- `app-detail.png` shows rows 1-4 with tiles 1-4 red and 5-16 green, and the header shows 12.
- `app-detail-live.png` shows tile 16 red and the header shows 11. This proves polling works.
- The final POST restores the seed state.

- [ ] **Step 5: Check the error state**

```bash
docker compose stop parking-app
sleep 8
adb exec-out screencap -p > /tmp/claude-1000/-home-dremel-Desktop-AU-Project-Work-Parking/8c1195a1-670d-420d-9958-ea8b9ad116aa/scratchpad/app-offline.png
docker compose start parking-app
```

Expected: `app-offline.png` still shows the grid, with the "Offline, showing last update" banner on top.

- [ ] **Step 6: Leave the emulator running** for the user to try the app, and leave the backend up.

- [ ] **Step 7: Stop. Do not commit.** Report to the user with the screenshot paths.
