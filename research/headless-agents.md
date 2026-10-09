# How do Agents run headless on a subscription?

Research for [#3](https://github.com/oyvij/ants/issues/3). Researched 2026-10-09 against Claude Code
2.1.295 (installed locally) and Codex CLI `rust-v0.162.0` (latest release, source read on GitHub;
not installed here). Claims cite the page or source that owns them. "Probed" means one of two
trivial `claude -p` runs made for this note (haiku, about $0.008 total).

## Answer

An Agent is one unmodified `claude -p` process per Task, started with the user's own claude.ai login,
a JSON Schema for its result, `--output-format stream-json --verbose` for live watching, a fixed
`--session-id` so it can be resumed, and a permission mode that can never prompt. Codex has a
matching `codex exec` for every one of these, so the Agent file can stay CLI-neutral. Running the
unmodified CLI on your own login is the use Anthropic explicitly carves out. The live risks are in
the details (see [Threats](#threats-to-running-on-a-subscription)): `--bare` can't use a
subscription and is due to become the `-p` default; a stray `ANTHROPIC_API_KEY` silently wins over
the login; and "ordinary, individual usage" is the stated assumption behind plan limits.

## 1. Authentication on a subscription, and whether unattended use is permitted

**How `claude -p` authenticates.** It uses the same stored login as interactive `claude`. On macOS
that is the Keychain; on Linux it is `~/.claude/.credentials.json`, and it is keyed per
`CLAUDE_CONFIG_DIR` ([Authentication › Credential management](https://code.claude.com/docs/en/authentication#credential-management)).
The precedence order puts subscription OAuth from `/login` **last**, below cloud-provider variables,
`ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_API_KEY`, `apiKeyHelper`, `CLAUDE_CODE_OAUTH_TOKEN` and Anthropic
profiles. The docs add: "In non-interactive mode (`-p`), the key is always used when present"
([Authentication precedence](https://code.claude.com/docs/en/authentication#authentication-precedence)).

For machines with no browser, `claude setup-token` mints "a one-year OAuth token" for
`CLAUDE_CODE_OAUTH_TOKEN`. It "authenticates with your Claude subscription and requires a Pro, Max,
Team, or Enterprise plan" ([Generate a long-lived token](https://code.claude.com/docs/en/authentication#generate-a-long-lived-token)).
Ants doesn't need it on the user's own machine, where the stored login works.

**`--bare` cannot use a subscription.** "In bare mode, Claude Code never reads OAuth credentials or
the system keychain" and "does not read `CLAUDE_CODE_OAUTH_TOKEN`." The same page says: "`--bare` is
the recommended mode for scripted and SDK calls, and will become the default for `-p` in a future
release" ([Headless › bare mode](https://code.claude.com/docs/en/headless#start-faster-with-bare-mode)).
`claude --help` on 2.1.295 agrees: "Anthropic auth is strictly ANTHROPIC_API_KEY or apiKeyHelper …
(OAuth and keychain are never read)".

**Probed.** In this environment `ANTHROPIC_API_KEY` happened to be set. The first probe's
`system/init` event reported `"apiKeySource":"ANTHROPIC_API_KEY"`, so the API key was billed and the
login was not used. Rerunning with `env -u ANTHROPIC_API_KEY` reported `"apiKeySource":"none"`, and
`claude auth status` showed `"authMethod":"claude.ai"`. **Ants should strip `ANTHROPIC_API_KEY` and
`ANTHROPIC_AUTH_TOKEN` from the Agent's environment and fail the Task if `system/init` reports any
`apiKeySource` other than `"none"`.**

**Is unattended automated use on your own subscription permitted?**

- *Consumer Terms* (Free, Pro and Max; effective 8 Oct 2025), §3 item 7, prohibit: "Except when you
  are accessing our Services via an Anthropic API Key or where we otherwise explicitly permit it, to
  access the Services through automated or non-human means, whether through a bot, script, or
  otherwise." §2: "You may not share your Account login information … or make your Account available
  to anyone else" ([Consumer Terms](https://www.anthropic.com/legal/consumer-terms)).
- *The explicit permission.* Anthropic's own Claude Code docs document `claude -p` "for scripts and
  CI/CD" ([Headless](https://code.claude.com/docs/en/headless)), and document `setup-token` "for CI
  pipelines and scripts where browser login isn't available" on a subscription. The Legal and
  compliance page says the OAuth restrictions do not "prevent an end user from signing in to the
  unmodified Claude Code binary with their own Claude subscription"
  ([Legal and compliance › Authentication and credential use](https://code.claude.com/docs/en/legal-and-compliance#authentication-and-credential-use)).
  The documented route is the unmodified binary, with each user's own login.
- *What is forbidden.* "OAuth authentication … is designed to support ordinary use of Claude Code
  and other native Anthropic applications." "Developers building products or services … including
  those using the Agent SDK, should use API key authentication … Anthropic does not permit
  third-party developers to offer Claude.ai login into their own applications, or to route requests
  through Free, Pro, or Max plan credentials on behalf of their users. Moreover, developers may not
  collect, store, or intermediate Claude.ai credentials or session tokens"
  ([same page](https://code.claude.com/docs/en/legal-and-compliance#authentication-and-credential-use)).
  The Agent SDK overview repeats this: "Unless previously approved, Anthropic does not allow third
  party developers to offer claude.ai login or rate limits for their products, including agents built
  on the Claude Agent SDK" ([Agent SDK overview](https://code.claude.com/docs/en/agent-sdk/overview)).
- *Limits.* "Advertised usage limits for Pro and Max plans assume ordinary, individual usage of
  Claude Code and the Agent SDK" ([Legal and compliance › Acceptable use](https://code.claude.com/docs/en/legal-and-compliance#acceptable-use)).

**CLI versus Agent SDK.** Ants must **shell out to the installed `claude` binary** and never embed
the Agent SDK with a claude.ai login. It must never read, copy or store the user's OAuth token
either; that would be "intermediating credentials". Anthropic's docs call `claude -p` "the Agent SDK
via the CLI", but the carve-out is worded around the unmodified binary and the user's own sign-in,
and the CLI route fits it. Running several Agents in parallel overnight is still a judgement call
against "ordinary, individual usage". Nothing found forbids it outright, and nothing promises it is
fine.

**The login on this machine is a Team plan.** `claude auth status` reports
`"subscriptionType":"team"`, organisation "Stacc Flow". Team falls under the
[Commercial Terms](https://www.anthropic.com/legal/commercial-terms) (effective 17 Jun 2025), not the
Consumer Terms ([Legal and compliance › License](https://code.claude.com/docs/en/legal-and-compliance#license)).
They contain no bot/script clause, but they bar reselling "except as expressly approved". On a
work-paid Team seat, a personal side project's overnight Runs also draw on the employer's
allowance; that is a policy question, not a technical one.

## 2. A structured JSON result

- `--output-format json` returns one object: `result`, `session_id`, `is_error`, `subtype`,
  `total_cost_usd`, `usage`, `modelUsage` and `permission_denials`.
- `--output-format stream-json` (requires `--verbose`) returns newline-delimited events. "The last
  line of the stream is a `result` message"
  ([Headless › Get structured output](https://code.claude.com/docs/en/headless#get-structured-output)).
- `--json-schema '<schema>'` puts the validated object in `structured_output`. An invalid schema
  exits with `Error: --json-schema is not a valid JSON Schema`, and `format` is treated as an
  annotation only (same page). A schema the model can't satisfy ends with result subtype
  `error_max_structured_output_retries`
  ([SDK TypeScript › SDKResultMessage](https://code.claude.com/docs/en/agent-sdk/typescript)).
- **Probed:** `--json-schema` combined with `stream-json` works. The result line carried
  `"structured_output":{"n":7}`, `"subtype":"success"`, `"is_error":false` and
  `"terminal_reason":"completed"`. Internally the model called a `StructuredOutput` tool, so a
  `--tools` allow-list must not starve it. That last point is an inference, not tested.
- Exit code: "0 on success and a non-zero code when the run fails … When a failure happens inside the
  run, such as missing authentication, Claude Code prints the failure as the result on stdout"
  ([Headless › Basic usage](https://code.claude.com/docs/en/headless#basic-usage)). SIGTERM exits 143
  and leaves the turn unfinished. SIGINT ends the turn cleanly
  ([Headless › Stop a run with SIGTERM](https://code.claude.com/docs/en/headless#stop-a-run-with-sigterm)).

## 3. Headless resume

- `claude -p --resume <session-id> "<prompt>"` continues a specific conversation from any directory
  (since v2.1.223). `--resume` also accepts an absolute path to the transcript `.jsonl`.
  `--continue` takes the most recent conversation in the directory and includes `-p` sessions only
  when run with `-p` ([Headless › Continue conversations](https://code.claude.com/docs/en/headless#continue-conversations);
  [Sessions › Resume a session](https://code.claude.com/docs/en/sessions#resume-a-session)).
- **Where the id comes from.** Ants can choose it up front with `--session-id <uuid>` ("must be a
  valid UUID", [CLI reference](https://code.claude.com/docs/en/cli-reference)). The id is also
  reported in `system/init` and in the `result` (`session_id`). Choosing it up front is best: the
  Task records its session before the process starts, so a crash before the first output is still
  resumable.
- **Probed:** resuming the first probe's id kept the same `session_id` and remembered the earlier
  answer. `--fork-session` gives a new id instead.
- On resume, `-p` starts in the permission mode a new `-p` run would start in, not the one the
  session had, so pass `--permission-mode` again. `--mcp-config`, `--settings`, `--add-dir` and
  `--plugin-dir` are not restored either
  ([Sessions › What a resumed session restores](https://code.claude.com/docs/en/sessions#what-a-resumed-session-restores)).
- A tool call cut off by a crash is shown to Claude as interrupted. Claude is told to check whether
  it took effect before retrying. Set `CLAUDE_CODE_RESUME_INTERRUPTED_TURN=1` to continue the
  interrupted turn instead (same page).
- "If you resume the same session in two terminals without forking, messages from both interleave
  into one transcript" ([Sessions › Branch a session](https://code.claude.com/docs/en/sessions#branch-a-session)).
  One live process per Task session.
- Transcripts are deleted after 30 days by default (`cleanupPeriodDays`), so a Task can't be resumed
  after that.

## 4. How a usage limit shows up

- **Messages** (subscription windows): `You've hit your session limit · resets 3:45pm`, `… weekly
  limit · resets Mon 12:00am`, `… Opus limit …` and `… Sonnet limit …`. "Claude Code blocks further
  requests until the reset time shown in the message"
  ([Errors › You've hit your session limit](https://code.claude.com/docs/en/errors#youve-hit-your-session-limit)).
  The reset time in the text is local wall-clock time with no date, so don't parse it.
- **Machine-readable, in `stream-json`.** A `rate_limit_event` carries
  `rate_limit_info.status: "allowed" | "allowed_warning" | "rejected"` and `resetsAt` (epoch seconds).
  An assistant message carries `error: "rate_limit"` (a 429 against your quota, as distinct from
  `"overloaded"`, a 529)
  ([SDK TypeScript › SDKRateLimitEvent, SDKAssistantMessage](https://code.claude.com/docs/en/agent-sdk/typescript)).
  `system/api_retry` events carry `error: "rate_limit"` and `retry_delay_ms`
  ([Headless › Handle API retries](https://code.claude.com/docs/en/headless#handle-api-retries)). The
  result carries `api_error_status` (the HTTP status that ended it) and `terminal_reason` (e.g.
  `"api_error"`).
- **Probed (no limit hit).** On a subscription run, Claude Code emitted a `rate_limit_event` even
  on success. It held more than the docs list: `"rateLimitType":"five_hour"`, `"resetsAt":…`,
  `"overageStatus":"rejected"`, and
  `"unifiedWindows":{"five_hour":{"utilization":0.01,"resetsAt":…},"seven_day":{"utilization":0,"resetsAt":…}}`.
  So Ants can see how close a Run is to the limit and when each window resets **before** the limit
  hits. The extra fields are undocumented and may change. The API-key probe emitted no such event.
- **The exact shape of a rejected run was not observed**, because that needs an exhausted window.
  From the docs, expect a non-zero exit, `is_error: true` with `api_error_status: 429` on the result,
  and a preceding `rate_limit_event` with `status: "rejected"` and `resetsAt`. That is the event to
  key a pause on.
- **Pausing inside the process.** Interactive sessions auto-wait for the reset; that feature is
  interactive only ([Interactive mode › Wait for a usage limit to reset](https://code.claude.com/docs/en/interactive-mode#wait-for-a-usage-limit-to-reset)).
  For headless runs there is `CLAUDE_CODE_RETRY_WATCHDOG=1`, which is "for unattended sessions such
  as … remote workers". It "backs off up to 5 minutes between attempts, or until the limit resets
  when the response carries a rate-limit reset time, so a session that hits a usage limit waits out
  the remaining window". It "fails at once" on a spend-limit or exhausted-usage-credits 429
  ([Env vars](https://code.claude.com/docs/en/env-vars)). Two pause designs follow:
  - *Process waits.* Set the watchdog. A paused Task is a live process whose latest event is
    `system/api_retry` with `error:"rate_limit"`. This is simple, but a weekly limit holds the
    process for days.
  - *Process exits.* Leave the watchdog unset. On a `rejected` `rate_limit_event`, the Task records
    `resetsAt`, the process ends, and Ants later runs `claude -p --resume <id> "continue"`. This
    survives reboots and frees the machine. **It is the better fit for Ants.**
- In burn.sh (prior art), "claude exited non-zero without committing" is treated as "a broken tool
  (a usage limit, an auth failure)" and the loop stops
  (`varde-editor/scripts/burn.sh`). It can't tell the two apart; the `rate_limit_event` can.
- Also: `Login expired · Please run /login` fails every request once the login can't refresh. A
  Fable request on an account that requires usage-credits consent ends with
  `the prompt to confirm went unanswered — nothing was sent`
  ([Errors](https://code.claude.com/docs/en/errors)). Both mean "needs the user", not "retry later".

## 5. Watching a live session

- `--output-format stream-json --verbose` streams every assistant message, tool call, tool result and
  system event as it happens. `--include-partial-messages` adds token deltas, and
  `--forward-subagent-text` adds subagent text
  ([Headless › Stream responses](https://code.claude.com/docs/en/headless#stream-responses)). burn.sh
  already does exactly this: `tee` to `burn-$n.jsonl`, plus a `jq` pretty-printer.
- The transcript is written continuously to
  `~/.claude/projects/<cwd with non-alphanumerics → '-'>/<session-id>.jsonl`. Probed: it was at
  `~/.claude/projects/-private-tmp-ants-probe/8c00c674-….jsonl`. The format "is internal to Claude
  Code and changes between versions"
  ([Sessions › Where transcripts are stored](https://code.claude.com/docs/en/sessions#where-transcripts-are-stored)).
  Watch the stdout stream, not the transcript.
- `claude --bg` with `attach`/`logs` exists, but `-p` rejects `--bg`
  ([Headless › Basic usage](https://code.claude.com/docs/en/headless#basic-usage)).

## 6. Permissions headless, so an Agent can't hang

- In a `-p` run with no permission host, anything that would prompt is denied, so it doesn't hang.
  `--permission-prompts none` (v2.1.259+) also tells Claude not to retry, and it removes
  `AskUserQuestion` ([Headless › Turn off permission prompts](https://code.claude.com/docs/en/headless#turn-off-permission-prompts-in-unattended-runs)).
  Denials appear as `permission_denied` events and in the result's `permission_denials`.
- Modes ([Headless › Auto-approve tools](https://code.claude.com/docs/en/headless#auto-approve-tools)):
  - `dontAsk` with `--allowedTools "Read,Edit,Bash(git *)"` is a fixed allow-list and the
    locked-down choice.
  - `auto` (a classifier reviews each action) is what burn.sh uses. It can end a headless run with an
    execution error if "the server returned no safety verdict for the last 10 responses"
    ([Errors](https://code.claude.com/docs/en/errors)).
  - `acceptEdits` covers file edits only.
- Pass a mode explicitly: "A run where nothing sets a permission mode takes the built-in starting
  permission mode, which can be `auto`."
- Without `--bare`, `-p` "runs the hooks in a project's `.claude/settings.json` and connects the
  servers in its `.mcp.json`, even in a folder you've never trusted" (Headless). Run workspaces
  clone repos, so this matters. `--bare` is out (no subscription). `--safe-mode` disables project
  customisations while keeping auth; it worked in both probes. `--setting-sources user` is the
  narrower option.
- `--max-turns` and `--max-budget-usd` bound a runaway. The budget is a client-side estimate and
  matters little on a subscription.

## 7. The same for `codex exec`

Sources: [Non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode),
[Authentication](https://learn.chatgpt.com/docs/auth), and `openai/codex` at `rust-v0.162.0`
(`codex-rs/exec/src/{lib,cli,exec_events,event_processor_with_jsonl_output}.rs`,
`codex-rs/protocol/src/error.rs`).

| Need | Claude Code | Codex |
|---|---|---|
| Subscription auth | stored `/login`; never `--bare`; strip `ANTHROPIC_API_KEY` | stored `codex login` (ChatGPT) in `~/.codex/auth.json` or keyring; `CODEX_API_KEY` overrides per run |
| Final result as JSON | `--output-format json` → `result` | `-o <file>` / `--output-last-message`; stdout is the final message only |
| Schema-checked result | `--json-schema '<inline>'` → `structured_output` | `--output-schema <file>` → final message conforms |
| Live event stream | `--output-format stream-json --verbose` | `--json`: `thread.started`, `turn.started`, `item.*`, `turn.completed` (with `usage`), `turn.failed`, `error` |
| Session id | `--session-id <uuid>` chosen up front, or `session_id` in output | `thread.started.thread_id` (cannot be chosen up front) |
| Resume | `claude -p --resume <id> "…"` | `codex exec resume <SESSION_ID> "…"` / `resume --last` |
| Transcript on disk | `~/.claude/projects/<cwd>/<id>.jsonl` | `$CODEX_HOME/sessions/…/rollout-<ts>-<thread_id>.jsonl`; `--ephemeral` disables |
| Never prompt | `--permission-mode dontAsk` + `--allowedTools`, or `--permission-prompts none` | built in: exec hard-codes `approval_policy: Never` ("Default to never ask for approvals in headless mode", `lib.rs`); `--sandbox read-only` (default) / `workspace-write` / `danger-full-access` |
| Exit code | non-zero on failure; 143 on SIGTERM | 1 when a turn fails, is interrupted, or a non-retrying error arrives (`lib.rs`, `error_seen`) |
| Usage limit | `rate_limit_event` with `status:"rejected"` and `resetsAt` epoch | `turn.failed` whose `error.message` is text only: `You've hit your usage limit. … Try again at 3:45 PM.` (local time; date added if not today; `error.rs`). No structured reset field in the exec JSONL |
| Repo guard | none | refuses outside a git repo unless `--skip-git-repo-check` |

**Codex and subscription automation.** OpenAI's Codex docs are lukewarm but explicit. "API keys are
the right default for automation"; ChatGPT-managed auth in CI is "advanced", for "users who need
ChatGPT/Codex rate limits instead of API key usage". "Use this path only if you specifically need to
run as your Codex account"; "Do not use this workflow for public or open-source repositories". They
also advise to "run Codex on the runner so Codex refreshes it in place". OpenAI's Terms of Use
couldn't be read from here (openai.com returned 403 to automated fetches), so the consumer-terms
question for Codex is still open.

**Implications for a CLI-neutral Agent definition.** An Agent names a CLI. Ants holds one small
adapter per CLI that maps four things:

- the result schema to a flag;
- the event stream to a common set: started (id), progress, finished (result), failed;
- "paused until T" (Claude: `resetsAt`; Codex: parse `Try again at …`, the weak spot);
- the resume command.

Permissions can't be expressed the same way. Claude uses tool allow-lists; Codex uses a sandbox
level. The Agent should state intent (read-only, edit the workspace, or full) and let the adapter
translate.

## Threats to running on a subscription

1. **`--bare` is slated to become the default for `-p`, and bare mode never reads OAuth.** When that
   ships, every Agent fails on auth unless Ants pins the old behaviour. Watch for an opt-out flag.
   The cheap guard: assert `apiKeySource == "none"` from `system/init` and treat
   `authentication_failed` as "needs the user".
2. **An inherited `ANTHROPIC_API_KEY` silently wins in `-p`.** This happened in the first probe here,
   and that run was billed to the API key. Scrub the Agent's environment.
3. **Agent SDK with claude.ai login is not allowed for third-party products.** Only the unmodified
   binary on the user's own login is allowed, so Ants must shell out and never handle tokens.
4. **"Ordinary, individual usage".** Parallel overnight Runs are the edge of what plan limits
   assume, and "Anthropic reserves the right to take measures to enforce these restrictions … without
   prior notice."
5. **This machine's login is a Team seat (Stacc Flow), not Pro/Max.** Commercial Terms apply, and
   the employer's allowance is being spent.
6. **Codex surfaces the usage-limit reset only as localized prose,** so a Codex Agent's pause time is
   a string parse.
