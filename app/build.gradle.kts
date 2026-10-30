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
        // Preserve the existing multi-engine UI/registry logic in the single APK.
        buildConfigField("String", "FLAVOR", "\"multi\"")
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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // Official Psiphon Android SDK; version is pinned for reproducible builds.
    implementation("ca.psiphon:psiphontunnel:2.0.41")

    // One APK and one shared gomobile runtime for Xray, sing-box, and PG CORE.
    // Keeping them in one AAR avoids duplicate gomobile runtime classes.
    implementation(files("libs/roohvpn-core.aar"))
    // Pinned Hev JNI runtime supplies PG CORE TUN-to-SOCKS packet forwarding.
    implementation(files("libs/hev-socks5-tunnel.aar"))

    // PG CORE's Android lifecycle/API module remains fail-closed until its
    // actual packet-forwarding backend is connected and verified.
    implementation(project(":pg-core-android"))

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
