import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "de.amongusmanager"
version = "2.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(module = "opus-java") // Der Bot spielt keine Sprache ab
    }
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("ch.qos.logback:logback-classic:1.5.38")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Läuft ab Java 17 (Pelican-Java-Eggs: 17, 21, …), gebaut wird mit einem neueren JDK
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("de.amongusmanager.crewbot.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir // .env und data/ liegen im Projektordner
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
    workingDir = projectDir
}

tasks.shadowJar {
    archiveFileName.set("AmongUsManager.jar")
    mergeServiceFiles()
}
