import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // PlaceholderAPI is only published here; compileOnly, so the jar is never bundled
    maven("https://repo.helpch.at/releases/")
}

configurations.all {
    // paper-api and folia-api both ship the bukkit capability (and, new with the 26-stable
    // folia line, paper-api's own capability). Gradle refuses to include both in one graph,
    // so one has to win per capability. Unlock Folia for compile: folia-api is the superset
    // surface (Paper API + regionized schedulers), so it always takes it; the build numbers
    // of the two forks drift independently and any fixed comparison would pick wrongly.
    // Runtime stays identical on both platforms — both provide these APIs.
    fun selectFolia(details: org.gradle.api.artifacts.CapabilityResolutionDetails) {
        // candidates do not implement the public ModuleComponentIdentifier in Gradle internals,
        // so match on the rendered id instead of casting.
        details.candidates
            .find { it.id.toString().startsWith("dev.folia:folia-api:") }
            ?.let { details.select(it) }
    }
    resolutionStrategy.capabilitiesResolution.withCapability("org.bukkit:bukkit") {
        selectFolia(this)
    }
    resolutionStrategy.capabilitiesResolution.withCapability("com.destroystokyo.paper:paper-api") {
        selectFolia(this)
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":common"))
    compileOnly(libs.paper.api)
    // Folia API is the same surface with the regionized scheduler types. compileOnly:
    // Paper and Folia both provide the API at runtime, we must not bundle either one.
    compileOnly(libs.folia.api)
    // Configurate is bundled into this jar (via :common's dependencies) with relocation.
    // compileOnly: we compile against the classes; the jar supplies them at runtime.
    compileOnly(libs.configurate.yaml)
    compileOnly(libs.annotations)
    // bStats metrics (opt-in, inert until the service id is set in BukkitMetricsService).
    // Shaded and relocated like every other bundled library — never compileOnly here:
    // nothing else provides these classes on the classpath at runtime.
    implementation(libs.bstats.bukkit)
    // PlaceholderAPI: optional host, compileOnly + runtime detection; not shaded.
    // Not in the version catalog yet; keep in sync with research-metrics (2.12.3).
    compileOnly("me.clip:placeholderapi:2.12.3")
    // tests instantiate the internal expansion against the real API surface
    testImplementation("me.clip:placeholderapi:2.12.3")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // test-only: payload wire contract pinned against the real wire conventions
    // (snake_case, unknown properties tolerated) without putting Jackson on the main classpath
    testImplementation(platform(libs.jackson.bom))
    testImplementation(libs.jackson.databind)
    // Jackson bean introspection resolves every method of the payload records, including
    // the ones taking Bukkit types, so the platform API must be on the test runtime
    // classpath (it is in production)
    testImplementation(libs.folia.api)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.slf4j.simple)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        // filteringCharset is required, but expand() must not be relied on for anything
        // beyond plain ASCII: Gradle reads that source file in the platform encoding otherwise.
        filteringCharset = "UTF-8"
        val props = mapOf("version" to version)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    named<Jar>("jar") {
        // keep the plain (unshaded) artifact distinguishable from the shaded one
        archiveBaseName.set("continuum-paper-plain")
    }

    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        archiveBaseName.set("continuum-paper")
        // Merge META-INF/services from all bundled jars (Configurate's service registration,
        // the MongoDB driver when it lands), otherwise the SPI files silently overwrite each other.
        mergeServiceFiles()
        // In Shadow 9.x, service files merged by mergeServiceFiles are rewritten to the
        // relocated names as well, but a same-name collision between a relocated and a
        // non-relocated jar still picks one — so keep the duplicatesStrategy as in the
        // Same-name entries are kept, not dropped: both relocated and bundled service
        // files land in the jar side by side and the duplicates strategy includes them.
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        // Relocation set (bundled third-party code, relocated to lol.candyriya.continuum.libs.*):
        //   configurate / snakeyaml / geantyref — config loading, implementation detail
        //   hikari                             — a pool, class names are ours to pick
        //   rabbitmq (com.rabbitmq)            — AMQP client
        //   netty (io.netty)                   — AMQP client's transport; both platforms ship their own netty, never clash
        //   protobuf (com.google.protobuf)      — pulled in by mysql-connector-j for the X DevAPI
        //   option (net.kyori.option)          — configurate-core needs it, the platforms do NOT provide it
        //   jackson (tools.jackson + com.fasterxml.jackson.annotation) — MUST not leak, other plugins bundle it too
        // Never relocated (class names are part of the resolution contract):
        //   net.kyori.adventure*.*, adventure-key — both platforms provide these at runtime (excluded from the jar below)
        //   org.jspecify:jspecify              — annotations-only, provided by both platforms
        //   org.slf4j.*, org.jetbrains.*       — provided by the platform / compileOnly, excluded from the jar
        //   JDBC drivers: com.mysql, org.mariadb.jdbc — DriverManager resolves by class name
        //   MongoDB driver: org.bson, com.mongodb     — registers via META-INF/services with its own class names
        //   kotlin/coroutines — nothing bundles them today; if something does, those stay unrelocated
        relocate("org.spongepowered.configurate", "lol.candyriya.continuum.libs.configurate")
        relocate("org.bstats", "lol.candyriya.continuum.libs.bstats")
        relocate("org.yaml.snakeyaml", "lol.candyriya.continuum.libs.snakeyaml")
        relocate("io.leangen.geantyref", "lol.candyriya.continuum.libs.geantyref")
        relocate("com.zaxxer.hikari", "lol.candyriya.continuum.libs.hikari")
        relocate("com.rabbitmq", "lol.candyriya.continuum.libs.rabbitmq")
        relocate("io.netty", "lol.candyriya.continuum.libs.netty")
        relocate("com.google.protobuf", "lol.candyriya.continuum.libs.protobuf")
        relocate("net.kyori.option", "lol.candyriya.continuum.libs.option")
        relocate("tools.jackson", "lol.candyriya.continuum.libs.jackson")
        relocate("com.fasterxml.jackson", "lol.candyriya.continuum.libs.jackson")
        dependencies {
            // Cloud is dead weight here: the backend registers no commands. cloud-core is
            // excluded outright; cloud-services arrives as its transitive dependency and
            // Shadow's filters do not reach transitives, so it needs its own line.
            exclude(dependency("org.incendo:cloud-core:.*"))
            exclude(dependency("org.incendo:cloud-services:.*"))
            // Adventure is provided by Paper — keep the platform-provided kyori modules out of the jar.
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
