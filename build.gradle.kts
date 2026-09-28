plugins {
    id("com.android.application") version "9.4.0" apply false
    // AGP compiles Kotlin itself, but the Compose compiler plugin still has to match the Kotlin version.
    // Declaring it here also puts that Kotlin Gradle plugin version on the build classpath.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
