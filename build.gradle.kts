import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "de.amongusmanager"
version = "2.5.0"

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
    exclude("web/**", "web-cosmetics/**", "seiten/**") // die gebaute Stats-Website (+ Kosmetik-Bilder) gehört nur ins StatsWebsite.jar
    // Service-Dateien (z.B. JDBC-Treiber von SQLite UND MariaDB) zusammenführen statt nur die erste zu behalten
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
}

// Stats-Website (Quelltext der Seite in web/, gebaut nach src/main/resources/web): eigenes JAR mit eigenem Startpunkt,
// läuft als eigener Server im Panel (java -jar StatsWebsite.jar) und liest die Datenbank des Bots nur.
val statsWebsiteJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "Stats-Website als Fat-JAR: build/libs/StatsWebsite.jar"
    archiveFileName.set("StatsWebsite.jar")
    from(sourceSets.main.get().output)
    configurations.set(listOf(project.configurations.runtimeClasspath.get()))
    manifest { attributes["Main-Class"] = "de.amongusmanager.crewbot.web.StatsWebsiteKt" }
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
}

tasks.register<JavaExec>("runWebsite") {
    group = "application"
    description = "Startet die Stats-Website lokal (Port 8080, liest data/crewbot.db bzw. die .env)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("de.amongusmanager.crewbot.web.StatsWebsiteKt")
    workingDir = projectDir
}
