import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "de.amongusmanager"
version = "2.2.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(module = "opus-java") // Der Bot spielt keine Sprache ab
    }
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.10") // MySQL/MariaDB (z.B. Datenbank des Panels)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("ch.qos.logback:logback-classic:1.5.38")

    testImplementation(kotlin("test"))
    testImplementation("ch.vorburger.mariaDB4j:mariaDB4j-core:3.3.1") // echter MariaDB-Server für die Tests
    testRuntimeOnly("ch.vorburger.mariaDB4j:mariaDB4j-db-winx64:11.4.5")
    testRuntimeOnly("ch.vorburger.mariaDB4j:mariaDB4j-db-linux64:11.4.5")
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
    systemProperty("jarSmoke", System.getProperty("jarSmoke") ?: "false") // JarSmokeTest: gradlew shadowJar test -DjarSmoke=true
}

tasks.shadowJar {
    archiveFileName.set("AmongUsManager.jar")
    // Service-Dateien (z.B. JDBC-Treiber von SQLite UND MariaDB) zusammenführen statt nur die erste zu behalten
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
}
