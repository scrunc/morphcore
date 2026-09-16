plugins {
    `java`
    `maven-publish`
}

group = "dev.servereer"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

// Publishes the plain (unshaded) compiled classes to mavenLocal as dev.servereer:morphcore:<version>, so
// Maven-built consumer plugins (e.g. KOTH, which isn't part of this Gradle build) can add it as a
// <scope>provided</scope> compile-time dependency. Gradle-built consumers should prefer an
// includeBuild(...) composite instead. Either way the real classes are resolved at RUNTIME from the
// MorphCore plugin's own classloader via a plugin `softdepend` — this published artifact is compile-time
// only and must never be shaded/bundled into a consumer's jar.
//
// Re-run `./gradlew publishToMavenLocal` here whenever this API changes, before rebuilding consumers.
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.servereer"
            artifactId = "morphcore"
            version = project.version.toString()
            from(components["java"])
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.jar {
    archiveBaseName.set("MorphCore")
}
