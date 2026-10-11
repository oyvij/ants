import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.0.8"
    id("gg.jte.gradle") version "3.2.4"
}

group = "no.oyvij"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

val operaton = "2.1.5"
val jte = "3.2.4"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.operaton.bpm:operaton-bom:$operaton"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.operaton.bpm.springboot:operaton-bpm-spring-boot-starter-rest:$operaton")
    implementation("org.operaton.bpm.springboot:operaton-bpm-spring-boot-starter-webapp:$operaton")
    implementation("gg.jte:jte-spring-boot-starter-4:$jte")
    implementation("gg.jte:jte-kotlin:$jte")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jte {
    generate()
}

tasks.test {
    useJUnitPlatform()
}
