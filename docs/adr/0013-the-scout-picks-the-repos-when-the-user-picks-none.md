# The scout picks the repos when the user picks none

The Scout rules let `repos` hold only the repos a prompt names, and gave `repos: []` for a prompt that names none. The
Vault served only the brief. So a Run started without picking repos, the way the default Processes are meant to be
used, cloned nothing, and its implementer answered stuck (Run si-24725: a Danish bug report about Aftaler, whose repo
the Vault names, but the prompt doesn't).

- **The user's choice comes first.** Repos picked on the start form are the Run's, and the scout takes them as given
  (ADR 0014).
- **Else the scout picks.** It takes the repos the prompt names, else the Source map names the Vault ties the work to,
  and says in the brief which words or Note tie each one.
- **Only the Source map.** `repos` never holds a repo outside the Source map, nor a new one.
- **Stuck when it can't pick**, as for a named repo it can't place: work the Vault ties to no repo, or to several with
  nothing to choose between them, with the closest Source map names in `needs`.

## Considered Options

- **Keep `repos: []`** and let the implementer answer stuck: the user learns it after the scout, the clone and the
  proposal, and has to start again with the repos picked.
- **The scout proposes repos and the user confirms them in a Round**: a stop in every Run, including the ones the Vault
  answers plainly.

## Consequences

- A Run can be wrong about its repos where it used to be empty. The brief names each repo's reason, and the Run page and
  Push show the repos before anything leaves the machine.
- #107 (repo kinds, Agents ask for repos, the trust policy) widens this to any Agent and any repo kind; this decision is
  its existing-repo, auto case, for the scout alone.
