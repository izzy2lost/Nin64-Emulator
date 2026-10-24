plugins {
    // AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is no longer
    // declared here - applying it now fails outright.
    id("com.android.application") version "9.2.1" apply false
}
