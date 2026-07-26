import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseStorePath = keystoreProps.getProperty("storeFile")
val releaseSigningReady = releaseSigningKeys.all { !keystoreProps.getProperty(it).isNullOrBlank() } &&
    releaseStorePath?.let { rootProject.file(it).isFile } == true
fun isReleaseArtifactTask(taskName: String): Boolean {
    val leaf = taskName.substringAfterLast(':').lowercase()
    val localArtifact = leaf.contains("release") &&
        listOf("assemble", "bundle", "package", "install").any(leaf::startsWith)
    return localArtifact || (leaf.startsWith("publish") && leaf.contains("release"))
}
val releaseArtifactRequested = gradle.startParameter.taskNames.any(::isReleaseArtifactTask)
if (releaseArtifactRequested && !releaseSigningReady) {
    throw GradleException(
        "A release artifact was requested, but keystore.properties is missing, incomplete, " +
            "or points to a missing keystore. PocketRDP will not fall back to the debug signer.",
    )
}

android {
    namespace = "com.hanfengruyue.pocketrdp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hanfengruyue.pocketrdp"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStorePath))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Keep every supported signing scheme explicit. Android 12+ verifies the v3
                // block; v1/v2 remain present for tooling and distribution compatibility, and
                // v4 produces the companion .idsig used by incremental installation.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    if (!releaseSigningReady) {
        // Fail closed without task-name guessing or TaskExecutionGraph listeners (which are not
        // configuration-cache compatible). No release component or APK/AAB-producing task exists
        // until a complete, existing release keystore is configured.
        beforeVariants(selector().withBuildType("release")) { variantBuilder ->
            variantBuilder.enable = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-rdp"))
    implementation(project(":feature-connections"))
    implementation(project(":feature-session"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
