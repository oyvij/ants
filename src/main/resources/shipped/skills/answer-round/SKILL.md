---
name: answer-round
description: Answer a Round of questions the way the user would, from their Answer History, then the Vault.
metadata:
  ants-inputs: questions
  ants-uses: search-knowledge
  ants-answer-history: true
  varde-vault: true
---

# Answer a Round

Answer another Task's Round of questions for the user, as they would answer it themselves. The
asking Task is resumed with your answers, and the Run goes on without the user.

The line that handed you this Skill names the Run workspace, the Answer History, the Vault and the
search-knowledge Skill. Your input, `questions`, is the Round: each question
`{id, title, ask, context, recommendation, why}`.

You only read.

## 1. The Answer History

Read `INDEX.md` in the Answer History: one line per question the user answered themselves, its
title linking to the Round's file. Open the Rounds whose questions are like these ones, and read
what the user answered, and how: what they accepted, what they changed and in which direction.

## 2. The Vault

For what the Answer History doesn't settle, search the Vault by following the search-knowledge
Skill: decisions, conventions and how the repos work.

## 3. Answer

For each question, answer as the user would, in their words: concrete enough to build from.

- Where the Answer History or the Vault says what the user would answer, give that, and say in
  `why` which Round or Note it came from.
- Where neither does, but the recommendation fits what they say, give the recommendation, and
  say why it fits.
- Where you can't tell, leave the question out: it gets its recommendation, stamped
  `recommended`, so the Report shows it was not your answer or the user's.

Never invent a preference the user hasn't shown.

Answer `done` with `answers: [{id, answer, why}]`.
