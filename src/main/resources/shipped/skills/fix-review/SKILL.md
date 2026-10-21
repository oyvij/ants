---
name: fix-review
description: Fix what the user's review of the Run's changes asks for, test-first in the clones, review the fixes, and comment on each Ticket they touch.
metadata:
  ants-inputs: review spec stories
  ants-uses: tdd code-review
---

# Fix the Review

The user reviewed the Run's changes and sent them back. Fix what they asked for, in the clones,
and leave it committed.

The line that handed you this Skill names your workspace and the tdd and code-review Skills. The
clones are in `repos/<name>/`, each on the work branch. You are started, or resumed, with the
user's comment followed by one line per comment they left:
`- TYPE file:from-to (side): body`, with `, <story> step <n>` before the colon for a comment on a
step of the Story set. Your inputs; paths are relative to the Run workspace:

- `review`: the same review, `{approved, comment, comments[]}`, each comment
  `{story, step, repo, file, side, from, to, type, body}`.
- `spec`: the Run's Spec, `tickets/spec.md`; its Tickets are in `tickets/issues/`.
- `stories`: the Story set the user reviewed, `walkthrough/stories.json`.

## Rules

- Commit on the branch each clone is on. Never push, and never switch, rebase or reset a branch.
- Follow each clone's own guidance: `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`, coding standards
  and ADRs, where they exist.

## 1. Read the review

Note each clone's `HEAD` commit: the fixed point your review starts from. Take each comment by
its type: an `ISSUE` must be fixed; a `SUGGESTION` is fixed unless it is wrong, and then you say
why; a `NOTE` or `COMMENT` needs a change only if it asks for one. Read the lines and the story
step each comment points at.

## 2. Fix

Fix by following the tdd Skill: a failing test for each behaviour the review says is wrong, then
the fix. Commit in small steps, in the repo's commit-message style, and run each touched repo's
tests as its `CLAUDE.md`, `AGENTS.md` or `CONTRIBUTING.md` says; where they say nothing, run its
whole suite once at the end.

## 3. Review

Review your fixes by following the code-review Skill, from the fixed point, against the review
and the Spec. Fix what it finds as new commits.

## 4. Comment on the Tickets

For each Ticket whose code your fixes touched, append a comment under its `## Comments`: the
review comments it answers, what you changed and the commits. Leave its `Status:` line alone.

## 5. Answer

Answer `done` with `review`: a short markdown summary of the review of your fixes, under
`### Standards` and `### Spec`, and each review comment you didn't act on, with why.
