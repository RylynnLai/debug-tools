plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
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

    sourceSets {
        getByName("main") {
            java.srcDir("../debugvpn/src/main/java")
            manifest.srcFile("../debugvpn/src/main/AndroidManifest.xml")
        }
        getByName("test") {
            java.srcDir("../debugvpn/src/test/java")
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Full LeakCanary: auto-detects Activity/Fragment/ViewModel/Service leaks,
    // runs heap dump + analysis, and exposes OnHeapAnalyzedListener for enriching leak info.
    api("com.squareup.leakcanary:leakcanary-android:2.14")
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.debugtools"
            artifactId = "debugkit"
            version = "1.0.0-local"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

