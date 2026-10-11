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
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.1") // a Ticket's ticking checkboxes
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.1")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    // BrowserTest. 1.60.0 drives Chromium build 1223, which a local ~/Library/Caches/ms-playwright already holds.
    testImplementation("com.microsoft.playwright:playwright:1.60.0")
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
    // CodeMirror 6 as plain ES modules: each file the import map in codemirror.kte names, and only those, copied from
    // the one place pnpm put it: a direct dependency in node_modules, any other package in the hoisted .pnpm root.
    val importMap = file("src/main/jte/codemirror.kte")
    inputs.file(importMap)
    for (path in Regex(""""/vendor/codemirror/([^"]+)"""").findAll(importMap.readText()).map { it.groupValues[1] }) {
        // Gradle also asks before pnpmInstall has run, when neither exists yet; the answer at copy time is what counts.
        from({ listOf("node_modules", "node_modules/.pnpm/node_modules").map { file("$it/$path") }.firstOrNull { it.exists() } ?: emptyList<File>() }) {
            into("static/vendor/codemirror/" + path.substringBeforeLast('/'))
        }
    }
}

tasks.withType<Test> {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// What modeler.js writes as text, checked by Node's own test runner with no browser, as part of `test`.
val nodeTest = tasks.register<com.github.gradle.node.task.NodeTask>("nodeTest") {
    script = file("src/test/js/modeler-text.test.js")
    options = listOf("--test")
}

// Playwright's CLI, such as `./gradlew playwright --args="install chromium"`, for the Chromium BrowserTest drives.
tasks.register<JavaExec>("playwright") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.microsoft.playwright.CLI"
}

tasks.test {
    dependsOn(nodeTest)
    useJUnitPlatform { excludeTags("live") }
    // Only the system folders and a fake gh, so a CLI run by its bare name, such as claude or nono, is never the real one.
    environment("PATH", "${projectDir}/src/test/resources/fake-gh:/usr/bin:/bin")
    // Ants must strip these from every Agent's environment, even when the allow-list names them.
    environment("ANTHROPIC_API_KEY", "sk-ant-test")
    environment("CLAUDE_CODE_RETRY_WATCHDOG", "1")
    // BrowserTest drives the Chromium the playwright task installed; it never downloads one itself.
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    // Test classes run in a random order (junit-platform.properties), from a seed it prints; -PtestSeed=… replays one.
    // Set when the task runs, so a new seed never makes an up-to-date test task run again.
    val testSeed = findProperty("testSeed")
    doFirst {
        val seed = testSeed ?: System.nanoTime()
        systemProperty("junit.jupiter.execution.order.random.seed", seed)
        logger.lifecycle("Test classes in random order, seed $seed")
    }
}

// The tests that run the real claude on your login, kept out of `test`: ./gradlew liveTest
tasks.register<Test>("liveTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("live") }
}
