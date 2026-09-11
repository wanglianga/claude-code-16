plugins {
    kotlin("jvm") version "1.9.24"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "city.market"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")
    implementation("io.ktor:ktor-server-sessions-jvm:2.3.12")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.12")
    implementation("io.ktor:ktor-server-html-builder-jvm:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.11.0")

    // DB: Exposed + PostgreSQL + HikariCP
    implementation("org.jetbrains.exposed:exposed-core:0.50.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.50.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.50.1")
    implementation("org.jetbrains.exposed:exposed-java-time:0.50.1")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("ch.qos.logback:logback-classic:1.5.6")
}

application {
    mainClass.set("city.market.ApplicationKt")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
    archiveFileName.set("scale-guard-all.jar")
}

kotlin {
    jvmToolchain(17)
}
