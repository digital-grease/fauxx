import java.util.Properties

// Version is single-sourced from app/version.properties. F-Droid's checkupdates bot
// reads the same file via UpdateCheckData; bump both keys together when releasing.
// Scheme: versionCode = major*10000 + minor*100 + patch (≥100 for any release ≥0.1.0,
// keeping Play's strict-monotonic rule satisfied above the v0.2.2 baseline of 15).
val versionProps = Properties().apply {
    file("version.properties").reader().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.fauxx"
    compileSdk = 36

    sourceSets {
        // Shared test support (FakeClock, MainDispatcherRule, seeded Random) lives in
        // src/sharedTest so unit and instrumented tests use one copy.
        getByName("test").kotlin.srcDir("src/sharedTest/java")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
        // Room exports schema JSONs to app/schemas (see the `ksp { arg("room.schemaLocation") }`
        // block below). Surfacing them as androidTest assets lets MigrationTestHelper read the
        // historical schemas on-device when validating migrations.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    defaultConfig {
        applicationId = "com.fauxx"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")

        testInstrumentationRunner = "com.fauxx.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Locales whose UI strings, query banks, harmful_queries blocklists, persona
        // templates, and crawl URL sets have shipped to production. The Settings language
        // picker enables only entries in this list; selecting a non-shipped locale is
        // blocked. Bump only after that locale's `harmful_queries/<tag>.json` has signed-off
        // native-speaker review — see .devloop/spikes/multilingual-support.md and
        // user-memory `project_multilingual_safety_gate.md`.
        buildConfigField(
            "String[]",
            "SHIPPED_LOCALES",
            "new String[]{\"en\", \"es\", \"fr\", \"ru\"}"
        )
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("full") {
            dimension = "distribution"
            applicationIdSuffix = ".full"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            // Allow developers to exercise the Settings language picker for ES/FR in
            // debug builds before native-speaker review of `harmful_queries/<locale>.json`
            // has signed off the locale for production. Release builds inherit the
            // defaultConfig allowlist (`["en"]`) so the safety gate remains intact.
            buildConfigField(
                "String[]",
                "SHIPPED_LOCALES",
                "new String[]{\"en\", \"es\", \"fr\", \"ru\"}"
            )
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
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // Baseline freezes the set of pre-existing lint issues so the build only fails
        // on newly-introduced ones. Primary use case: community-contributed locales
        // (e.g. ru, PR #30) that land structurally complete but lack full UI-string
        // coverage. Without a baseline, the ~130 missing-translation errors would
        // block the build; with one, we accept the gap as known and keep the check
        // armed for any *new* MissingTranslation regressions (e.g. an EN-only string
        // added without a corresponding ES/FR translation).
        baseline = file("lint-baseline.xml")
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric loads the Android framework JAR plus a shadow universe
                // per test class — across the ~30 Robolectric-tagged tests in this
                // module, the per-fork heap blows past the JVM default. Bump to 6g
                // so MarkovQuerySanityTest + BootReceiverTest + persona/template tests
                // can coexist in one fork without OutOfMemoryError.
                test.maxHeapSize = "6g"
            }
        }
    }
}

// JDK 21 LTS. This matches F-Droid buildserver's `default-jdk-headless` on Debian
// Trixie. Do not bump above 21 without first verifying F-Droid buildserver support
// — a newer JDK here will fail reproducible-build verification during fdroiddata MR
// review and block F-Droid inclusion.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // Opt in to future Kotlin default: annotations on constructor `val` params attach to
        // both the parameter and the backing field. Silences KT-73255 warnings for Hilt
        // qualifiers like @ApplicationContext. Semantically a no-op for Dagger-consumed
        // annotations, which are read off the parameter either way.
        freeCompilerArgs.addAll("-Xannotation-default-target=param-property")
    }
}

tasks.withType<JavaCompile>().configureEach {
    javaCompiler = javaToolchains.compilerFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

ksp {
    // Export Room schemas to app/schemas/<database>/<version>.json so migrations can be
    // validated against the entity history (MigrationTestHelper). Each version is committed;
    // any future schema change must commit its new JSON or the Room schema-export check fails.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)

    // SQLCipher
    implementation(libs.sqlcipher)
    implementation(libs.sqlite.ktx)

    // Security
    implementation(libs.security.crypto)
    implementation(libs.datastore.preferences)
    implementation(libs.tink.android)

    // Gson
    implementation(libs.gson)

    // Logging
    implementation(libs.timber)

    // AppCompat — needed only for AppCompatDelegate.setApplicationLocales(), which
    // backports the per-app language API (Android 13+) down to minSdk 26. The app is
    // pure Compose; no AppCompat themes or AppCompatActivity are used.
    implementation(libs.appcompat)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.work.testing)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.mockk.android)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
