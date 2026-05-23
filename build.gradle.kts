plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
}

group = "f1sim"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // DB
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")

    // HTTP
    implementation("io.javalin:javalin:6.3.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.slf4j:slf4j-api:2.0.16")
}

application {
    mainClass.set("f1sim.MainKt")
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<JavaExec> {
    // Forwards system properties like -Df1sim.db.url=... to the running app
    systemProperties = System.getProperties().mapKeys { it.key.toString() }
}
