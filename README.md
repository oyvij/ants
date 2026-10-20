# Ants

Runs BPMN Processes whose Tasks are AI agents. You draw how a kind of job gets done once, as a Process, then start a
Run with a prompt and walk away. Agents do the steps they can, and the Run waits on you only where the Process says
it must. The spec is `docs/spec.md`; the vocabulary is `GLOSSARY.md`.

## Getting started

Ants runs on macOS on Apple silicon. Install it, with its own Java runtime, and start it:

```sh
curl -fsSL https://github.com/oyvij/ants/releases/latest/download/install.sh | bash
```

The installer checks the download against the release's checksums, puts it in `~/.ants/versions/<version>` and links
`~/.local/bin/ants` (add `~/.local/bin` to your `PATH`). Ants runs as a launchd agent, so it keeps going after you
close the terminal and comes back after you log in. `ants start` starts it again after an `ants stop`; the launcher
also has `restart`, `status`, `logs`, `run` (in the foreground), `rollback` and `password`.

When a newer version is out, the nav offers **Update**. It downloads the new version beside the one running, which
keeps going with its Runs; `ants restart` then runs the new one, and `ants rollback` goes back to the one before.

You also need `claude` (and `codex`, if you use it), `git` and `gh` on your login shell's `PATH`.

Open <http://127.0.0.1:7323> and log in as `admin` / `admin`. Then, on **Settings**:

1. Change the password. Ants refuses a non-loopback bind until you do.
2. Press **Detect** to find `claude`, `codex`, `git` and `gh`.
3. Log in to Claude with the command the page shows. Agents run on Ants' own subscription login, never yours and
   never an API key.
4. Ants ships the Profile **Anthill**: the Vault `~/.ants/knowledge-vault` and the Source map `~/.ants/source-map.md`.
   Press **Edit** on the Source map to list your repos, and **Set up Vault** on the Vault to write its rules. Add
   more Vaults, Source maps and Profiles beside it.

Then start a Run from one of the shipped Processes, such as **Build and ship**, picking its Path (Human in the loop, Feeling lucky, …), and watch the
**Notification list** for anything waiting on you.

Everything Ants owns lives in `~/.ants`. Set `ANTS_HOME` to move it. `settings.yaml` there takes hand edits.

## Using Ants from a phone

Every page fits a phone screen, so you can follow your Runs and answer what's waiting on you away from your desk.
This needs no change to your homelab, and no new TLS or auth: the phone reaches the same Ants, behind the same login.

1. Use a phone that can reach the machine Ants runs on, for example one on the same network.
2. Open the same URL you open on the desktop, and log in as you do there.
3. Optionally, add Ants to the home screen (**Share** → **Add to Home Screen** in Safari, ⋮ → **Add to Home
   screen** in Chrome). Ants' web manifest makes it open as its own app, full screen with the Ants icon.

Out of the box Ants listens only on `127.0.0.1`, which no other device can reach. To reach it from the network,
change the password, then `ants stop` and start it in the foreground on every address with
`SERVER_ADDRESS=0.0.0.0 ants run`.

## Developing

Run Ants from source with Java 25 (Gradle and Node are fetched by the build): `./gradlew bootRun`.
`./gradlew tarball` builds the release, `build/dist/ants-<version>-macos-arm64.tar.gz` and its `SHA256SUMS`. CI
releases it when `build.gradle.kts`' version becomes a new major or minor (`x.y.0`).

## Tests

```sh
./gradlew test
```

The tests fake `claude`, `codex` and `gh`, and use real git against local bare repos. `BrowserTest` needs Chromium, so
it skips locally where Chromium can't launch and runs in CI.
