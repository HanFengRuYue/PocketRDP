plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
}

detekt {
    parallel = true
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
    autoCorrect = false
    // Re-run with `--create-baseline` after major refactors to refresh.
    baseline = file("$rootDir/detekt-baseline.xml")
    source.setFrom(
        files(
            "$rootDir/app/src",
            "$rootDir/core-ui/src",
            "$rootDir/core-data/src",
            "$rootDir/core-rdp/src",
            "$rootDir/feature-connections/src",
            "$rootDir/feature-session/src",
        ),
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    // Skip the FreeRDP submodule and the LibFreeRDP shim — they follow upstream's
    // Java style, not ours.
    exclude("**/third_party/**", "**/com/freerdp/**")
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

