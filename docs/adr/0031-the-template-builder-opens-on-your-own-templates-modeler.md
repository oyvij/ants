# The Template Builder opens on your own Template's modeler

ADR 0019 put the Process Builder on a Process's modeler only, never a Template's: a Template was changed in the
Processes it is copied into. So a reusable Template could only be drawn by hand. The **Template Builder** now opens on
the modeler of the user's own Template, never on a shipped one, which still offers **Copy**. This supersedes ADR
0019's "Only on a Process's modeler, never a Template's"; the rest of it stands, for both builders.

- **A helper of its own,** beside the Process Builder and the script helper: its own title, its own shipped read-only
  instructions (`template-builder.md`, which say a Template is a piece dropped into Processes and changed there) and
  its own `template-builder: {cli, model}` in `settings.yaml`, so its model is picked apart from the Process
  Builder's. Only claude runs it until #115.
- **The Process Builder's tools and relay:** the same MCP tools, through a page token of its own, acting on the open
  Template page through bpmn-js, so each edit is one undo step, locked down the same way, with the same session,
  reload and idle end.
- **Unsaved until Save:** its edits stay on the canvas until the user clicks **Save**, which is unchanged. Templates
  get no Drafts.
- **Checks are advice:** it reads them with `checks` and fixes what it can; Save never refuses a Template for them.

## Considered Options

- **Open the Process Builder itself on a Template:** one helper, but one set of instructions written for a whole
  Process, and one model setting for both.
- **A shipped Template it may change:** shipped items are read-only everywhere; the user copies one first.
