repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":common"))

    // RabbitMQ client. Bundled into the platform jars with relocation (see the paper/velocity shadowJar config).
    implementation(libs.amqp.client)

    // Jackson 3 for the plugin-message payload codec. Reuses common's mapper configuration
    // (see common's internal JacksonMappers); bundled and relocated in the platform jars too.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    compileOnly(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // Transport is tested against a real broker in a container, not mocks.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.slf4j.simple)
}
