fun gitVersionName(): String {
    val result = providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0")
        isIgnoreExitValue = true
    }
    return result.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { "0.0.0" }
}

fun gitVersionCode(): Int {
    val result = providers.exec {
        commandLine("git", "tag", "--list")
        isIgnoreExitValue = true
    }
    return result.standardOutput.asText.get().trim().lines().count { it.isNotBlank() }.coerceAtLeast(1)
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

    defaultConfig {
        applicationId = "com.fauxx"
        minSdk = 26
        targetSdk = 36
        versionCode = gitVersionCode()
        versionName = gitVersionName()

        testInstrumentationRunner = "com.fauxx.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
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
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        // Opt in to future Kotlin default: annotations on constructor `val` params attach to
        // both the parameter and the backing field. Silences KT-73255 warnings for Hilt
        // qualifiers like @ApplicationContext. Semantically a no-op for Dagger-consumed
        // annotations, which are read off the parameter either way.
        freeCompilerArgs.addAll("-Xannotation-default-target=param-property")
    }
}

tasks.withType<JavaCompile>().configureEach {
    javaCompiler = javaToolchains.compilerFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
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

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
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
