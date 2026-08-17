plugins {
    application
}

group = "com.example"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.guava:guava:33.2.1-jre")
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}

application {
    mainClass.set("com.example.Main")
}

tasks.jar {
    archiveBaseName.set("hello-world")
    manifest {
        attributes["Main-Class"] = "com.example.Main"
        attributes["Class-Path"] = configurations.runtimeClasspath.get().files
            .joinToString(" ") { it.toURI().toString() }
    }
}
