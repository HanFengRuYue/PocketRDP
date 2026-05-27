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

    // Native build is wired in (CMake config, submodule, NDK 27, pkg-config, perl, make
    // all set up) but the externalNativeBuild block stays commented because OpenSSL's
    // Configure script needs a Perl distribution with the Locale::Maketext::Simple
    // module — Git for Windows' perl 5.42.2 doesn't ship it, and Strawberry Perl
    // portable downloads kept hitting 404 from current network. To finish native:
    //   1. Install Strawberry Perl: https://strawberryperl.com (any 5.32+ portable).
    //   2. Make sure `perl -MLocale::Maketext::Simple -e 1` returns no error.
    //   3. Uncomment the externalNativeBuild + ndk + packaging blocks below.
    //   4. ./gradlew :core-rdp:externalNativeBuildDebug (30-60 min for arm64-v8a).
    /*
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
        ndk { abiFilters += listOf("arm64-v8a") }
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
