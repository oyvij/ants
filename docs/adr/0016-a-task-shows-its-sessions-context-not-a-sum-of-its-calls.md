# A Task shows its session's context, not a sum of its calls

Ants showed a Task's tokens as the sum of `modelUsage` over its launches, plus its So far rewrites, and the Run page and
the Report added up their Tasks. `modelUsage` counts every call the session made, and each call re-reads the whole
conversation from the cache, so an implement Task whose context ended at 62k of a 1M window read "1.5M tokens", more
than any window holds. The user reads the figure as Claude Code's status line: how full the session is.

- **A Task shows its session's context:** what the latest main-thread call read, `input_tokens` +
  `cache_read_input_tokens` + `cache_creation_input_tokens` of the latest `assistant` message's `usage`, as Claude Code
  counts it. A `result`'s `modelUsage` gives the window (`contextWindow`), so the figure reads `62k tokens (6.2%)`.
- **One session, one context.** A Task shows its latest launch's: a resumed session carries on the same context and a
  fresh one starts over, so launches never add up. Nor do Tasks: the Run page and the Report list each Task's context
  with no total, and a multi-instance row shows its largest instance's.
- **Left out:** a Subagent's calls, which read a context of their own, and the So far rewrites, haiku's own sessions.
- **codex** prints no context per call (`turn.completed` usage is the session's total so far), so a codex launch shows
  that total until codex gives better.

## Considered Options

- **Keep the sum and label it "processed"**: true, but it answers how much was read, not how full the session is, and
  most of it is cheap cache re-reads.
- **Show both**: one figure per Task is what the pages have room for; the sum is in each Transcript's `modelUsage` for
  anyone who wants it.

## Consequences

- Nothing in Ants shows what a Task cost any more, the So far rewrites included.
- The percentage shows once the session's first `result` has come; a first launch still running shows the tokens alone.
