# A Run scoped to its repos skips the scout and Update the Vault in the launcher

Partly superseded by ADR 0014: the scout and Update the Vault run, and the Vault and Source map follow the Run's
Profile, in a Run with picked repos too. What this says about cloning exactly the picked repos and creating new ones
stands.

Every Run used to go through the scout, which picks repos from the Source map and reads the Vault, and every default
Process ends by updating the Vault. #101 lets the user name a Run's repos, new ones included, so that the Run never
touches the Source map or the Vault. The spec had a Repos field type (Source map names only) but said nothing of what
filling one in does.

- **Any Repos field filled in scopes the Run.** Its entries are Source map names, git URLs, and `owner/name` for a new
  repo (exactly one slash, no scheme, host or `.git`). At start Ants keeps them as the Run variable `ants.scope`, each
  Source map name replaced by its remote, so the Run keeps no other link to the Source map. A Run with no Repos entry
  has no `ants.scope` and behaves as before.
- **Which Vault and Source map a Run uses is decided in one place** (`Scope.kt`): none for a scoped Run, else the
  defaults. The launcher (`--add-dir`, the hand-off line), the `sourceMap` Run variable, Clone repos and the pre-Run
  check all ask it, so groups of Vault and Source map (#90) change only that place.
- **The launcher completes two Skills itself in a scoped Run, without a launch.** A Task whose Skill is `scout` gets
  `{repos: <the scope>, branch: "", brief: "scout/brief.md"}`, and Ants writes `scout/brief.md` (the Task's `prompt`
  and the repos). A Task whose Skill is `update-knowledge` gets `{written: true}`, so Update the Vault ends with no
  Notes staged or written. Each leaves one Ants line in its Transcript saying why it wasn't launched.
- **Clone repos clones exactly the scope** in a scoped Run, whatever its `repos` input names. It creates each new repo
  first, with `gh repo create <owner/name> --private --add-readme` on the user's gh login, only when the Run gets
  there, and clones it from the URL `gh repo view` gives in gh's git protocol (`sshUrl` when `git_protocol` is `ssh`,
  else `url`). A repo of that name already there is taken as one an earlier try of the Task created.
- **The pre-Run check needs no Vault for a scoped Run.** It checks each repo with `git ls-remote` and each new repo's
  name with `gh repo view` (free when it fails), besides `gh auth status`. The Process page checks the stored values,
  so without a Vault and with a Repos field it says **Can't start unless scoped to its Repos** and leaves **Start**
  enabled; Start runs the full check on what was posted.

## Considered Options

- **Gateways in every default Process** (Repos given? skip the scout; skip Update the Vault): visible in the diagram,
  but five Processes and two Templates change, the scout's Result would be `null` for every Task reading
  `${scout.output.brief}`, and a user's own Process built from the Templates would still scout and update the Vault.
- **A separate start-form flag for scoping**: one more field, when filling in the repos already says it.

## Consequences

- The scout and Update the Vault show as done on the Run page of a scoped Run; their Transcripts say they weren't
  launched, and the Run page and the Report say the Run was scoped and to what.
- A user's Process with a Repos field for some other purpose is scoped when it is filled in.
- A Source map repo in a scoped Run is cloned from its remote with no `--reference`, and named after the remote's last
  part, as a git URL is.
- `gh repo view` failing for any reason, a network error too, reads as a free name; `gh repo create` then fails the
  Clone repos Task with gh's message.
