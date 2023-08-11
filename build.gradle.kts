plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
    kotlin("jvm") version "1.9.0"
    application
}

group = "org.anadoxin"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("commons-codec:commons-codec:1.16.0")
    implementation("org.apache.commons:commons-compress:1.21")
    implementation("commons-io:commons-io:2.13.0")
    implementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    implementation("org.junit.jupiter:junit-jupiter-params:5.8.2")
    implementation("info.picocli:picocli:4.7.4")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(13)
}

application {
    mainClass.set("adx.MainKt")
}