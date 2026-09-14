import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
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
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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

    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
}
