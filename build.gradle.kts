plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"

    id("io.ktor.plugin") version "3.3.2"
    application
}

group = "com.example"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.3.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.3.2")

    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.3.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.3.2")

    implementation("io.ktor:ktor-server-request-validation-jvm:3.3.2")
    implementation("io.ktor:ktor-server-call-logging-jvm:3.3.2")
    implementation("io.ktor:ktor-server-call-id-jvm:3.3.2")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.3.2")
    implementation("io.ktor:ktor-server-config-yaml:3.3.2")

    implementation("ch.qos.logback:logback-classic:1.5.16")

    implementation("io.ktor:ktor-client-core-jvm:3.3.2")
    implementation("io.ktor:ktor-client-cio-jvm:3.3.2")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:3.3.2")

    testImplementation("io.ktor:ktor-server-test-host-jvm:3.3.2")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:3.3.2")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.3.2")

    // Postgres JDBC driver
    implementation("org.postgresql:postgresql:42.7.4")

    // HikariCP (connection pool)
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Flyway migrations
    implementation("org.flywaydb:flyway-core:9.22.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("smoke")
    }
}

tasks.register<Test>("smokeTest") {
    useJUnitPlatform {
        includeTags("smoke")
    }
}