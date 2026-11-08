# Coding standards

Read during code review. Each rule is a judgement call; tests and tooling cover the rest.

- **One source for domain facts.** A list, label or wording the modeler shows comes from the server, as
  `/catalogue.json` and `modelerFacts` (`Checks.kt`) do, so `static/modeler.js` holds no second copy. Logic that edits
  the diagram live, such as a field's `required`, stays in the browser.
- **Text shows as Markdown, everywhere.** Any text in Ants that someone wrote (a name, a title, a note, a question,
  a line, whether an Agent, a Script or the user wrote it) shows with its Markdown rendered, so no `**` or `` ` ``
  ever shows raw. A block renders through `Transcript.markdown`, a single line or a title through `Transcript.inline`,
  and a place that shows only plain text (a tab, a dialog, an attribute, a chart's labels) through `Transcript.plain`.
  Where the text is edited in place, the editor shows it as typed and the page shows it rendered once the edit ends.
  `MarkdownEverywhereTest` fails on a template that prints such text raw.
