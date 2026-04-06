plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debugtools.debugkit"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Full LeakCanary: auto-detects Activity/Fragment/ViewModel/Service leaks,
    // runs heap dump + analysis, and exposes OnHeapAnalyzedListener for enriching leak info.
    api("com.squareup.leakcanary:leakcanary-android:2.14")
}
