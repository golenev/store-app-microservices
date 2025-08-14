plugins {
    kotlin("jvm") version "1.9.24"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.9"))
    implementation("org.springframework:spring-jdbc")
    implementation("io.rest-assured:kotlin-extensions:5.5.0")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.apache.kafka:kafka-clients")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
    testImplementation("ch.qos.logback:logback-classic")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

