# A refused tool call waits as a Permission request

Under `dontAsk`, claude refuses a tool call no allow rule covers and tells the model, which then looks for another
way, or ends stuck, and the user finds the refusal only in the Transcript.

- **The Task waits.** claude 2.1.311 reports each refusal as a `system/permission_denied` event (`tool_name`,
  `tool_use_id`, `decision_reason_type`): `mode` when no allow rule covers the call, `rule` when a deny rule refused
  it. The event holds no input, so Ants takes the command or path from the `tool_use` with that id. On the first
  one, Ants interrupts the session and puts the Task on the Notification list as `[permission]`, keeping its session.
- **The dialog proposes a Limit** the user can edit: `Bash(<first word> *)`, `Write(<folder>/**)` or `Edit(…)`, or the
  bare tool. Always allow adds it to the Agent's tools, or to Your tools on a shipped Agent (ADR 0028); Allow for this
  Task keeps it with the Task (`ants.granted`), for the rest of its launches; Don't allow adds nothing. Each resumes
  the same session (`--resume`), its launch built again, telling the Agent what the user chose.
- **No allow where none can help.** A call one of Ants' deny rules refuses (a Protected Command, `secrets.yaml`,
  `settings.yaml`, the Scripts, `git … --output`, a Subagent type outside an `Agent(…)` Limit) offers Don't allow
  only, and says why.
- **An Agent with a Draft**: Always allow changes the published Agent, which is what Runs use (ADR 0026), and keeps
  the Draft as it is. Publishing that Draft later drops the Limit, as it drops any change made beside it.

## Considered Options

- **Send the user to the Agent's editor**: the Task would wait on a second page, and a Publish of the Draft would
  publish every other change in it too.
- **Allow once**: claude's rules are per session, so a rule given for one call holds for the rest of it anyway.
