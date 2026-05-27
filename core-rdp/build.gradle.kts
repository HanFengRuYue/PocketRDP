plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pocketrdp.core.rdp"
    compileSdkPreview = "CinnamonBun"
    // NDK 29 is what FreeRDP master targets; NDK 27.1 (fully installed locally) is API-compatible
    // for our purposes. Bump to 29 once it's fully downloaded.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 31
    }

    // -------------------------------------------------------------------------------------
    // Native build is **wired but currently disabled** because FreeRDP's CMake superbuild
    // fetches OpenSSL (~52 MB) and OpenH264 from GitHub Releases, which is unreliable from
    // mainland China without a stable proxy. Re-enable on a machine that can hit
    // dl.google.com / github.com reliably, then run `:core-rdp:assembleDebug` once.
    //
    // To enable:
    //   1. Make sure pkg-config is on PATH (already installed at
    //      %LOCALAPPDATA%\pkg-config\pkg-config-lite-0.28-1\bin).
    //   2. Uncomment the externalNativeBuild blocks below.
    //   3. First build will take 30-60 minutes and download ~150 MB.
    //   4. Once libfreerdp-android.so etc. are in app/build/intermediates/cxx, RdpClient
    //      will actually exchange RDP wire bytes; SessionScreen will show real desktops.
    // -------------------------------------------------------------------------------------
    /*
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DWITH_OPENSSL=ON",
                    "-DWITH_OPENH264=ON",
                    "-DWITH_CJSON=ON",
                    "-DWITH_FFMPEG=OFF",
                    "-DWITH_OPUS=OFF",
                    "-DWITH_WEBP=OFF",
                    "-DWITH_JPEG=OFF",
                    "-DWITH_PNG=OFF",
                )
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../third_party/FreeRDP/client/Android/Studio/freeRDPCore/src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }
    packaging {
        jniLibs {
            pickFirsts += listOf(
                "lib/arm64-v8a/libfreerdp3.so",
                "lib/arm64-v8a/libfreerdp-client3.so",
                "lib/arm64-v8a/libwinpr3.so",
            )
        }
    }
    */

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
