plugins {
    kotlin("jvm") version "1.9.24"
    id("io.qameta.allure") version "2.12.0"
}
repositories { mavenCentral() }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.9"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.apache.kafka:kafka-clients")
    implementation("io.qameta.allure:allure-java-commons:2.29.1")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.qameta.allure:allure-junit5:2.29.1")
    testRuntimeOnly("ch.qos.logback:logback-classic")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
    systemProperty("allure.results.directory", layout.buildDirectory.dir("allure-results").get().asFile.absolutePath)
}
allure {
    report { version.set("2.29.0") }
    adapter {
        autoconfigure.set(false)
        aspectjWeaver.set(false)
        frameworks { junit5 { adapterVersion.set("2.29.1") } }
    }
}
