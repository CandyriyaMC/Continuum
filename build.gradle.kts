// Shared conventions for all modules: Java, encoding, tests.
// Platform specifics (shade, run tasks) live in the module build files themselves.
import org.gradle.api.plugins.JavaPluginExtension

subprojects {
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        // One toolchain (25) for every module: paper-api 26.x and velocity-api 4.x
        // are built for 25, older javac cannot even read their class files.
        // A missing JDK is downloaded automatically via the Foojay resolver from settings.gradle.kts.
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
        }
    }
}
