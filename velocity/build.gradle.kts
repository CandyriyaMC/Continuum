import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.velocity)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":common"))
    implementation(project(":storage"))
    implementation(project(":messaging"))
    compileOnly(libs.velocity.api)
    // Runs the velocity-api annotation processor, which reads @Plugin at compile time
    // and writes the plugin descriptor (id/name/version/...) into the jar.
    annotationProcessor(libs.velocity.api)
    // Cloud for velocity: brings the Velocity binding plus cloud-core.
    implementation(libs.cloud.velocity)
    compileOnly(libs.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.slf4j.simple)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runVelocity {
        velocityVersion(libs.versions.velocity.api.get())
    }

    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        archiveBaseName.set("continuum-velocity")
        // Merge META-INF/services from all bundled jars (Configurate, Cloud, MongoDB
        // driver registration), otherwise the SPI files silently overwrite each other.
        mergeServiceFiles()
        // See paper/build.gradle.kts for the shared relocation rules — both platform jars
        // use the same set: relocate bundled third-party code except the JDBC/MongoDB
        // drivers (resolved by class name via DriverManager/ServiceLoader) and the
        // platform-provided libraries (Adventure, jspecify, slf4j — excluded below).
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        relocate("org.spongepowered.configurate", "lol.candyriya.continuum.libs.configurate")
        relocate("org.yaml.snakeyaml", "lol.candyriya.continuum.libs.snakeyaml")
        relocate("io.leangen.geantyref", "lol.candyriya.continuum.libs.geantyref")
        relocate("org.incendo.cloud", "lol.candyriya.continuum.libs.cloud")
        relocate("com.zaxxer.hikari", "lol.candyriya.continuum.libs.hikari")
        relocate("com.rabbitmq", "lol.candyriya.continuum.libs.rabbitmq")
        relocate("io.netty", "lol.candyriya.continuum.libs.netty")
        relocate("com.google.protobuf", "lol.candyriya.continuum.libs.protobuf")
        relocate("net.kyori.option", "lol.candyriya.continuum.libs.option")
        relocate("tools.jackson", "lol.candyriya.continuum.libs.jackson")
        relocate("com.fasterxml.jackson", "lol.candyriya.continuum.libs.jackson")
        dependencies {
            // Velocity 4 ships Adventure 5: keep the platform-provided kyori modules out of the jar.
            // (net.kyori:option is NOT provided and is bundled — configurate needs it.)
            exclude(dependency("net.kyori:adventure-api:.*"))
            exclude(dependency("net.kyori:adventure-key:.*"))
            exclude(dependency("net.kyori:adventure-text-minimessage:.*"))
            exclude(dependency("net.kyori:adventure-text-serializer-plain:.*"))
            exclude(dependency("net.kyori:adventure-text-serializer-gson:.*"))
            exclude(dependency("net.kyori:adventure-text-serializer-legacy:.*"))
            exclude(dependency("net.kyori:adventure-text-serializer-ansi:.*"))
            exclude(dependency("net.kyori:adventure-text-logger-slf4j:.*"))
            exclude(dependency("org.jspecify:jspecify:.*"))
            exclude(dependency("org.slf4j:.*:.*"))
            exclude(dependency("org.jetbrains:annotations:.*"))
        }
    }
}

// Inject the version into BuildConstants before compilation so @Plugin does not hardcode it.
// The constants file itself stays ASCII: expand() mangles anything else,
// and this file is a Velocity-plugin descriptor input, not prose.
val constantsSource = file("src/main/templates")
val constantsDest = layout.buildDirectory.dir("generated/sources/templates")
val generateBuildConstants = tasks.register<Copy>("generateBuildConstants") {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)

    from(constantsSource)
    into(constantsDest)
    expand(props)
}

sourceSets.main.configure { java.srcDir(generateBuildConstants.map { it.outputs }) }
