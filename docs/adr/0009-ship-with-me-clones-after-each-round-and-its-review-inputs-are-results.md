# Ship with me clones after each Round is asked, its review inputs are Results, and the storyteller writes only walkthrough/

The spec gives Ship with me's Templates as shapes: Grill until no questions with **Clone new repos** in the loop, To-spec
and approve, Cut and implement, and Review and push. It doesn't say where in the loop the clone goes, what fix-review's
and pr's `review` inputs are when the implementers are a multi-instance, how the storyteller writes its Story set with
only Bash limited to `git log`, `diff` and `show`, or what a dropped Template's Task-naming properties point at.

- **Grill → Clone new repos → Asked? → Answer the Round → Grill.** The clone runs right after each Round is asked, with
  `${grill.output.repos}`, so a repo named in the last, empty Round is cloned too, and the user answers with the new
  clone already in the Run. Clone repos does nothing for repos already cloned, or for `[]`. The asking Task's id is
  `grill` in every Grill Template, so a cap reads `${runs.grill}`.
- **fix-review's `review` is the Review changes Result**, `${reviewchanges}`: the same comments its Send back message
  carries, as `{approved, comment, comments[]}`, so the fixer can read them as data.
- **pr's `review` is `${implement}`**, the list of the implementers' Results, each holding its `output.review`: JUEL
  can't map a list, and a Ticket's review stays beside the Ticket it was for. The fixers' reviews reach the Report, not
  the pull request.
- **The storyteller's tools are `Read, Grep, Glob, Write(walkthrough/**), Edit(walkthrough/**)`** and Bash limited to
  `git log`, `git diff`, `git show` and `git merge-base`, the last for a range's `base`. It reads `story-set.schema.json`
  from the walkthrough Skill's folder, which is also where Ants checks Story sets against it: one copy.
- **A drop renames the Task an `ants.asker`, `ants.send-back` or `ants.walkthrough` names**, as it renames flows, so a
  second Grill or Review and push in one Process answers, resumes and walks through its own Tasks.

## Considered Options

- **Clone after the user answers** (Answer → Clone → Grill): the griller would see the clone at its next Round, but a
  repo named in the Round that ends the loop would never be cloned.
- **pr reads the fixer's review** (`${fix.output.review}`): `null` when nothing was sent back, and it holds only the
  last fix.

## Consequences

- A Clone new repos that fails (an unknown repo name) is `[failed]` before the user sees that Round.
- The storyteller's Bash rules, like pr's, rely on `cd repos/<name> && git log …` matching `git log *`; they have not
  been probed against a real claude.
