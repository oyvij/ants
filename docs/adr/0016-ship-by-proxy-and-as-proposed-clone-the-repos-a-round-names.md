# Ship by proxy and Ship as proposed clone the repos a Round names

ADR 0010 kept the unattended Grills from widening the Run's repos: a repo the griller named was listed in the Spec
as one the Run doesn't change. In a Run of Ship by proxy the answerer agreed to bring in the backend that computes
the value the fix shows, it was never cloned, and to-spec wrote that it "should need no change". The user approved a
client-only Spec, and the fix failed in staging on exactly that backend.

- **Grill (by proxy) and Grill (as proposed) have Clone new repos in the loop**, as Grill (you answer) has: grill →
  Clone new repos with `${grill.output.repos}` → **Asked?**. The user still sees every repo the Run will change at
  Approve the Spec, before anything is implemented, and at Push.
- **Ship overnight still never widens**: no one looks until Review changes, so its Grill keeps no clone.
- **No one writes that an unread repo needs no change.** The scout lists a repo it didn't pick in **Open** with what
  would show it is needed; the griller names a repo the work may need when nothing it read rules it out; to-spec lists
  an uncloned one as not checked, with what to check there.

This supersedes ADR 0010's "None but the first clones"; the rest of it stands.

## Considered Options

- **to-spec stops at Approve the Spec with the uncloned repos**: the user would clone by hand or send back, where
  a clone step in the loop lets the griller read the repo and ask about it in its next Round.
