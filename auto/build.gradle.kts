/**
 * Noctorium in the car: the menu Android Auto shows -- Home, your library, the queue, radio -- and what playing
 * something from it does, over the phone app's one player.
 *
 * An Android library rather than an app of its own. Android Auto shows what a phone app's media session offers, and
 * the session that makes the sound is Noctorium-Mobile's; a second app would be a second player. Noctorium-Mobile
 * includes this as :auto (from ../Noctorium-Android-Auto, or its auto/ submodule) and builds its session with
 * [app.noctorium.auto.CarLibraryCallback].
 */
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "app.noctorium.auto"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    api(project(":core"))
    // The phone app's Media3, so the two agree on the session's classes.
    api("androidx.media3:media3-session:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Media3's callbacks answer with Guava futures; coroutines make them.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // A real player for the session test, the one the phone app uses.
    testImplementation("androidx.media3:media3-exoplayer:1.5.1")

    // On a phone or an emulator with Noctorium installed, by hand: see RealAppTest.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
