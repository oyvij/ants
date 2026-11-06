---
name: dashboards
description: Post what your work found to the user's Dashboards, so it shows on a Widget they glance at. Use when you are asked to Post to a Dashboard or a Widget.
---

# Dashboards

A Dashboard is a grid of Widgets. Each Widget has a type that fixes the data it accepts, and an address,
`<dashboard id>/<widget id>`, that never changes. You Post with the tools `ListWidgets` and `PostToWidget`.

## 1. List the Widgets first

Call `ListWidgets` before your first Post. Each Widget it gives has its `address`, its Dashboard, its `type`, its
`title` and the data it `accepts`. Post only to a Widget it lists: when the one you were asked to Post to isn't
there, stop and say which Widget is missing. Never guess an address.

## 2. Post the right shape

Call `PostToWidget` with the Widget's `dashboard` id, its `widget` id and `data` in exactly the shape `accepts`
gives, such as `{"value": 42, "unit": "GB"}` for a number Widget or `{"text": "…"}` for a text Widget. Leave out
any field the shape doesn't name.

A text Widget renders its `text` as Markdown (CommonMark, with GFM tables), so format it: bold, lists, tables, code.

## 3. Keep Posts small

A Post is what a person reads at a glance: a number, a short paragraph, a few list items or chart points. Summarise
rather than paste output. A Post over 256 KB is refused. A list or status Widget keeps only its newest 100 items.

## 4. Fix a refused Post

A refused Post answers with an error naming the Widget, its type and the shape it needs. Read it, fix your data and
Post again, once or twice. If it is still refused, or the error says you may not Post there, carry on with your
work and say in your answer which Post was refused and why. A refused Post never makes your work fail by itself.

## 5. Leave computed numbers to a Script

A number a rule computes, such as free disk space, a count of issues or a test pass rate, belongs in a Script that
Posts it with `ants.post`: it costs no tokens and gives the same answer every time. Post yourself what needs
judgement: a summary, picks worth reading, the state of something you assessed.
