repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":common"))

    // HikariCP drives the single JDBC pool. Bundled into the platform jars with relocation
    // (it is a pool, not a JDBC driver — nothing resolves it by class name).
    implementation(libs.hikari)
    // JDBC drivers are NEVER relocated in this project: DriverManager and the
    // META-INF/services entries resolve them by class name. Relocating breaks them.
    implementation(libs.mysql.connector.j)
    implementation(libs.mariadb.java.client)
    // MongoDB sync driver: same story, registering via META-INF/services + class names.
    implementation(libs.mongodb.driver.sync)

    compileOnly(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // Integration tests are expected to run against real databases in containers, not mocks.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mongodb)
}
