plugins {
    kotlin("jvm") version "1.9.24"
    id("io.qameta.allure") version "2.12.0"
}

repositories {
    mavenCentral()
}


dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.9"))
    implementation("org.springframework:spring-jdbc")
    implementation("io.rest-assured:kotlin-extensions:5.5.0")
    implementation("io.qameta.allure:allure-rest-assured:2.29.1")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.apache.kafka:kafka-clients")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
    testImplementation("ch.qos.logback:logback-classic")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("io.qameta.allure:allure-junit5:2.29.1")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

allure {
    report {
        version.set("2.29.0")
    }
    adapter {
        autoconfigure.set(true)
        frameworks {
            junit5 {
                adapterVersion.set("2.29.0")
            }
        }
    }
}

tasks.register("deleteAllureReport", Delete::class) {
    delete(rootProject.layout.buildDirectory.dir("reports/allure-report"))
}

tasks.named<Test>("test") {
    dependsOn(tasks.named("deleteAllureReport"))
    useJUnitPlatform()
    finalizedBy(tasks.named("allureReport"))
}

tasks.register<JavaExec>("runMyKotlinFunction") {
    group = "custom"
    description = "Runs a Kotlin function from test sources"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("helpers.MyRunner")
    dependsOn("testClasses")
}