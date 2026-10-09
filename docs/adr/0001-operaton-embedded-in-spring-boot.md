# Operaton embedded in a Kotlin Spring Boot service

Ants is one Kotlin process on Spring Boot 4 (Java 25, Gradle with Kotlin scripts) that embeds Operaton
through its Spring Boot starter, adding the REST and webapp starters so Cockpit is there for debugging.
Its Task worker runs inside the same process and fetches external tasks through Operaton's Java API,
not over REST. This revises the earlier plan of a standalone Operaton server with workers long-polling
its REST API. Operaton ties Ants to a JVM whatever else we choose, so a second process would add
another thing to install, start and upgrade, and give nothing back. Users still never install a JVM:
Ants ships as a jlink/jpackage app with its own trimmed Java runtime.

## Considered Options

- **A native binary (GraalVM native-image):** Operaton has no native-image support and the one public
  Camunda 7 attempt failed, so this would be an unsupported research project.
- **Operaton's Node.js task client:** it only replaces the worker. Operaton still runs as a Java server
  beside it, so you get two runtimes and two processes.
- **Quarkus:** Operaton's Quarkus extension only has the engine, with no REST API and no Cockpit, and
  Quarkus's native mode is the part Operaton can't use.
- **Plain engine without Spring (with Ktor or Javalin):** lighter, but it loses Cockpit and the setup
  Operaton documents and tests. Startup time and memory don't matter for a single-user service that
  is always on.

## Consequences

- The web UI is rendered on the server with JTE templates. The modeler's `camunda-bpmn-js` and
  `camunda-dmn-js` come from pnpm, run by the Gradle build with its own Node. Their `dist/` files are
  copied into the static resources, so there's no bundler and the installed app carries no Node.
- The jpackage app is built on each OS it targets, and on macOS it must be signed and notarized.
