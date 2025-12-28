plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"

    id("io.ktor.plugin") version "3.3.2"
    application
}

group = "com.example"
version = "0.0.1"

application {
    mainClass.set("com.example.ApplicationKt")
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

    implementation("ch.qos.logback:logback-classic:1.5.16")

    implementation("io.ktor:ktor-client-core-jvm:3.3.2")
    implementation("io.ktor:ktor-client-cio-jvm:3.3.2")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:3.3.2")
    implementation("io.ktor:ktor-client-timeout-jvm:3.3.2")

    testImplementation("io.ktor:ktor-server-test-host-jvm:3.3.2")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:3.3.2")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.3.2")
}
