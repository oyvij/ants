---
name: grill
description: Grill the user on what a Run should build, one Round of questions at a time, until nothing is left to settle, naming any repo the work turns out to need.
metadata:
  ants-inputs: prompt brief clones
  ants-verbosity: true
---

<!-- Matt Pocock's grilling Skill (https://github.com/mattpocock/skills, v1.3.1), word for word below the line.
Copyright (c) 2026 Matt Pocock. MIT License: see LICENSE in the tdd Skill's folder. -->

## In Ants

Below the line is Matt Pocock's grilling Skill, word for word. Follow it. Ants runs it without a
chat, so where the two differ, this section wins.

The plan to grill is what the Run should build. Your inputs; paths are relative to the Run
workspace:

- `prompt`: what the user wants, in their words.
- `brief`: the scout's brief: the repos and why, and what the Vault says.
- `clones`: the repos cloned so far, `[{name, path, commit}]`.

You only read: the brief, and the clones' code, docs, ADRs and glossary. You have no sub-agents,
so find each fact yourself, before the round that needs it.

**A round is one answer.** Answer `done` with the round's frontier as `questions`, and `repos`.
Each question is an entry rather than the chat format below:

- `id`: short and unique across the Run, such as `export-format`;
- `title`: the question title;
- `ask`: the question itself, one decision;
- `context`: the rest of the question body: what it settles, and the choices;
- `recommendation`: your recommended answer, concrete enough to build from;
- `why`: the reason for your recommendation.

**Write for a quick read.** The user reads each question on the Answer page, often on a phone, not
in a chat. `ask` is one sentence. `context` lists the choices as a Markdown list, one short line
each, and leaves out what the user already knows. `recommendation` is one sentence the user could
send back as their answer, and `why` is one sentence. Put code, file names, commands and names from
the code in backticks: the page shows them as code. Your `summary` heads the page, so name the
Round's topic in a few words, such as "Hello CLI: language, greeting, how it runs", rather than
describing the Round.

You are resumed with the round's answers as `{answers: [{id, answer, source}]}`. Take each answer
as settled, whoever gave it, and recompute the frontier. Never ask a settled question again unless
a later answer reopens it, and then say so in its `context`.

**Repos.** When the answers show the work needs a repo that wasn't in `clones` when you started,
or may need it and nothing you have read rules it out, name it in `repos` by one of the Source map
names your hand-off line lists, or by a git URL the user gave; never by a package, folder or
product name you found in the code. Ants sends back any other name. Never take a repo you haven't
read to need no change. Name every such repo again in each later round, the last one included:
`repos` is always the whole list. Each is cloned at `repos/<name>/` before your next round, so read
it then. One that never shows in `clones` was not cloned, and the Spec lists it as not checked.
With none, `repos` is `[]`.

**The end.** When the frontier is empty, answer `done` with `questions: []`, and `repos` as
before. The Run then writes the Spec from the Run log, which holds every round, and the user
confirms the shared understanding by approving it.

---

Interview the user relentlessly until you reach a shared understanding. Map this as a **design tree**: every decision branches into the decisions that hang off it.

Work the tree in **rounds**. The **frontier** is every decision whose prerequisites are already settled: the questions you can ask _now_ without guessing at answers you haven't heard yet. Ask the whole frontier in one round: number each question and give your recommended answer. Then wait for the user's answers before the next round.

Format a round like so:

```
❓ **Q1** - **<question title>**: <question body, might be multiple paragraphs, including multiple choices>

➡️ <your recommended answer>

---

❓ **Q2** - **<question title>**: <question body, might be multiple paragraphs, including multiple choices>

➡️ <your recommended answer>
```

Each round the user answers reshapes the tree: settled decisions push the frontier outward and unblock questions that depended on them. Recompute the frontier and ask the next round. A question whose answer depends on another question still open in this round belongs to a _later_ round, not this one.

Finding _facts_ is your job, never the user's. When a frontier question needs a fact from the environment (filesystem, tools, etc.), dispatch a sub-agent to find it; don't ask the user for anything you could look up yourself. Don't block on it: a running exploration is an unsettled prerequisite, so only the questions downstream of it wait for the sub-agent to report; ask the rest of the frontier now. The _decisions_ are the user's: put each to them and wait.

The session is done when the frontier is empty: every branch of the design tree visited, nothing left silently assumed. Do not act on it until the user confirms you have reached a shared understanding.
