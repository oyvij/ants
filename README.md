<h1 align="center"><img src="docs/readme-header.svg" alt="Ants" width="420"></h1>

Runs BPMN Processes whose Tasks are AI agents. You draw how a kind of job gets done once, as a Process, then start a
Run with a prompt and walk away. Agents do the steps they can, and the Run waits on you only where the Process says
it must. The vocabulary is `GLOSSARY.md`; the decisions are `docs/adr/`.

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
keeps going with its Runs; `ants restart` then runs the new one, and `ants rollback` goes back to the one before. Only
those two stay in `~/.ants/versions`; the installer deletes older ones.

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

### Alerts on a phone or browser

Ants can send an Alert to your phone or browser for each new entry on the Notification list: turn it on in
**Settings** → **Alerts**, on the device itself. Alerts use standard Web Push, so they need no account anywhere, but
browsers allow them only on a page served over HTTPS (or from `localhost`). Ants doesn't serve HTTPS itself, so put it
behind something that does, for example:

- **Tailscale**: on the machine Ants runs on, `tailscale serve --bg 7323` gives it an HTTPS address
  such as `https://homelab.your-tailnet.ts.net` that every device on your tailnet can open. Ants can stay on
  `127.0.0.1`.
- **A reverse proxy** with a certificate, such as Caddy (`ants.example.com { reverse_proxy 127.0.0.1:7323 }`) or nginx.

On an iPhone or iPad, add Ants to the home screen first and open it from there (iOS 16.4 or later): Safari offers
Alerts only to an installed app. Settings says why when a device can't get Alerts, lists every device that has them on,
and has **Remove** for an old one.

## Developing

Run Ants from source with Java 25 (Gradle and Node are fetched by the build): `./gradlew bootRun`.
`./gradlew tarball` builds the release, `build/dist/ants-<version>-macos-arm64.tar.gz` and its `SHA256SUMS`. Each merge
to main publishes a beta, the next minor version after the latest release with CI's run number (`0.27.0-beta.412`), as
a GitHub prerelease, which Ants offers only with **Follow beta versions** ticked in Settings, and as the image
`ghcr.io/oyvij/ants:<version>` and `:beta`. `gh workflow run
release.yml` makes the newest beta the stable release (`0.27.0`) and its image; `-f version=1.0.0` names another.

## Tests

```sh
./gradlew test
```

The tests fake `claude`, `codex` and `gh`, and use real git against local bare repos. `BrowserTest` needs Chromium, so
it skips locally where Chromium can't launch and runs in CI.

## Icon

The logo is `src/main/resources/static/logo.svg`, an ant seen from the side. The app icons are `docs/icon.svg`,
the logo on a cream tile; render them with the `chrome-headless-shell` that
`./gradlew playwright --args="install chromium"` installs (full Chrome's headless window won't shrink to 192 px), for
N = 192 and 512 (add `--no-sandbox` where its sandbox can't start, such as in a container):

```sh
chrome-headless-shell --hide-scrollbars --window-size=N,N --screenshot=src/main/resources/static/icon-N.png file://$PWD/docs/icon.svg
```
