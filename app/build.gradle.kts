import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
}

val discoveryUrl: String = (findProperty("samford.discoveryUrl") as String?)
    ?: "https://gist.githubusercontent.com/shivam098/a4237b7aa37de0c876d5c08a3c250d52/raw/samford-endpoint.json"

// Origin of an isolated Samford mock backend for BackendIntegrationTest, e.g. http://127.0.0.1:4321.
val integrationBase: String = (findProperty("samford.itBase") as String?).orEmpty()

// Release signing material lives outside the repository. Build-SignedRelease.ps1 decrypts the
// DPAPI-protected passwords into process-scoped environment variables; nothing is written to disk.
val releaseKeystorePath: String? = System.getenv("SAMFORD_PATIENT_KEYSTORE")
val hasReleaseSigning = releaseKeystorePath != null && file(releaseKeystorePath).exists()

android {
    namespace = "com.samford.patient"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.samford.patient"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DISCOVERY_URL", "\"$discoveryUrl\"")
        buildConfigField("int", "API_VERSION", "1")
    }

    androidResources {
        localeFilters += listOf("en", "hi")
        generateLocaleConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("SAMFORD_PATIENT_STORE_PASSWORD")
                keyAlias = System.getenv("SAMFORD_PATIENT_KEY_ALIAS")
                keyPassword = System.getenv("SAMFORD_PATIENT_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.maxHeapSize = "3g"
                it.jvmArgs("-Duser.timezone=UTC")
                it.systemProperty("samford.itBase", integrationBase)
                it.systemProperty("samford.e2eIn", (findProperty("samford.e2eIn") as String?).orEmpty())
                it.systemProperty("samford.e2eOut", (findProperty("samford.e2eOut") as String?).orEmpty())
                it.systemProperty("java.awt.headless", "true")
                // Runs against a live backend or handoff file must really execute, never come from cache.
                if (integrationBase.isNotEmpty() || findProperty("samford.e2eIn") != null || findProperty("samford.e2eOut") != null) {
                    it.outputs.upToDateWhen { false }
                    it.outputs.cacheIf { false }
                }
            }
        }
        animationsDisabled = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    // APKs are side-loaded for testing; keep every language in any App Bundle too, because the app
    // switches between English and Hindi at runtime.
    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
            )
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

roborazzi {
    outputDir.set(rootProject.file("docs/screenshots"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.gms.code.scanner)

    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// Prints the APK size of each assembled variant so CI logs keep an eye on download size.
tasks.register("printApkSizes") {
    doLast {
        fileTree(layout.buildDirectory.dir("outputs/apk")) { include("**/*.apk") }.forEach {
            println(String.format(Locale.ROOT, "%-70s %8.2f MB", it.name, it.length() / 1048576.0))
        }
    }
}
