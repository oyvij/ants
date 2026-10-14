---
name: claude-probe
description: Probe the real claude CLI with Ants' headless flags and record its stream-json events. Use when a ticket depends on how claude behaves (event shapes, schema acceptance, tool permission rules such as `--allowedTools`/`--disallowedTools` path patterns and `--add-dir`, follow-up messages, interrupts, rate limits, login loss) or when a fake-claude session should mirror a real one.
---

# claude-probe

`scripts/claude-probe` runs the real `claude` the way `AgentLauncher` does (the same headless flags, stream-json in
and out, `ANTHROPIC_API_KEY` dropped), prints one line per event, and keeps the full recording.
`scripts/claude-probe --help` lists every option.

## When to probe

Probe before you build on a CLI fact the spec states but nobody has observed. The fake `claude` only plays what a
test scripts, so it passes whatever you assume. Facts the real CLI has already settled:

- The API refuses `oneOf`, `allOf` or `anyOf` at a `--json-schema` top level (ADR 0003).
- A user message after a `result` continues the same session, and an `interrupt` control request ends the turn with
  `control_response` success, then `result/error_during_execution`, then exit 1.
- A base64 `image` block before the text block of a stream-json user message reaches the model, and claude
  replays the message with the image data in it.
- Under `--permission-mode dontAsk`, claude 2.1.296 checks a Write against Edit rules: `--allowedTools 'Write(scout/**)'`
  denies the Write, and `'Edit(scout/**)'` allows it (so `AgentLauncher` sends a Write limit as both).
- Under `dontAsk`, claude 2.1.299 with `--allowedTools 'Bash(git log *)'` refuses `git -C <dir> log`,
  `git --git-dir=… log`, `git -c … log`, `cd <dir> && git log` and `git log; touch x`, but allows a lone
  `cd <dir>` inside the cwd (not `cd /tmp`), after which the shell stays there and a plain `git log` runs. It also
  allows `git diff --output=<any path>`, which writes the file; `--disallowedTools 'Bash(git *--output*)'` refuses it.
- claude 2.1.296 under `--strict-mcp-config` uses an `--mcp-config` HTTP server on `127.0.0.1` (`"type": "http"`,
  with an `Authorization` header it sends on every request), inside nono with network allowed. `--tools Read`
  doesn't hide the MCP tool; `--allowedTools mcp__<server>__<tool>` lets it run under `dontAsk`.
- With the `pr` Agent's tools under `dontAsk`, claude 2.1.299 runs `gh pr view <branch> --repo o/n` and
  `gh pr create --repo o/n …` (a multi-line `--body` too) from the workspace root, which isn't a git repo, so gh
  needs `--repo` there. A lone `cd repos/<x>` is allowed, and `cd ../..` back to the root, but not `cd ..` above
  it, nor a `cd` into a symlink that leaves it. `a && b` runs when each part is allowed on its own. A user's shell
  `cd` hook that fails (fnm's `--use-on-cd` on a clone with `package.json`) makes the `cd` fail and not stick.

## Steps

1. Run the smallest prompt that shows the behaviour, on the default haiku. Each run costs a little of the user's
   usage, so one or two runs per question is the budget.
   - A schema: `--schema '<output schema>'`, wrapped in the Result envelope; add `--raw-schema` to send it bare.
   - A conversation: `--then "<message>"`, once per message sent after a `result`.
   - Stop: `--interrupt <seconds>`, with a task long enough to still be running (haiku answers short prompts in
     under 4 s).
   - An image: `--image <file>` puts it beside the first prompt as a base64 image block.
   - Any other flag goes after `--`.
2. Read the printed lines; open the recording (path on stderr) only for a field the line cuts off.
3. To make a test play the same session, pass `--fixture <file>`. It writes fake-`claude` session lines: partial
   stream events dropped, the session id as `$SESSION`, a `read` line where the probe sent a message. Trim it to
   the lines the test needs before using it.
4. Record what you learned where the next reader looks: a deviation from the spec (`docs/spec.md`) goes into an ADR and the spec's
   text (`docs/agents/issue-tracker.md`, Resolve a ticket); a fact the code depends on goes in a comment beside
   that code.

Done when the question has a printed answer from the real CLI and the answer is recorded.
