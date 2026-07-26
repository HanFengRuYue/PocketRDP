plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hanfengruyue.pocketrdp.core.rdp"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    // Four ABIs shipped in the APK. In a normal (Windows) build all four are packaged
    // straight from the prebuilt jniLibs/<abi>/ — no native toolchain needed.
    val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    // -PnativeAbi=<abi> turns ON the FreeRDP CMake superbuild for a SINGLE ABI (WSL2 only).
    // Omit it for pure prebuilt-packaging (the default, what Windows gradle does). This
    // property-gate replaces the old comment-region toggle: no more editing /* ===== */ markers.
    // ONE ABI at a time is mandatory — the OpenSSL/FFmpeg ExternalProjects build from a shared
    // source tree, so a concurrent multi-ABI build would race on it. The driver script loops
    // the four ABIs, invoking gradle once per ABI. A Windows native build fails at OpenSSL
    // Configure (perl/path bugs — see NATIVE_BUILD_NOTES.md), so this only runs under WSL2.
    val nativeAbi = (project.findProperty("nativeAbi") as String?)?.takeIf { it.isNotBlank() }

    defaultConfig {
        minSdk = 31
        ndk { abiFilters += nativeAbi?.let { listOf(it) } ?: supportedAbis }

        if (nativeAbi != null) {
            // Hard-won flags: ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES + max-page-size=16384 are
            // mandatory for Android 15+ (SDK 35+) 16 KB-page devices (64-bit ABIs); without them
            // A 4 KB-aligned 64-bit library is rejected by dlopen on such devices. The submodule's
            // freeRDPCore/cpp/CMakeLists.txt and ExternalFreeRDP.cmake were also patched to forward
            // these flags into the ExternalProject children — see NATIVE_BUILD_NOTES.md.
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
                        // H.264 uses Android MediaCodec first and a minimal FFmpeg software decoder
                        // (+ swscale) as fallback; OpenH264 remains OFF. FFmpeg is built STATIC
                        // (ExternalFFmpeg.cmake) and linked into libfreerdp3, so there is no extra
                        // versioned FFmpeg .so/SONAME for Android to package.
                        "-DWITH_OPENH264=OFF",
                        "-DWITH_CJSON=ON",
                        "-DWITH_FFMPEG=ON",
                        // NOTE: WITH_MEDIACODEC is deliberately NOT passed here. Adding a -D to this gradle
                        // arguments list changes AGP's CMake config hash, which makes AGP create a fresh
                        // .cxx/<newhash>/ dir and re-run the WHOLE superbuild — re-downloading openssl/ffmpeg/
                        // cjson from GitHub (which flakes badly on the China network) and recompiling every
                        // dep for all four ABIs. Instead MediaCodec is forced ON inside the CMake layer
                        // (client/Android/cmake/ExternalFreeRDP.cmake: -DWITH_MEDIACODEC:BOOL=ON), which keeps
                        // the config hash stable so the cached deps are reused and only FreeRDP rebuilds.
                        "-DWITH_OPUS=OFF",
                        "-DWITH_WEBP=OFF",
                        "-DWITH_JPEG=OFF",
                        "-DWITH_PNG=OFF",
                    )
                }
            }
        }
    }

    if (nativeAbi != null) {
        externalNativeBuild {
            cmake {
                path = file("../third_party/FreeRDP/client/Android/Studio/freeRDPCore/src/main/cpp/CMakeLists.txt")
                version = "4.1.2"
            }
        }
    }

    packaging {
        jniLibs {
            // Keep every bundled .so out of AGP's merge-time dedup drop for all four ABIs.
            // libfreerdp-android.so is deliberately excluded (single JNI entry, no duplicate source).
            val packagedLibs = listOf(
                "libfreerdp3", "libfreerdp-client3", "libwinpr3", "libc++_shared",
                "libssl", "libcrypto", "libcjson", "liburiparser",
            )
            for (abi in supportedAbis) {
                for (lib in packagedLibs) {
                    pickFirsts += "lib/$abi/$lib.so"
                }
            }
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

    testImplementation(libs.junit)
}
