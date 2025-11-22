plugins {
    kotlin("jvm") version "1.9.24"
    id("io.qameta.allure") version "2.12.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.9"))
    implementation("org.jetbrains.exposed:exposed-core:0.57.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.57.0")
    implementation("io.rest-assured:rest-assured:5.5.0")
    implementation("io.rest-assured:kotlin-extensions:5.5.0")
    implementation("io.qameta.allure:allure-rest-assured:2.29.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("org.postgresql:postgresql:42.7.3")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
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
