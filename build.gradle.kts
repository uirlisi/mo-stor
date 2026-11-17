plugins {
    alias(libs.plugins.android.application) apply false
    // Declaring the Kotlin plugins here also pins the Kotlin version that AGP's built-in Kotlin uses.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}
