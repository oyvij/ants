# Issue tracker: Local Markdown

This Run's Spec and Tickets live as markdown files in `tickets/` in the Run workspace. The files are the truth: Ants
reads them, shows them to the user, who may tick and edit them, and moves their statuses.

## Conventions

- The Spec is `tickets/spec.md`, starting with a `# <title>` line.
- Tickets are one file each at `tickets/issues/<NN>-<slug>.md`, numbered from `01`, never a single combined tickets
  file. Each starts with a `# <title>` line, then:
  - a `Status:` line, such as `Status: ready-for-agent`;
  - a `Blocked by:` line naming the Tickets that block it by number, such as `Blocked by: 01, 03`, or `Blocked by: none`;
  - `## What to build`, then `## Acceptance criteria` as `- [ ]` checkboxes, ticked (`- [x]`) as each is met;
  - `## Comments` at the bottom. Append comments there; never rewrite earlier ones.
- Statuses: `ready-for-agent` or `ready-for-human` when a Ticket is cut (`ready-for-human` when only the user can do
  it), `in-progress` and `needs-info` while you work on it. Only Ants sets `in-review` and `done`, and only the user
  `wontfix`. The Spec closes when the Run ends.
- The user may edit any of these files while you work. Read a file again before you change it.

## When a skill says "publish to the issue tracker"

Create a new file under `tickets/` (creating the folder if needed), as above.

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. A Ticket number `NN` is the file `tickets/issues/NN-*.md`.
