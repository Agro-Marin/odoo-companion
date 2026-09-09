buildscript {
    dependencies {
        // AGP brings its own Kotlin plugin (2.2.10 in AGP 9.4.0) and resolution
        // would keep it unless something newer is on the same classpath. The
        // serialization plugin happens to pull 2.4.20 today, which makes the
        // Kotlin version this project compiles with a side effect of a
        // dependency nobody would think to check before removing. This states
        // it instead.
        constraints {
            classpath(libs.kotlin.gradle.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
