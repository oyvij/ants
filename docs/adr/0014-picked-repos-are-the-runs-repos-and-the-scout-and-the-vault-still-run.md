# Picked repos are the Run's repos, and the scout and the Vault still run

ADR 0011 made any Repos entry on the start form scope the Run: no Profile, so no Vault and no Source map, and the
launcher completed the scout and Update the Vault without launching them. That tied two separate questions together:
which repos the Run works in, and whether it may read and write the user's knowledge. A user who knew the repo lost the
brief the Vault would have given the implementers, and the Notes the Run would have left behind.

This supersedes the scout and Vault parts of ADR 0011; what it says about the repos themselves stands.

- **The scout always runs** in a Process that has one. Picking repos never skips it.
- **Picked repos are the Run's repos.** Ants keeps them as `ants.scope`, each Source map name given as its remote,
  as before. Clone repos clones exactly them, whatever its `repos` input names, and creates each new repo (`owner/name`)
  on GitHub when it gets there (ADR 0011). The scout gets them as its `repos` input, `${repos}` from the start form,
  empty when none was picked. It takes them as given: it adds, drops and questions none, answers with them unchanged,
  and writes `scout/brief.md` from the prompt and the Vault, saying what needs doing in each.
- **No repos picked: the scout picks them** (ADR 0013).
- **The Vault and the Source map follow the Run's Profile and the Tasks' Knowledge toggles, never the Repos field.** A
  Run with picked repos keeps `ants.profile` like any Run, so `sourceMap` holds its Profile's repos, its Tasks get the
  Vault when their toggle is on and the Profile has one, and Update the Vault runs. `RunSource` in `Scope.kt` is one
  data class, a Profile name and the picked repos, and stays the one place that decides.
- **A Profile without a Vault** keeps story 59's rule: a Run with picked repos starts without one, and the pre-Run check
  asks for **Configure a Vault** only when no repos are picked, since the scout then picks from the Vault. With no Vault
  the hand-off lines name none: the scout writes its brief from the prompt alone, and update-knowledge answers `written:
  true` with nothing written. Both are a sentence in their Skills, not a rule in the launcher.
- **Runs started before this change**, with `ants.scope` and no `ants.profile`, read as Runs with the default Profile:
  they load, clone exactly their scope and finish, but a Task still ahead of them, Update the Vault included, now gets
  the default Profile's Vault when its toggle is on.

## Considered Options

- **Keep ADR 0011's launcher stand-ins**, completing the scout and Update the Vault without a launch: the Run loses the
  Vault's brief and Notes only because the user knew the repo, and a Skill-specific rule stays in the launcher.
- **A separate start-form flag for "no Vault"**: the Profile already says which Vault a Run uses, and a Profile with no
  Vault is that flag.
- **Old Runs keep no Profile** (a scope with no `ants.profile` reads as no Vault): a second reading of `ants.scope` kept
  for the few Runs live across the upgrade, and a Run started with no Profiles configured can't be told apart from one.

## Consequences

- Every Run with a scout costs a scout launch, picked repos or not, and every default Process ends with Update the
  Vault.
- The launcher has no Skill-specific completions left.
- #107 (repo kinds, Agents ask for repos, the trust policy with "Use the Vault" as its own row) builds on this: whether
  a Task reads the Vault is already apart from which repos the Run works in.
