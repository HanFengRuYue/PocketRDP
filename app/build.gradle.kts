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
val releaseSigningEnvironment = mapOf(
    "storeFile" to "POCKETRDP_RELEASE_STORE_FILE",
    "storePassword" to "POCKETRDP_RELEASE_STORE_PASSWORD",
    "keyAlias" to "POCKETRDP_RELEASE_KEY_ALIAS",
    "keyPassword" to "POCKETRDP_RELEASE_KEY_PASSWORD",
)
val environmentSigningValues = releaseSigningEnvironment.mapValues { (_, environmentName) ->
    providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }
}
val environmentSigningRequested = environmentSigningValues.values.any { it != null }
if (environmentSigningRequested && environmentSigningValues.values.any { it == null }) {
    throw GradleException(
        "PocketRDP CI release signing variables must be provided as a complete set. " +
            "The release variant remains unavailable when the set is incomplete.",
    )
}
fun releaseSigningValue(key: String): String? =
    if (environmentSigningRequested) environmentSigningValues.getValue(key) else keystoreProps.getProperty(key)

val releaseStorePath = releaseSigningValue("storeFile")
val releaseSigningReady = releaseSigningKeys.all { !releaseSigningValue(it).isNullOrBlank() } &&
    releaseStorePath?.let { rootProject.file(it).isFile } == true

val ciVersionName = providers.environmentVariable("POCKETRDP_VERSION_NAME").orNull?.takeIf { it.isNotBlank() }
val ciVersionCode = providers.environmentVariable("POCKETRDP_VERSION_CODE").orNull?.takeIf { it.isNotBlank() }
if ((ciVersionName == null) != (ciVersionCode == null)) {
    throw GradleException("POCKETRDP_VERSION_NAME and POCKETRDP_VERSION_CODE must be provided together.")
}
val releaseVersionName = ciVersionName ?: "1.0.0"
val releaseVersionMatch = Regex(
    "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)",
).matchEntire(releaseVersionName) ?: throw GradleException(
    "PocketRDP release version names must use stable MAJOR.MINOR.PATCH SemVer.",
)
val releaseVersionParts = releaseVersionMatch.groupValues.drop(1).map { component ->
    component.toLongOrNull() ?: throw GradleException("PocketRDP release version components are too large.")
}
val (releaseMajor, releaseMinor, releasePatch) = releaseVersionParts
if (releaseMinor > 999 || releasePatch > 999) {
    throw GradleException("PocketRDP MINOR and PATCH versions must each be between 0 and 999.")
}
val derivedVersionCode = releaseMajor * 1_000_000L + releaseMinor * 1_000L + releasePatch
if (derivedVersionCode !in 1L..2_100_000_000L) {
    throw GradleException("The PocketRDP SemVer-derived version code must be between 1 and 2100000000.")
}
val releaseVersionCode = ciVersionCode?.toIntOrNull() ?: derivedVersionCode.toInt()
if (releaseVersionCode.toLong() != derivedVersionCode) {
    throw GradleException(
        "POCKETRDP_VERSION_CODE must equal MAJOR*1000000 + MINOR*1000 + PATCH.",
    )
}
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
    // Keep APK-side native processing on the same audited toolchain as :core-rdp. Without this,
    // AGP falls back to its default NDK and cannot strip the staged FreeRDP libraries when that
    // unrelated version is not installed on the Windows build host.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.hanfengruyue.pocketrdp"
        minSdk = 31
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStorePath))
                storePassword = releaseSigningValue("storePassword")
                keyAlias = releaseSigningValue("keyAlias")
                keyPassword = releaseSigningValue("keyPassword")
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
