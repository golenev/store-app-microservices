plugins {
    kotlin("jvm") version "1.9.24"
    id("io.qameta.allure") version "2.12.0"
}
repositories { mavenCentral() }
dependencies {
    implementation("io.kotest:kotest-assertions-core:5.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.9"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.apache.kafka:kafka-clients")
    implementation("io.qameta.allure:allure-java-commons:2.29.1")
    implementation("org.jetbrains.exposed:exposed-core:0.49.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.49.0")
    implementation("io.rest-assured:rest-assured:5.4.0")
    implementation("io.qameta.allure:allure-rest-assured:2.29.1")
    implementation("com.codeborne:selenide:7.12.1")
    implementation("com.codeborne:selenide-proxy:7.12.1")
    implementation("io.qameta.allure:allure-selenide:2.29.1")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.junit.jupiter:junit-jupiter-api")
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
    systemProperty("allure.results.directory", System.getenv("E2E_ALLURE_RESULTS") ?: layout.buildDirectory.dir("allure-results").get().asFile.absolutePath)
    systemProperty("e2e.revision", System.getenv("E2E_REVISION") ?: "local")
}
allure {
    report { version.set("2.29.0") }
    adapter {
        autoconfigure.set(true)
        aspectjWeaver.set(true)
        frameworks { junit5 { adapterVersion.set("2.29.1") } }
    }
}
