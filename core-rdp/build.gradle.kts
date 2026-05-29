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
    // Native build via CMake superbuild — disabled, using prebuilt .so in jniLibs/.
    // To rebuild (e.g. after FreeRDP submodule bump), uncomment this whole region
    // AND delete the .so files from src/main/jniLibs/arm64-v8a/. Build via WSL2
    // only (OpenSSL Configure has perl/path bugs on Windows — see NATIVE_BUILD_NOTES.md).
    //
    // Hard-won flags below: ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES + max-page-size=16384
    // are mandatory for Android 15+ (SDK 35+) 16 KB-page devices. Without them,
    // NDK 27.1 leaves LOAD segments at 4 KB alignment and dlopen rejects them.
    // The submodule's freeRDPCore/cpp/CMakeLists.txt and ExternalFreeRDP.cmake were
    // also patched to forward these flags into the ExternalProject children — see
    // NATIVE_BUILD_NOTES.md "16 KB page-size" section.
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    "-DCMAKE_MODULE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
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
                "lib/arm64-v8a/libcjson.so",
                "lib/arm64-v8a/liburiparser.so",
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
    api(project(":core-logging"))

    api(libs.androidx.core.ktx)
    api(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.kotlinx.coroutines.android)
}
