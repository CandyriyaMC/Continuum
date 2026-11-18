plugins {
    // JDK resolver for toolchains via Foojay: without the convention plugin the
    // resolver is not registered and Gradle falls back to auto-provisioning
    // (deprecated in 9.x, an error in 10.x). The -convention variant is the one to use.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "continuum"

include("api", "common", "storage", "messaging", "paper", "velocity")
