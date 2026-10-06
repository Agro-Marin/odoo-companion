import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The signing material for a fleet build, read from a file that is never
// committed. Absent -- on any checkout that has not been given the keystore --
// the release build stays unsigned and behaves exactly as it did
// before this existed, which is what keeps the gate honest: a missing keystore
// must not turn into a green build that quietly ships an unsigned APK under a
// name that suggests otherwise.
val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.odoocompanion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.odoocompanion"
        minSdk = 29
        // compileSdk is 37 because androidx.core 1.19.0 refuses to link below it.
        // targetSdk stays at 36 because Robolectric 4.16.1 refuses to emulate
        // above it -- "Package targetSdkVersion=37 > maxSdkVersion=36" fails
        // every test class before it runs -- and targetSdk is the SDK the whole
        // suite runs at. Raising this to 37 means the tests stop running, not
        // that they run against 37.
        targetSdk = 36
        // An MDM cannot push an APK over one carrying the same versionCode, so
        // this has to move with every build a fleet is meant to receive.
        versionCode = 6
        versionName = "0.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The SIP stack is native code per ABI, tens of megabytes each, and
        // every handset downloads the APK over MDM: company handsets are arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            // storeFile decides whether this config is used at all. A file
            // that names a store but omits a password therefore fails in the
            // signing task, loudly, rather than falling back to an unsigned
            // build that looks like a successful one.
            signingProperties.getProperty("storeFile")?.let { path ->
                storeFile = rootProject.file(path)
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // the emulator a debug build is tried on
            ndk { abiFilters += "x86_64" }
        }
        release {
            // Null when no keystore was supplied: an unsigned release, same as
            // before. AGP names the output app-release-unsigned.apk in that
            // case, so which one a build produced is visible from the filename.
            signingConfig = signingConfigs.getByName("release").takeIf {
                it.storeFile != null
            }
            isMinifyEnabled = true
            // R8 was shrinking code while every resource shipped regardless. The
            // APK reaches handsets over MDM, one full download each time, so the
            // unused half of a resource table is bandwidth a fleet pays for.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // Linphone's own ringtones, hold music and test-call clips: the softphone
        // rings with the handset's ringtone through its own notification
        // channel (SoftphoneRinging), not through the SIP stack, and every
        // megabyte here is one each handset downloads over MDM. The ringback
        // tone and the TLS root certificates it does use stay.
        ignoreAssetsPatterns += listOf(
            "!<dir>rings",
            "!dont_wait_too_long.mkv",
            "!hello8000.wav",
            "!hello16000.wav",
            "!toy-mono.wav",
        )
    }

    packaging {
        // compressed in the APK, extracted once at install: a handset downloads
        // the native SIP stack at about half its size
        jniLibs.useLegacyPackaging = true
    }

    lint {
        // the APK goes to company Android handsets by MDM, never to a
        // Chromebook: the release carries arm64 alone on purpose (ndk above)
        disable += "ChromeOsAbiSupport"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // An app gets dalvik.vm.heapgrowthlimit unless it asks for
            // largeHeap, which this one does not — 192 MB on a stock image.
            // Capping the test JVM in the same range is what makes the
            // max-size recording upload a real test: assembled in memory
            // rather than streamed, it raised OutOfMemoryError at both 192 MB
            // and 256 MB, below the app's own 36 MB cap. Kept at 256 MB to
            // leave Robolectric its own headroom.
            all { it.maxHeapSize = "256m" }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.linphone)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
}
