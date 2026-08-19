plugins {
    `java-library`
}

group = "com.example.wordcounter"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.guava:guava:33.2.1-jre")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testImplementation("org.mockito:mockito-core:5.23.0")
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("word-counter")
}
