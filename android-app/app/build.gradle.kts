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
        versionCode = 6
        versionName = "1.5"
    }

    // Default: the shared server on AWS (docs/deploy-aws.md), so a plain build shows real data.
    // Testing against your own local backend instead (`docker compose up`), with the device's
    // localhost:8080 forwarded to the laptop by `adb reverse tcp:8080 tcp:8080`:
    //   ./gradlew :app:installDebug -PapiBaseUrl=http://localhost:8080/
    // To not retype it, put `apiBaseUrl=http://localhost:8080/` in your own
    // ~/.gradle/gradle.properties (never committed, applies to every build).
    val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { if (it.endsWith("/")) it else "$it/" }
        ?: "https://au-parking.duckdns.org/"

    // Release signing key: given only to CI (.github/workflows/android-release.yml, GitHub secrets) or to a
    // local build that sets the same variables. The key itself is kept outside the repo. Without them the
    // release build is unsigned, as before.
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") // PKCS12: one password for both
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        }
        release {
            // https, so release's no-cleartext rule (src/main/res/xml/network_security_config.xml) allows it
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
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
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

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
