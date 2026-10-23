# Feeling lucky clones the repos the architect names

ADR 0017 gave the Grills a way to widen the Run's repos; Feeling lucky had none. In a Run of it the scout picked one
repo, and the user continued the architect's session asking it to check out a second. The architect can't run Bash,
so it called `run` with `git clone …`; Ants refused it as no Protected Command and told it to end stuck asking the
user to add one. That advice was wrong: a clone made outside Clone repos gets no work branch, no `ants.workBranch` and
no dead push URL, and a `git clone` Protected Command would let any Agent clone anything.

- **propose-solution names repos as grill does.** Its Result gains `repos[]`, on grill's contract: Source map names
  as the brief gives them, or git URLs the user gave, always the whole list, `[]` with none. Until a repo shows in
  `clones`, the proposal lists it as not checked.
- **Feeling lucky loops through Clone new repos**: propose-solution → Clone new repos with
  `${proposesolution.output.repos}` → **Cloned any?** → back to propose-solution while Clone repos cloned something
  (`${not empty cloneproposal}`), else implement. propose-solution resumes its session (`ants.resume`), so it reads
  the new clone and proposes again on everything it read before.
- **No cap.** grill's `runs.grill` caps Rounds an Agent answers for the user, since each Round costs a session and
  the questions need not run out. Here every pass back clones at least one repo the Run didn't have, and a repo
  already cloned is skipped, so the loop ends once the architect names nothing new: at most once per repo it can
  name. A Run with repos picked clones nothing new, so it never loops.
- **`run` refuses `git clone` with what to do instead**: Ants clones the Run's repos itself, on the work branch, so
  the Agent names the repo where its Skill says to name repos, else ends stuck with the repo in `needs`, and never
  asks for `git clone` as a Protected Command. Only `git clone`: it is the one git command whose result Ants must
  make itself. Other git commands an Agent without Bash may want, such as `git log`, are fine as Protected Commands,
  and `git push` fails on the dead push URL whoever runs it.

This extends ADR 0017 to Feeling lucky.

## Considered Options

- **Give the architect Bash for `git clone`**: the clone would still lack the work branch and dead push URL, and the
  architect would no longer only read.
- **A cap like `runs.grill`**: each pass clones a repo, so the loop is bounded already.
- **Refuse every `git` command with the clone advice**: wrong for `git log` or `git status`, which Ants doesn't run
  for an Agent.
