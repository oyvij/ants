import org.springframework.boot.gradle.plugin.SpringBootPlugin
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.0.8"
    id("gg.jte.gradle") version "3.2.4"
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "no.oyvij"
version = "0.11.0"

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
    runtimeOnly("org.postgresql:postgresql") // engine state on a server, such as in the Docker image

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    // BrowserTest. 1.60.0 drives Chromium build 1223, which a local ~/Library/Caches/ms-playwright already holds.
    testImplementation("com.microsoft.playwright:playwright:1.60.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jte {
    generate()
}

// META-INF/build-info.properties: the version the update check compares a release with. No build time, so a build
// that changed nothing stays up to date.
springBoot {
    buildInfo { excludes = setOf("time") }
}

// The Dockerfile copies it by this name.
tasks.bootJar {
    archiveFileName = "ants.jar"
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

// The release: Ants with its own trimmed Java runtime, laid out as the launcher expects (bin/ants, lib/ants.jar,
// runtime/), as build/dist/ants-<version>-macos-arm64.tar.gz beside its SHA256SUMS, which install.sh checks.
// It is only ever built for macOS arm64 (ci.yml), but the runtime is the build machine's, so CI's tests on Linux
// install a Linux one under the same name.
val image = layout.buildDirectory.dir("image")
val jlink = tasks.register<Exec>("jlink") {
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }.get().metadata.installationPath
    outputs.dir(image.map { it.dir("runtime") })
    doFirst { delete(image) }
    // java.se and the jdk modules Spring Boot, H2 and Operaton reach for; no compiler, jshell or other tools.
    commandLine(
        jdk.file("bin/jlink").asFile, "--output", image.get().dir("runtime").asFile,
        "--add-modules", "java.se,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.management,jdk.crypto.cryptoki,jdk.naming.dns",
        "--strip-debug", "--no-header-files", "--no-man-pages",
    )
}
// Updates.kt's tarball() names it the same way.
val tarballFile = layout.buildDirectory.file("dist/ants-$version-macos-arm64.tar.gz")
val tarball = tasks.register<Exec>("tarball") {
    val bootJar = tasks.bootJar.flatMap { it.archiveFile }
    val dist = layout.buildDirectory.dir("dist").get()
    val file = tarballFile.get().asFile
    dependsOn(jlink)
    inputs.file(bootJar)
    inputs.dir("src/dist")
    outputs.dir(dist)
    doFirst {
        delete(dist)
        copy { from("src/dist"); into(image) }
        copy { from(bootJar); into(image.get().dir("lib")); rename { "ants.jar" } }
        dist.asFile.mkdirs()
    }
    // The system tar keeps the runtime's symlinks and modes as they are; on macOS, without AppleDouble ._ files.
    environment("COPYFILE_DISABLE", "1")
    commandLine("tar", "-czf", file, "-C", image.get().asFile, "bin", "lib", "runtime")
    doLast {
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        dist.file("SHA256SUMS").asFile.writeText("$sha  ${file.name}\n")
    }
}

tasks.test {
    dependsOn(nodeTest)
    dependsOn(tasks.bootJar) // DockerTest builds the image from it
    useJUnitPlatform { excludeTags("live") }
    // LauncherTest runs the boot jar as the tarball's lib/ants.jar; InstallTest installs the tarball itself.
    val bootJar = tasks.bootJar.flatMap { it.archiveFile }
    inputs.file(bootJar)
    systemProperty("ants.jar", bootJar.get().asFile.path)
    inputs.files(tarball)
    systemProperty("ants.tarball", tarballFile.get().asFile.path)
    // Only the system folders and a fake gh, so a CLI run by its bare name, such as claude or nono, is never the real one.
    environment("PATH", "${projectDir}/src/test/resources/fake-gh:/usr/bin:/bin")
    // Ants must strip these from every Agent's environment, even when the allow-list names them.
    environment("ANTHROPIC_API_KEY", "sk-ant-test")
    environment("CLAUDE_CODE_RETRY_WATCHDOG", "1")
    environment("OPENAI_API_KEY", "sk-openai-test")
    environment("CODEX_API_KEY", "sk-codex-test")
    // BrowserTest drives the Chromium the playwright task installed; it never downloads one itself.
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    // CI runs the suite as jobs at once (ci.yml): -Pshard=look or browser runs only those classes, rest every other.
    val shards = mapOf("look" to listOf("ants.LookTest"), "browser" to listOf("ants.BrowserTest", "ants.DockerTest", "ants.PwaTest", "ants.ChromiumLaunchTest"))
    when (val shard = findProperty("shard")?.toString()) {
        null -> {}
        "rest" -> shards.values.flatten().forEach(filter::excludeTestsMatching)
        else -> shards.getValue(shard).forEach(filter::includeTestsMatching)
    }
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
