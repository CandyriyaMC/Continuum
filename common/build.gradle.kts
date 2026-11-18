repositories {
    mavenCentral()
}

dependencies {
    api(project(":api"))

    // Adventure goes as api: Component signatures appear in this module's public API.
    // Both platforms provide Adventure at runtime, and the shade bundles exclude net.kyori.
    api(libs.adventure.api)
    api(libs.adventure.minimessage)

    // Cloud core as api: CommandManager sits in the signature of ContinuumCommands.register.
    // Kept out of the shade set (relocated instead), so it stays out of the exploded jar too.
    api(libs.cloud.core)

    // Configurate is bundled into the platform jars with relocation, pulling in snakeyaml.
    implementation(libs.configurate.yaml)
    // Plain serializer for strip(): not part of the public API, so implementation is enough.
    implementation(libs.adventure.plain)
    // Jackson 3 for snapshot (de)serialization. Bundled and relocated in the platform jars.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    compileOnly(libs.slf4j.api)
    compileOnly(libs.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Gradle 9 needs the launcher explicitly, junit-jupiter no longer brings it along.
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.configurate.yaml)
    testImplementation(libs.slf4j.simple)
}
