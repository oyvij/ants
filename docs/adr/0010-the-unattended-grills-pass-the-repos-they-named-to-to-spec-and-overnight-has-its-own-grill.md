# The unattended Grills pass the repos they named to to-spec, and Ship overnight has its own Grill

Partly superseded by ADR 0017: Grill (by proxy) and Grill (as proposed) clone the repos a Round names; only Grill
(overnight) never clones.

The spec says the unattended Processes never widen the Run's repos and that to-spec lists the uncloned repos in the
Spec instead, but to-spec's inputs are only prompt, brief, rounds and clones, and the griller names a repo in one
Round's `repos` only. It ships three Grill Templates, one per answerer, the two unattended ones carrying a cap that
hands Round 10 to the user, yet Ship overnight's cap ends the loop. And it gives the answerer no tool list.

- **The griller's `repos` is the whole list.** It names every repo the work needs beyond the clones it started with,
  again in each later Round, the last one included, so `${grill.output.repos}` after the loop holds them all.
- **to-spec reads `repos`, `${grill.output.repos}`.** A repo in `repos` that isn't in `clones` was not cloned, and the
  Spec lists it under Further Notes as one the Run doesn't change. In Ship with me every one of them was cloned.
- **Four Grill Templates:** Grill (you answer), Grill (by proxy) and Grill (as proposed), whose Round 10 goes to the
  user, and Grill (overnight), the answerer every Round, its cap checked after each answer, so the loop ends once
  Round 9 is answered and no Round 10 is asked, which the griller would ask only to have it thrown away. None but the first
  clones; the user answering Round 10 in the by-proxy and as-proposed ones doesn't clone either.
- **Ship overnight's to-spec is a bare Task**, `tospec`, not To-spec and approve with its Approve removed, so Cut and
  implement and Review and push read `${tospec.output.spec}` as in the others.
- **The answerer's tools are `Read, Grep, Glob`.** It only reads: the Answer History and the Vault, each at the path
  its hand-off line names and on `--add-dir`. Every claude Task with the Answer History on gets its `--add-dir`.

## Considered Options

- **to-spec reads the repos from the Run log**: the Rounds are logged, but not their `repos`, and a model reading a
  log is a guess where a list is data.
- **Ship overnight uses Grill (by proxy)**: its Round 10 waits on the user, so the Process would not be unattended up
  to Review changes.

## Consequences

- A griller that drops a repo from a later Round's `repos` drops it from the Spec too.
- The Answer History's `--add-dir` is read and write for claude, as the Vault's is; the answerer has no tool that
  writes. codex reads it without one, since its sandbox reads everywhere.
