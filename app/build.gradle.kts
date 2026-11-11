plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

// Room's KSP processor wants its own plugin pass through KSP (agents.md §3: Room 2.7+ KSP).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "dev.beacon"
    // Current androidx stack (Compose BOM 2026.09, lifecycle 2.11, okhttp 5.5)
    // requires compileSdk ≥37 (agent.md §3 pinned 36 — superseded, logged in docs/decisions.md).
    // Minor-versioned platforms only now: 37 + minor 0 → platforms/android-37.0.
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "dev.beacon"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/versions/**",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.maxHeapSize = "2g"
            }
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window)
    // v4 FoldAware: WindowInfoTracker / WindowLayoutInfo / FoldingFeature (beacon-foldable-design.md §1)
    implementation(libs.window)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material.icons.ext)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.biometric2)
    implementation(libs.appcompat2)
    implementation(libs.datastore)
    implementation(libs.okhttp)
    implementation(libs.socket.io.client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.work.runtime.ktx)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    // Robolectric resolves compose's ComponentActivity launch via the debug manifest
    // (robolectric/robolectric#4736): testImplementation alone does NOT merge it. (ADR-0007)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
}