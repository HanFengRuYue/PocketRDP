plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
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
            "$rootDir/core-logging/src",
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

val auditedNetty4Version = "4.1.136.Final"
val auditedBouncyCastleVersion = "1.85"
val auditedHttpClient4Version = "4.5.14"
val auditedCommonsLang3Version = "3.20.0"

subprojects {
    // Pin the fully resolved transitive graph as well as the direct versions in the catalog.
    // Refresh intentionally with the module `dependencies` tasks plus `--write-locks`.
    dependencyLocking {
        lockAllConfigurations()
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "io.netty" &&
                    requested.version.orEmpty().startsWith("4.1.") -> {
                    useVersion(auditedNetty4Version)
                    because("OSV audit: keep AGP test tooling on the patched Netty 4.1 line")
                }
                requested.group == "org.bouncycastle" &&
                    requested.name.endsWith("-jdk18on") -> {
                    useVersion(auditedBouncyCastleVersion)
                    because("OSV audit: update lint/tooling cryptography providers")
                }
                requested.group == "org.apache.httpcomponents" &&
                    requested.name == "httpclient" -> {
                    useVersion(auditedHttpClient4Version)
                    because("OSV audit: update the lint/tooling HTTP client")
                }
                requested.group == "org.apache.commons" &&
                    requested.name == "commons-lang3" -> {
                    useVersion(auditedCommonsLang3Version)
                    because("OSV audit: update the lint/tooling utility library")
                }
            }
        }
    }
}
