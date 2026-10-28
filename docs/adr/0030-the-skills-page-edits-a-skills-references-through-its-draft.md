# The Skills page edits a Skill's references, through its Draft

A Claude Code skill keeps its detail in Markdown files beside its `SKILL.md`, which the Agent reads only when
`SKILL.md` points it there, and each allowed Skill's folder already reaches the Agent as an `--add-dir` (ADR 0024,
probe 5). But the Skills page edited only `SKILL.md`, so a reference could be written only on disk, behind Ants' back
and outside Drafts and Publish. Now the page edits a Skill's **references** as well.

- **The page edits `SKILL.md` and the Markdown files beside it, nothing else.** A reference is a `.md` file in the
  Skill's folder or a subfolder, other than its `SKILL.md` and not inside another Skill's folder nested in it, named by
  its path relative to the Skill's folder. `LICENSE`, scripts, schemas and `ants.yaml` are never changed by the page.
  This replaces ADR 0024's "the Skills page edits only `SKILL.md`".
- **References go through the Draft, as `SKILL.md` does (ADR 0026).** A reference's Save keeps it in the Skill's
  Draft, making one from the published `SKILL.md` when there is none. Once a reference changes, the Draft holds the
  full set of the Skill's references, in the Drafts store beside the Draft (`drafts/skills/<id>/`), where Copy
  already keeps a Draft-only Skill's files. The page and the reference show the Draft's version, and the card marks a
  reference the Draft has changed.
- **Publish writes the Draft's references into the Skill's folder before `SKILL.md`**, each one atomically, so a
  launch never sees half a Skill, and leaves the folder's other files as they are. **Discard Draft** drops the
  references with the rest of the Draft. **Copy** takes the Draft's references.
- **Read-only Skills stay read-only.** A shipped Skill, or one in an extra Skill folder, shows its references with no
  Save, and refuses one.
- **Run time is unchanged.** A launch reads the published Skill folder; a Draft's references never reach it.

## Considered Options

- **Editing references in the Skill's folder at once**, outside the Draft: a half-written reference would reach the
  next launch, while the `SKILL.md` that points to it waits for Publish.
- **Keeping only the changed references in the Draft**: Publish and the card would have to merge two folders on every
  read, and a deleted reference would need a marker of its own.
