plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// M1 stage: native build disabled. FreeRDP integration is wired up in M2.
// When enabling M2, uncomment the externalNativeBuild blocks below, add the
// `third_party/FreeRDP` submodule, and configure ABI filters via gradle.properties.

android {
    namespace = "com.pocketrdp.core.rdp"
    compileSdkPreview = "CinnamonBun"
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 31

        // M2 wire-up (commented for M1):
        // externalNativeBuild {
        //     cmake {
        //         cppFlags += "-std=c++17"
        //         arguments += listOf(
        //             "-DANDROID_STL=c++_shared",
        //             "-DWITH_CLIENT_CHANNELS=ON",
        //             "-DWITH_GFX_H264=ON",
        //             "-DWITH_OPENH264=ON",
        //         )
        //         abiFilters += listOf("arm64-v8a", "x86_64")
        //     }
        // }
    }

    // externalNativeBuild {
    //     cmake {
    //         path = file("../third_party/FreeRDP/client/Android/Studio/freeRDPCore/src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1+"
    //     }
    // }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.kotlinx.coroutines.android)
}
