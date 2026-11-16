/**
 * Noctorium for Android Auto: the library the phone app includes, built and tested here on its own.
 *
 * The versions are the phone app's, stated here because this is the root project when it is built on its own; inside
 * Noctorium-Mobile its root states them, and `auto/` applies the plugins without a version, as `core` does.
 */
plugins {
    id("com.android.library") version "8.7.3" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("jvm") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
}
