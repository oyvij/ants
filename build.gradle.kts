import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.0.8"
    id("gg.jte.gradle") version "3.2.4"
    id("com.github.node-gradle.node") version "7.1.0"
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
    implementation("org.operaton.bpm:operaton-engine-plugin-spin")
    implementation("org.operaton.spin:operaton-spin-dataformat-json-jackson")
    implementation("gg.jte:jte-spring-boot-starter-4:$jte")
    implementation("gg.jte:jte-kotlin:$jte")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.networknt:json-schema-validator:2.0.4")
    implementation("tools.jackson.dataformat:jackson-dataformat-toml") // reads Varde's config.toml
    implementation("org.commonmark:commonmark:0.30.1")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jte {
    generate()
}

// Node runs only in the build: pnpm fetches the front-end libraries, whose dist/ goes into the static resources.
node {
    download = true
    version = "24.21.0"
    pnpmVersion = "10.34.6"
}

tasks.processResources {
    dependsOn(tasks.named("pnpmInstall"))
    from("node_modules/camunda-bpmn-js/dist") {
        include("*.production.min.js", "assets/**")
        into("static/vendor/camunda-bpmn-js")
    }
    // CodeMirror 6 as plain ES modules, each package under its own name, for the import map in codemirror.kte.
    for (packages in listOf("node_modules", "node_modules/.pnpm/node_modules")) from(packages) {
        include("codemirror/dist/index.js", "@codemirror/*/dist/index.js", "@lezer/*/dist/index.js")
        include("@marijn/find-cluster-break/src/index.js", "crelt/index.js", "style-mod/src/style-mod.js", "w3c-keyname/index.js")
        into("static/vendor/codemirror")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE // pnpm may hoist a direct dependency into both roots
    }
}

tasks.withType<Test> {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test {
    useJUnitPlatform { excludeTags("live") }
    // Ants must strip these from every Agent's environment, even when the allow-list names them.
    environment("ANTHROPIC_API_KEY", "sk-ant-test")
    environment("CLAUDE_CODE_RETRY_WATCHDOG", "1")
}

// The tests that run the real claude on your login, kept out of `test`: ./gradlew liveTest
tasks.register<Test>("liveTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("live") }
}
