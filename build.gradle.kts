plugins {
    java
}

group = "tr.kozalaksmp"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// Keep the plugin identity unchanged; only sort the JAR near the top of plugins/.
tasks.jar {
    archiveFileName.set("aaKozalakSettings.jar")
}
