---
name: claude-probe
description: Probe the real claude CLI with Ants' headless flags and record its stream-json events. Use when a ticket depends on how claude behaves (event shapes, schema acceptance, follow-up messages, interrupts, rate limits, login loss) or when a fake-claude session should mirror a real one.
---

# claude-probe

`scripts/claude-probe` runs the real `claude` the way `AgentLauncher` does (the same headless flags, stream-json in
and out, `ANTHROPIC_API_KEY` dropped), prints one line per event, and keeps the full recording.
`scripts/claude-probe --help` lists every option.

## When to probe

Probe before you build on a CLI fact the spec states but nobody has observed. The fake `claude` only plays what a
test scripts, so it passes whatever you assume. Two facts the real CLI has already settled:

- The API refuses `oneOf`, `allOf` or `anyOf` at a `--json-schema` top level (ADR 0003).
- A user message after a `result` continues the same session, and an `interrupt` control request ends the turn with
  `control_response` success, then `result/error_during_execution`, then exit 1.

## Steps

1. Run the smallest prompt that shows the behaviour, on the default haiku. Each run costs a little of the user's
   usage, so one or two runs per question is the budget.
   - A schema: `--schema '<output schema>'`, wrapped in the Result envelope; add `--raw-schema` to send it bare.
   - A conversation: `--then "<message>"`, once per message sent after a `result`.
   - Stop: `--interrupt <seconds>`, with a task long enough to still be running (haiku answers short prompts in
     under 4 s).
   - Any other flag goes after `--`.
2. Read the printed lines; open the recording (path on stderr) only for a field the line cuts off.
3. To make a test play the same session, pass `--fixture <file>`. It writes fake-`claude` session lines: partial
   stream events dropped, the session id as `$SESSION`, a `read` line where the probe sent a message. Trim it to
   the lines the test needs before using it.
4. Record what you learned where the next reader looks: a deviation from the spec (`docs/spec.md`) goes into an ADR and the spec's
   text (`docs/agents/issue-tracker.md`, Resolve a ticket); a fact the code depends on goes in a comment beside
   that code.

Done when the question has a printed answer from the real CLI and the answer is recorded.
