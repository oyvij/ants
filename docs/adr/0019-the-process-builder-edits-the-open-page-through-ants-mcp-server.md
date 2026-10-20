# The Process Builder edits the open page through Ants' MCP server

The Process Builder (#156) is a claude session that builds the Process open in the modeler from what the user writes.
Its MCP tools act on the diagram in the open modeler page, not on a copy on the server: Ants relays each tool call to
the page, which applies it through bpmn-js' modeling API and answers with the result. The diagram already lives only
in the page until **Save and deploy**, and going through bpmn-js puts every change on the canvas as it happens, on the
undo stack (one step per tool call) and in the live Checks, exactly like a hand edit. A tool call with no page open
fails, and closing the page ends the session.

- **Transport:** the same `/mcp` endpoint as Protected Commands (ADR 0015). A page opening the Process Builder gets a
  token of its own, which opens only the modeler tools; a Task's token opens only `run`.
- **Locked down by the tool list, not the prompt:** `--strict-mcp-config` with Ants' server alone, only
  `mcp__ants__*` allowed (no Bash, Read, Write or Skills), no setting sources, a scratch folder as working directory,
  and its standing instructions shipped read-only and passed with `--append-system-prompt`. So it can't deploy, start
  a Run, read a Secret or edit a shipped Agent, Skill or Template: no tool does that. To change one it makes a copy.
- **Session:** one live claude process per page (`--input-format stream-json`, `--session-id`), relaunched with
  `--resume` if it dies. A new page starts a new session, which reads the Process's Build log
  (`ANTS_HOME/processes/<key>/build-log.md`), rewritten by the Process Builder at the end of every turn.
- **CLI:** `process-builder: {cli, model}` in `settings.yaml`. Only claude runs it until codex reaching Ants' MCP
  server is probed (#115).

## Considered Options

- **A server-side draft the page syncs:** the tools would work with no page open, but Ants would model BPMN edits in
  Kotlin, merge them with the user's hand edits and build undo a second time. Drafts (#157) autosave the page's
  diagram, the Process Builder's edits included, so they don't need this either.
- **A shipped Agent for it:** an Agent comes with Skills, tools and a Result schema, none of which the Process Builder
  uses, and it would show on the Agents page as one to pick for a Task.
