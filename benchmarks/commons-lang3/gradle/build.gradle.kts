plugins {
    `java-library`
}

group = "org.apache.commons"
version = "3.19.0"

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("commons-lang3")
}
