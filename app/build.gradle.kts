plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aminopy.roohvpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aminopy.roohvpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "engine"
    productFlavors {
        create("xray") {
            dimension = "engine"
        }
        create("singbox") {
            dimension = "engine"
        }
        // Experimental combined package: CI must prove both native runtimes coexist.
        create("multi") {
            dimension = "engine"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20260102")

    // PG CORE's Android lifecycle/API module is included in the app build.
    // Its default backend intentionally fails closed until a real packet
    // forwarding backend is supplied and verified; this dependency alone
    // must not be treated as a working VPN data plane.
    implementation(project(":pg-core-android"))

    // Xray and sing-box each ship a gomobile runtime with overlapping classes.
    // Keep runtime selection explicit: the Xray flavor packages Xray, while the
    // singbox flavor packages libbox. Both APIs remain compile-visible to the
    // shared source set, but only one Go mobile runtime is shipped per APK.
    add("xrayImplementation", files("libs/xray-core.aar"))
    add("xrayCompileOnly", "com.github.singbox-android:libbox:1.14.0")
    add("singboxImplementation", "com.github.singbox-android:libbox:1.14.0")
    add("singboxCompileOnly", files("libs/xray-core.aar"))
    add("multiImplementation", files("libs/xray-core.aar"))
    add("multiImplementation", "com.github.singbox-android:libbox:1.14.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
