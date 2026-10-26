# Drafts are kept beside published versions, and only published ones are used

Nothing the user made in Ants was kept until it was finished: the modeler kept nothing until **Save and deploy**, and
the Agent, Skill and Script editors kept work only when Save accepted it, and Save put it to use at once. So a reload,
an expired login or a restart lost a half-built Process, and an Agent changed halfway was the Agent the next Run
launched. Now each Process, Agent, Skill and Script the user owns has a **Draft**, kept on the server as they work,
and a **published** version, the one Ants uses; **Publish** turns the one into the other.

- **One item, up to two versions.** An item has a Draft, a published version, or both, never neither: *Draft only*
  (never published, so nothing can use it), *Published*, and *Published with a Draft*. One marker shows the three
  the same way in every list and every editor's header: "Draft", nothing in a list or "Published" in a header, and
  "Draft · changes not published".
- **One Drafts store**, `ANTS_HOME/drafts/<kind>/<id>.yaml`, outside the engine and every folder Ants reads from when
  it runs something (`agents/`, `skills/`, `scripts/`, the Run workspaces). Each holds the editor's whole page in the
  kind's own format (an Agent's agent file, a Skill's `SKILL.md`, a Script's code and definition, a Process's BPMN),
  the name the page has, the name the page is at while the item is a Draft only, a version and when it was saved.
  Nothing that runs reads it, so a Draft can't reach a Run, a launch, a Skills folder, a Script Task, a Schedule or
  the Pre-Run check. It keeps nothing in memory, so a Draft survives a restart.
- **Autosave.** Each editor posts its whole page to `<its page>/draft` about 2 s after the last change and on leaving
  the page, whatever state it is in, and retries until the server answers. Each save carries the version the page
  last loaded or saved; a save against another version is answered 409 and the page offers to load the newer Draft,
  and stops saving. With no Draft there, any save is kept. The last save wins otherwise; nothing merges.
- **Publish** keeps the page as the Draft first, then runs what Save or Save and deploy ran; if it passes, the Draft
  becomes the published version and is removed, and if not, the Draft stays and the refusal is shown as before.
  Publish refuses a name another item of the kind has, a Draft-only one's included.
- **New and Copy** make a Draft-only item at once, with its fixed id (ADR 0025) and a placeholder name, `new-agent`,
  `new-agent-2`, …, or `<name>-copy`. **Import** publishes at once. A rename lives in the Draft until Publish; a
  Draft-only item's page follows its name while no other item has it, and stays where it was otherwise.
- **Discard Draft** removes the Draft after a confirm and reopens the published version; on a Draft-only item it is
  **Delete**, after a confirm. Deleting a published item deletes its Draft too.
- **Shipped items and those in extra folders** stay read-only with no Draft; Copy makes the user's own Draft.
- This replaces "saving puts it to use" in ADR 0025: Retry runs a Script as it is *published* at that moment, and an
  edit reaches the next launch once it is published.

## Considered Options

- **Drafts in the folders Ants runs from**, as a second file beside the published one: every reader (the launcher, the
  Skills folder, the Script runner, Checks) would have to learn to skip it, and one that didn't would run a Draft.
- **Drafts in the browser** (local storage): lost with the browser, a new device or a cleared profile, and invisible
  to a restart's next login.
- **Deploying half a Process as a version**: Checks refuse an unfinished one, and a deployed one can be started and
  scheduled.
- **Merging two tabs' changes**: one user rarely edits in two tabs; the page asks, and the newer Draft wins.

## Consequences

- A Draft of a Script holds its code in `ANTS_HOME/drafts/scripts/`, which an Agent's `--disallowedTools` must deny
  as it denies `ANTS_HOME/scripts/**`.
- The Process Builder's `create_agent`, `change_agent` and `copy` still publish the Agent at once, as Save did, and
  leave any Draft of it as it was. Its edits to the open diagram reach the Process's Draft through the modeler's
  autosave (ADR 0019).
- A Process's Draft is kept under its key, the Process id New Process makes from the name it is given, and its page
  stays at that key: renaming the Process in the modeler changes its name, not its key, as it did before Drafts. A
  shipped Process has a Draft like the user's own ones, since Publish makes it the user's (the shipped version stays
  deployed until then).
