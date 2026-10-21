# Coding standards

Read during code review. Each rule is a judgement call; tests and tooling cover the rest.

- **One source for domain facts.** A list, label or wording the modeler shows comes from the server, as
  `/catalogue.json` and `modelerFacts` (`Checks.kt`) do, so `static/modeler.js` holds no second copy. Logic that edits
  the diagram live, such as a field's `required`, stays in the browser.
