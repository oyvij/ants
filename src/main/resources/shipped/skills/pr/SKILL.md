---
name: pr
description: Open a pull request on GitHub for each repo the Run pushed, with a body written from the commits, the Spec or proposal and the review.
metadata:
  ants-inputs: push spec proposal review fixes
---

<!-- The body's shape is adapted from Matt Pocock's pr Skill (https://github.com/mattpocock/skills).
Copyright (c) 2026 Matt Pocock. MIT License: see LICENSE in the tdd Skill's folder. -->

# Open Pull Requests

Open one pull request per pushed repo, so the user's reviewers get the context of the Run.

Your inputs; paths are relative to the Run workspace:

- `push`: the Push Result, one entry per repo:
  `{repo, remote, branch, base, isNew, commits: [{sha, subject}], pushed, error, compareUrl}`.
  The clone is `repos/<repo>/`.
- `spec` or `proposal`: what the Run built, `tickets/spec.md` or `proposal/proposal.md`.
- `review`: the implementers' review of their commits, if any: a summary, or their Results, each
  with the summary as `output.review`.
- `fixes`: the review of the fixes made after the user sent the changes back, if any.

You run `gh` on the user's own login, and `git log` and `git diff` in the clones. You change
nothing in the repos and push nothing. Each command is its own Bash call. Run `gh` from wherever
you are, always with `--repo <owner>/<name>`. Run git from inside a clone: first `cd repos/<repo>`,
then a plain `git log …` (the shell stays in the clone); `cd ../..` takes you back to the
workspace root. Anything else is refused: `git -C …`, `git --git-dir=…`, `git -c …`, `--output`,
and `cd … && git …` or any other chained command.

## 1. Pick the repos

Only entries with `pushed: true`. A repo whose `remote` isn't on `github.com` gets no pull
request from you: open the others first, then answer `stuck`, naming each such repo with its
`compareUrl` (or its remote and branches when there is none), so the user can open it by hand and
Continue. When Continued, answer `done` with the pull requests you opened.

If `gh pr view <branch> --repo <owner>/<name>` already shows an open pull request for the branch,
keep it: don't open a second one.

## 2. Write the body

Read the spec or proposal, the review and the fixes, and `git log` and `git diff` from `<base>`, inside the clone.
Write the body briefly, in the repo's own terms:

```markdown
## Summary

<what changed and why, in a few lines, or the smallest sketch that makes it clear>

## Evidence

<the tests that show it works>

## Review

<what the review found and what was fixed, after the user's review too; what was left>

## Merge Danger

**Door:** <one-way or two-way>

**Blast Radius:** <one word>
```

The title is one short line saying what the change does.

## 3. Open it

`gh pr create --repo <owner>/<name> --head <branch> --base <base> --title "…" --body "…"`, with
`<owner>/<name>` read from the remote. If `gh` isn't logged in or is refused, answer `stuck` with
its message.

Answer `done` with `pullRequests: [{repo, url}]`.
