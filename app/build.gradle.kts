import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val fleetProperties = Properties().apply {
    val file = rootProject.file("fleet.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val adminPasswordSalt = "odoo-companion-settings"

val adminPasswordSha256 = fleetProperties.getProperty("adminPassword")
    ?.takeIf { it.isNotEmpty() }
    ?.let { password ->
        MessageDigest.getInstance("SHA-256")
            .digest("$adminPasswordSalt:$password".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    .orEmpty()

android {
    namespace = "com.odoocompanion"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.odoocompanion"
        minSdk = 29
        targetSdk = 37
        versionCode = 7
        versionName = "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ADMIN_PASSWORD_SALT", "\"$adminPasswordSalt\"")
        buildConfigField("String", "ADMIN_PASSWORD_SHA256", "\"$adminPasswordSha256\"")
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
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
            ndk { abiFilters += "x86_64" }
        }
        release {
            signingConfig = signingConfigs.getByName("release").takeIf {
                it.storeFile != null
            }
            isMinifyEnabled = true
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
        buildConfig = true
    }

    androidResources {
        ignoreAssetsPatterns += listOf(
            "!<dir>rings",
            "!dont_wait_too_long.mkv",
            "!hello8000.wav",
            "!hello16000.wav",
            "!toy-mono.wav",
        )
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    lint {
        disable += "ChromeOsAbiSupport"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "256m"
                it.jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
            }
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
    implementation(libs.androidx.core.telecom)
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
    implementation(libs.mlkit.document.scanner)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.linphone)
    implementation(libs.unifiedpush.connector)

    testImplementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
}
