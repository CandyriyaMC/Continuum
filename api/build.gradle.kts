repositories {
    mavenCentral()
}

dependencies {
    // Nullability annotations for public signatures. compileOnly: not needed at runtime.
    compileOnly(libs.annotations)

    // tests annotate overrides the same way the main sources do
    testCompileOnly(libs.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Gradle 9 needs the launcher explicitly, junit-jupiter no longer brings it along.
    testRuntimeOnly(libs.junit.launcher)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.slf4j.simple)
}
