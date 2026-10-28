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

    // Xray is the primary engine. CI builds this AAR from the pinned xray-core
    // submodule before Gradle runs. Its CoreController exposes StartLoop(config, tunFd),
    // allowing the Android VPN service to hand the TUN descriptor to Xray.
    // Do not suppress duplicate-class checks: libbox ships a second gomobile
    // runtime (go.Seq), so sing-box stays compile-only until a supported
    // shared-runtime or process-isolation strategy is implemented.
    implementation(files("libs/xray-core.aar"))
    compileOnly("com.github.singbox-android:libbox:1.14.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
