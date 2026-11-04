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
        // debug default: run `adb reverse tcp:8080 tcp:8080` so the device's localhost:8080 hits the laptop (phone or emulator).
        // Backend on another machine on the same wifi? Point the app straight at it, no adb reverse needed:
        //   ./gradlew :app:installDebug -PapiBaseUrl=http://192.168.178.20:8080/
        // To not retype it, put `apiBaseUrl=http://192.168.178.20:8080/` in your own
        // ~/.gradle/gradle.properties (never committed, applies to every build).
        debug {
            val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "http://localhost:8080/"
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
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
    // retrofit only exposes okhttp at runtime; we use MediaType / ResponseBody at compile time
    implementation(libs.okhttp)
    // server-sent events: the backend tells us when to refetch, instead of us asking every few seconds
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
