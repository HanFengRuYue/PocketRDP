plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hanfengruyue.pocketrdp.core.rdp"
    compileSdk = 37
    // NDK 29 is what FreeRDP master targets; NDK 27.1 (fully installed locally) is API-compatible
    // for our purposes. Bump to 29 once it's fully downloaded.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 31
        // Only ship arm64-v8a — that's the only ABI we have prebuilt .so for in
        // src/main/jniLibs/arm64-v8a/ (libfreerdp-android + libfreerdp3 +
        // libfreerdp-client3 + libwinpr3 + libc++_shared). To rebuild the .so,
        // run scripts/build-native-in-wsl.sh in WSL2 Ubuntu and re-uncomment
        // the externalNativeBuild blocks below — see NATIVE_BUILD_NOTES.md.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    /*
    // Native build (disabled — using prebuilt .so from jniLibs/). To re-enable
    // for a fresh source build, uncomment this whole region AND remove the
    // .so files from src/main/jniLibs/arm64-v8a/. Build via WSL2 only (Windows
    // host has too many Unix/Win32 perl + path mismatches in the OpenSSL
    // Configure chain — see NATIVE_BUILD_NOTES.md "What blocks ... on Windows").
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DWITH_OPENSSL=ON",
                    "-DWITH_OPENH264=OFF",
                    "-DWITH_CJSON=ON",
                    "-DWITH_FFMPEG=OFF",
                    "-DWITH_OPUS=OFF",
                    "-DWITH_WEBP=OFF",
                    "-DWITH_JPEG=OFF",
                    "-DWITH_PNG=OFF",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../third_party/FreeRDP/client/Android/Studio/freeRDPCore/src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }
    */
    packaging {
        jniLibs {
            pickFirsts += listOf(
                "lib/arm64-v8a/libfreerdp3.so",
                "lib/arm64-v8a/libfreerdp-client3.so",
                "lib/arm64-v8a/libwinpr3.so",
                "lib/arm64-v8a/libc++_shared.so",
                "lib/arm64-v8a/libssl.so",
                "lib/arm64-v8a/libcrypto.so",
            )
        }
    }

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
    api(libs.androidx.core.ktx)
    api(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.kotlinx.coroutines.android)
}
