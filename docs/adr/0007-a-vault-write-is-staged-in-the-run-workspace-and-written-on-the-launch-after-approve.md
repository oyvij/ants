# A Vault write is staged in the Run workspace and written on the launch after Approve

The spec says every Vault write is proposed first and written only after the user approves it, that Update the Vault
is update-knowledge → **Written?** → if no, Approve the Notes and loop back, and that Set up Vault is init-vault → its
Round → Approve the rule Notes. It doesn't say how the knowledge Task learns that the user approved, where the Notes
wait meanwhile, or how the Skills reach `vault-format.md`.

- **The approval is an input.** The knowledge Task (resume on) reads the Approve's Result as an input, `approval:
  ${approvenotes}`: `null` before the first proposal, as every Task's Result starts. Approve and Send back both loop
  back to it. After Send back it resumes with the comment alone and proposes again; after Approve the flow resumes it
  with its inputs, now `approval.approved` true, and only then does it copy the staged Notes into the Vault and answer
  `written: true`. Both Update the Vault and Set up Vault are drawn this way, from building blocks a user can draw.
- **The Notes wait in the Run workspace.** Varde stages in a temporary folder and asks in the same conversation. A Task
  stages in `vault/staging/` and writes its proposal to `vault/proposal.md`, which the Approve shows; both outlast the
  launch that wrote them. Staged Notes pass the leak check, so the Vault's path still never reaches the Run workspace.
- **init-vault's output is `{questions, proposal, written}`**: its Round while decisions are open, then the proposal
  the Approve shows, then `written: true`. Set up Vault's one gateway asks the Round, ends when written, else approves.
- **`vault-format.md`, `leak-check.sh` and `topic.base` live in search-knowledge's folder**, and update-knowledge and
  init-vault use search-knowledge (`ants-uses`), so the folder is an `--add-dir` of every Task that needs them. Only
  "Proposals" and "Writing to the Vault" differ from Varde's file, for the two points above.
- **Shipped Processes** are `shipped/processes/*.bpmn`, deployed through the Checks on every start, each as its own
  deployment `shipped/<file>` with duplicate filtering, so a start deploys one again only when Ants ships a change.

## Considered Options

- **Ants writes the Notes on Approve**, copying `vault/staging/` itself: the Agent would never need write access to
  the Vault, but Approve would become a Vault-only User Task, and the leak check and the move removals would be Ants'
  code, not the Skill's.
- **Deny Write and Edit on the Vault until approval** (`--disallowedTools`): Ants would have to read the approval out
  of an input it otherwise never interprets, and Bash would still reach the Vault. Taken as well, after review: the
  Skill's prose alone narrowed story 121, so Ants backs it up where it cheaply can.

## Consequences

- Ants holds back what it can: a Task with an `approval` input, until the launch where `approval.approved` is true,
  gets `--disallowedTools Write(/<vault>/**),Edit(/<vault>/**)` (claude's `//` absolute-path rules) on claude, and
  no `--add-dir` for the Vault on codex, whose sandbox still lets it read. Bash is still a hole: a knowledge Agent
  with Bash can write the Vault before approval, so the Skills and their Approve hold that back. The path rules are
  unprobed against a real claude, and a Vault reached through a symlink may slip them.
- A Template using update-knowledge must keep its `approval` input naming the Approve that follows it.
- A user's Save of a shipped Process deploys under its key and survives a restart; a changed shipped file still
  deploys a newer version over it.
