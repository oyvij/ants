# Ants

Runs BPMN Processes whose Tasks are AI agents. You draw how a kind of job gets done once, as a Process, then start a
Run with a prompt and walk away. Agents do the steps they can, and the Run waits on you only where the Process says
it must. The spec is `docs/spec.md`; the vocabulary is `GLOSSARY.md`.

## Getting started

There is no installer yet (`install.sh` and the `ants` launcher arrive with the packaging tickets, #74–#78), so run
Ants from source.

You need:

- Java 25. Gradle and Node are fetched by the build.
- `claude` (and `codex`, if you use it), `git` and `gh` on your login shell's `PATH`.

Start it:

```sh
./gradlew bootRun
```

Open <http://127.0.0.1:7323> and log in as `admin` / `admin`. Then, on **Settings**:

1. Change the password. Ants refuses a non-loopback bind until you do.
2. Press **Detect** to find `claude`, `codex`, `git` and `gh`.
3. Log in to Claude with the command the page shows. Agents run on Ants' own subscription login, never yours and
   never an API key.
4. Add a Vault and a Source map, or run the shipped **Set up Vault** Process to create a Vault.

Then start a Run from one of the shipped Processes, such as **Ship small** or **Ship with me**, and watch the
**Notification list** for anything waiting on you.

Everything Ants owns lives in `~/.ants`. Set `ANTS_HOME` to move it. `settings.yaml` there takes hand edits.

## Tests

```sh
./gradlew test
```

The tests fake `claude`, `codex` and `gh`, and use real git against local bare repos. `BrowserTest` needs Chromium, so
it skips locally where Chromium can't launch and runs in CI.
