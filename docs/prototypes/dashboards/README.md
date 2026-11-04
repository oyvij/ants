# Dashboards prototype

The UI and UX guide for Dashboards (ADR 0032). Keep its layout and behaviour; take colours, type, spacing and the
brand from Ants' own styles.

Run it with `node server.js` (port 8765, or `PORT=…`), then fill it with `./fake-run.sh`, `node gallery.js` or
`node morning.js` (needs `gh` and `claude`). Dashboards are kept in `dashboards.json` beside the server.

Where the decisions differ from the prototype:

- **No draft Widgets.** A helper creates a real Widget; the prototype's draft badge and Publish button go.
- **Helpers suggest, then wait.** A helper writes one sentence and changes a Dashboard only after the user replies;
  the prototype has no helpers.
- **Menu order is the user's.** A new Dashboard goes to the bottom and the user drags to reorder; the prototype keeps
  creation order.
- **Lists drop their oldest items** past 100 instead of growing; a Post is refused above 256 KB.
- **Every Post shows the Run that sent it**, and a Widget offers **Clear data** in edit mode.
- **Phones** show one column, view mode only, and nothing is wider than the screen.
- **Libraries are bundled**, not loaded from a CDN, and Widgets follow Light and Dark mode.
