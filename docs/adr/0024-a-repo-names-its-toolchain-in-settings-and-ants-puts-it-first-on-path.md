# A repo names its Toolchain in Settings, and Ants puts it first on PATH

Every Run had one PATH: the one Ants started with, under launchd whatever the plist sets. Claude Agents inherit it
through the environment allow-list, and so do Protected Commands, which run with no shell (ADR 0015), so neither Ants
nor the Agent could change it for them. One Run can touch repos that need different Node majors, and Runs on different
Node majors run at once, so switching Ants' own Node can't fix it. The case that showed it: four repos pin
`packageManager: yarn@4.10.3` and build on Node 22 in CI, two use webpack 4 and build on Node 16 and 12, none has an
`.nvmrc`, `.node-version`, `engines.node` or `volta`, and with v16 first `yarn install` runs Yarn 1 and refuses.

- **A Toolchain is named in `settings.yaml`**: `toolchains: name: {bin: [folders], repos: [repo names]}`. The folders
  are the user's own installs, whichever manager made them (nvm, fnm, volta, asdf, mise, Homebrew); Ants installs
  nothing and resolves no version. A repo is named as the Source map names it, which is also its clone's folder under
  `repos/`, and takes the first Toolchain that lists it. A repo no Toolchain lists keeps Ants' PATH, as before.
  Hand-edited only, like `process-builder`; the settings page doesn't show it, and its Save keeps it.
- **A Protected Command in the repo** (its folder in `repos/<name>/`, or an instance's `instances/<key>/repos/<name>/`)
  runs with the Toolchain's folders before the allow-list's PATH. Ants looks a bare program name up in those folders
  first itself, since Java finds the program on Ants' own PATH, not the child's: without that, `yarn` would still be
  v16's Yarn 1, merely running on Node 22. A Toolchain folder that is missing is refused, and the Agent ends stuck.
- **The allow-list is still never widened.** No variable of Ants' own that the allow-list drops reaches the process;
  PATH gets folders from Settings put before it, as a Protected Command gets Secrets from Settings.
- **The Agent's own Bash** keeps Ants' PATH, since one session works across repos. The hand-off line ends, when the
  Task's folder has clones whose names a Toolchain lists, with ` Prefix each Bash command in these repos with its
  PATH: `PATH=<bin>:$PATH` in repos/<name>, …; the run tool puts it there itself.` The last clause keeps the Agent
  from putting `PATH=` into a `run` command, where it would be no Protected Command.

## Considered Options

- **Reading the repo's `.nvmrc`, `.node-version`, `engines.node` or `volta.node`**: the repos that failed have none,
  so it alone fixes nothing here, and it needs a version (or, for `engines`, a range) resolved to an install, which is
  each manager's own folder layout: a version manager. It is the upgrade path if Toolchains get tedious: a repo file
  naming a major, mapped to a Toolchain.
- **Guessing from the CI config** (`image: az-node:22` in `bitbucket-pipelines.yml`): one CI provider's file and one
  registry's naming, read at run time; magic, and wrong as soon as an image is named differently.
- **`packageManager` and Corepack**: it names the Yarn, not the Node, and Corepack under Node 16 can't run Yarn 4.
- **A Node per Profile, or one default**: a Profile spans repos on different majors; a default is Ants' PATH already.
- **Explicit folders per repo, with no named Toolchains**: the same code, but four repos on Node 22 repeat one path
  four times, and an nvm upgrade that renames `v22.23.2` breaks four rows instead of one.
- **Setting PATH on the Agent's own launch**: one session works in several repos, so there is no one PATH to set.
- **A wrapper or shim folder Ants writes per repo**: runtime magic, and it still needs the Agent to find it.

## Consequences

- The user adds a Toolchain row per Node they use and lists each repo once; a repo added to the Source map builds on
  Ants' PATH until it is listed.
- Whether the real model prefixes every Bash command as told isn't tested; the user judges it on real Runs. An Agent
  can always run its builds through `run` instead, if `yarn` is a Protected Command.
- A Task launched before Clone repos, such as the scout, gets no Toolchain sentence, and a resumed session keeps its
  first prompt's; a session that clones a repo itself learns nothing about its Toolchain.
- The pre-Run check doesn't look at Toolchain folders; a missing one shows only when a Protected Command needs it.
- `JAVA_HOME` and other variables a toolchain may want aren't set; a Toolchain is PATH folders only.
