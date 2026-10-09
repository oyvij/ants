# Which modeler do users draw Processes in?

Research for [oyvij/ants#12](https://github.com/oyvij/ants/issues/12). Builds on
[Which BPMN and DMN engine runs Ants?](https://github.com/oyvij/ants/blob/research/bpmn-engine/research/bpmn-engine.md),
which settled the engine on Operaton and named the Camunda Desktop Modeler and the Operaton Modeler.
Checked on 2026-10-09 against GitHub repos (via the GitHub API and shallow clones), the bpmn.io
licence text, docs.camunda.io, docs.camunda.org and Operaton's own docs. Each claim cites its source.
Inferences are marked as such.

<!-- ANSWER -->

## Route A: an existing desktop modeler, as-is

### A1. Camunda Desktop Modeler, Camunda 7 target, plus an Ants element template

**Licence and health.** MIT, 1,723 stars, v5.52.0 released 2026-10-06, v5.51.1 on 2026-09-14
([GitHub camunda/camunda-modeler](https://github.com/camunda/camunda-modeler), GitHub API). It
bundles bpmn-js, dmn-js and form-js "licensed under the bpmn.io license" (README). Free to use
without asking anyone.

**Is the C7 target still alive?** Yes, today. The 5.5x changelog still carries Camunda 7 work: "FIX:
enable element template chooser in Camunda 7 (#6108)" in 5.51.0, "support Camunda 7.24 (#4984)"
earlier ([CHANGELOG.md](https://github.com/camunda/camunda-modeler/blob/main/CHANGELOG.md)). No
official removal date exists. The nearest statement is a Camunda reply on the forum: "there will
always be a version of the Desktop Modeler that supports the latest Camunda 7 version but it might
not be the latest Desktop Modeler version", and C7 support is expected to last until Enterprise
support ends (April 2030)
([forum: Desktop Modeler support post 2025 October for Camunda 7](https://forum.camunda.io/t/desktop-modeler-support-post-2025-october-for-camunda-7/55127)).
*Inference:* the worst case is pinning an older Modeler version, not losing the tool.

**Does Operaton read what it writes?** Yes, with a deprecation clock. The Modeler writes the
`camunda:` namespace (`http://camunda.org/schema/1.0/bpmn`). Operaton's parser accepts both:
`new Namespace(BpmnParser.OPERATON_BPMN_EXTENSIONS_NS, BpmnParser.CAMUNDA_BPMN_EXTENSIONS_NS)`
([BpmnParse.java:272](https://github.com/operaton/operaton/blob/main/engine/src/main/java/org/operaton/bpm/engine/impl/bpmn/parser/BpmnParse.java)),
but the `camunda:` constant is `@Deprecated(forRemoval = true, since = "1.0")`
([BpmnParser.java:60](https://github.com/operaton/operaton/blob/main/engine/src/main/java/org/operaton/bpm/engine/impl/bpmn/parser/BpmnParser.java)).
Operaton's migration guide states the current promise without a date: "The engine accepts both the
`operaton:` and the legacy `camunda:` extension namespaces"
([docs: camunda-to-operaton.md](https://github.com/operaton/documentation/blob/main/docs/documentation/update/camunda-to-operaton.md)).
*Inference:* when Operaton drops `camunda:`, the fix is a namespace rename on the way to the engine.
The Operaton Modeler already does exactly that rename as a three-line regex (see A2).

**How Agent, Skill, inputs and result are entered.** Camunda 7 element templates. Bindings available
for C7: `property`, `camunda:property`, `camunda:inputParameter`, `camunda:outputParameter`,
`camunda:in`, `camunda:out`, `camunda:field`, `camunda:errorEventDefinition`, and more. Field types:
"`String`, `Text`, `Boolean`, `Dropdown`, or `Hidden`". Constraints: `notEmpty`, `minLength`,
`maxLength`, `pattern`
([docs.camunda.org 7.24: element templates](https://docs.camunda.org/manual/7.24/modeler/element-templates/)).
So an "Agent Task" template can:
- hide `camunda:type = external` and `camunda:topic = agent` (`property` binding, `Hidden`);
- offer **Agent** and **Skill** as `Dropdown`s bound to `camunda:property`, with `notEmpty`;
- take **inputs** as a `String` property (variable names) or as `camunda:inputParameter`s;
- take the **result** as a `Text` property holding the promised JSON shape, or as
  `camunda:outputParameter`s.

The catch: dropdown choices are **static**, "stored in a custom properties `choices` attribute as
`{ name, value }` pairs" (same page). The template cannot ask Ants for the current Agents and Skills.
Ants has to regenerate the template JSON whenever an Agent or Skill is added. Templates load from a
global `resources/element-templates` folder (on macOS, the user data directory) or from a
`.camunda/element-templates` folder in the diagram's directory or any parent. They load at startup,
and Cmd/Ctrl+R reloads them; nothing says they reload on file change
([docs.camunda.io: configuring templates](https://docs.camunda.io/docs/components/modeler/desktop-modeler/element-templates/configuring-templates/)).
*Inference:* Ants writes `.camunda/element-templates/ants.json` into the folder that holds the
Processes, and the user presses Cmd+R after creating an Agent. Workable, a little clumsy.

**DMN.** Yes. DMN tables and DRDs, C7 target, deployable to the same engine (it bundles dmn-js,
README).

**Deploy to the engine.** Yes, built in, and it is what Operaton's own quick start tells users to
do: "click on the deploy button in the Camunda Modeler ... provide an URL ... (e.g.
`http://localhost:8080/engine-rest`)"
([Operaton docs: quick start, deploy](https://github.com/operaton/documentation/blob/main/docs/get-started/quick-start/deploy.md)). The C7 deployment tool posts to `/deployment/create` on a
REST URL defaulting to `http://localhost:8080/engine-rest`
([CamundaAPI.js](https://github.com/camunda/camunda-modeler/blob/main/client/src/plugins/camunda-plugin/shared/CamundaAPI.js)).
Operaton Run serves the same REST API at `http://localhost:8080/engine-rest/`
([Operaton docs: Operaton Run](https://github.com/operaton/documentation/blob/main/docs/documentation/user-guide/operaton-bpm-run.md)),
and Operaton keeps a "fully compatible REST API" with Camunda 7
([operaton README](https://github.com/operaton/operaton)). *Inference:* deploy and "start instance"
work against Operaton unchanged. Not tested here.

**Effort.** Build: one generated element-template JSON (tens of lines) and the code in Ants that
regenerates it. Maintain: re-check the C7 target on Modeler upgrades; add the namespace rename the
day Operaton drops `camunda:`.

### A2. Operaton Modeler

**Licence and health.** MIT, "ported from Fluxnova Modeler and adapted for the Operaton platform".
It **has no releases**: the README tells you to `npm install` and `npm run build`. 7 stars, last push
2026-06-24, most commits on 2026-06-16
([GitHub operaton/operaton-modeler](https://github.com/operaton/operaton-modeler), GitHub API).
Operaton's own docs point users to it: "You can model BPMN process models with Operaton Modeler"
([docs: modeling-bpmn](https://github.com/operaton/documentation/blob/main/docs/documentation/modeling-bpmn/index.md)).
It pins older bpmn.io libraries than Camunda's (`bpmn-js ^18.3.1` against Fluxnova's `^18.25.0`,
`client/package.json` in each repo).

**Does Operaton read what it writes?** Yes, natively. It edits with Camunda's moddle internally and
rewrites to `operaton:` on save: `normalizeOperatonXml` replaces `camunda:` prefixes with
`operaton:` and declares `http://operaton.org/schema/1.0/bpmn` (and `.../dmn`), called from
`BpmnEditor.js` and `DmnEditor.js` on export
([client/src/util/operatonXml.js](https://github.com/operaton/operaton-modeler/blob/main/client/src/util/operatonXml.js)).
Opening a `camunda:` file triggers a conversion dialog
([operatonConversion.js](https://github.com/operaton/operaton-modeler/blob/main/client/src/app/tabs/util/operatonConversion.js)).
This is the only modeler found that writes the namespace Operaton intends to keep.

**Agent, Skill, inputs, result.** The same element-template engine as Camunda's (it depends on
`bpmn-js-element-templates`, and its sample templates still bind `camunda:property`, which the save
step renames). Same static-dropdown catch as A1.

**DMN.** Yes (dmn-js, with an `operaton:` DMN namespace).

**Deploy.** Yes: it keeps Camunda's deployment tool, defaulting to
`http://localhost:8080/engine-rest`
([DeploymentTool.js:51](https://github.com/operaton/operaton-modeler/blob/main/client/src/plugins/camunda-plugin/deployment-tool/DeploymentTool.js)).

**Effort.** Same template work as A1, plus building an Electron app from source on every machine,
and a young fork that already lags upstream. *Inference:* the right tool the day it ships signed
releases; not today.

### A3. Fluxnova Modeler (FINOS)

MIT, v1.3.2 released 2026-08-21, active (pushed 2026-10-09)
([GitHub finos/fluxnova-modeler](https://github.com/finos/fluxnova-modeler), GitHub API). It is the
parent of the Operaton Modeler, but it writes the `fluxnova:` namespace
(`http://fluxnova.finos.org/schema/1.0/bpmn`, `client/src/moddle/fluxnova-bpmn-moddle.json`), which
Operaton does not parse. Reject for Ants.

## Route B: a thin Ants modeler page on bpmn-js / dmn-js

### What it is built from

| Package | Licence | Latest (npm, 2026-10-09) | What it gives |
|---|---|---|---|
| `bpmn-js` | bpmn.io licence (MIT + watermark clause) | 18.31.0 | the BPMN canvas |
| `dmn-js` | bpmn.io licence | 17.12.3 | DRD, decision table, literal expression editors |
| `bpmn-js-properties-panel` | MIT | 5.65.2 | the side panel, with a Camunda 7 provider |
| `bpmn-js-element-templates` | MIT | 2.37.1 | element templates in the panel |
| `camunda-bpmn-js` | MIT (bundles bpmn-js) | 5.36.0 | all of the above as one "Camunda Platform Modeler" |
| `camunda-dmn-js` | MIT (bundles dmn-js) | 3.8.3 | the same for DMN |

Licences from the GitHub API and the npm registry. All repos were pushed to in October 2026.

**The watermark.** bpmn-js and dmn-js are MIT plus one clause: "The source code responsible for
displaying the bpmn.io project watermark that links back to https://bpmn.io as part of rendered
diagrams MUST NOT be removed or changed. When this software is being used in a website or
application, the watermark must stay fully visible and not visually overlapped by other elements"
([bpmn-js LICENSE](https://github.com/bpmn-io/bpmn-js/blob/develop/LICENSE), same text in
[dmn-js](https://github.com/bpmn-io/dmn-js)). Free for a personal tool; the small logo stays in the
canvas corner. The desktop modelers carry the same obligation, they just meet it for you.

**No build step needed for the thin version.** `camunda-bpmn-js` ships pre-built bundles:
"Use a pre-packaged distribution ... `dist/camunda-platform-modeler.development.js`", and its
`camunda-platform` distribution already includes `camunda-bpmn-moddle`, the Camunda 7 properties
provider, `ElementTemplatesPropertiesProviderModule` and the element-template chooser
([camunda-bpmn-js README](https://github.com/camunda/camunda-bpmn-js),
[docs/DISTRIBUTIONS.md](https://github.com/camunda/camunda-bpmn-js/blob/main/docs/DISTRIBUTIONS.md),
[lib/camunda-platform/Modeler.js](https://github.com/camunda/camunda-bpmn-js/blob/main/lib/camunda-platform/Modeler.js)).
`dist/camunda-platform-modeler.production.min.js` resolves on unpkg for both `camunda-bpmn-js@5.36.0`
and `camunda-dmn-js@3.8.3` (HTTP 200, checked 2026-10-09). *Inference:* vendor two JS files and two
CSS files into Ants; no npm toolchain.

### How Agent, Skill, inputs and result get entered

This is where B beats A. The same Camunda 7 element templates work, but the page can **fetch them
from Ants every time a diagram opens**. The loader takes `config.elementTemplates` as "an array of
template descriptors or a node style callback to retrieve the templates asynchronously", and calls it
again on every `diagram.init`
([ElementTemplatesLoader.js](https://github.com/bpmn-io/bpmn-js-element-templates/blob/main/src/element-templates/ElementTemplatesLoader.js)).
So Ants generates the "Agent Task" template from the Agents and Skills on disk, and the dropdowns
are always current. No file to regenerate, no Cmd+R.

Beyond templates, a custom properties provider (MIT `@bpmn-io/properties-panel` components) can do
what static templates cannot: filter Skills by Agent, offer the results of earlier Tasks as inputs,
validate the result schema. *Inference:* that needs a JS bundler and Preact-style components, and
breaks on bpmn-js-properties-panel majors (it is at 5.x). Start without it.

### Namespace, DMN, deploy

- **Namespace:** writes `camunda:` (it uses `camunda-bpmn-moddle`). No `operaton:` moddle is on npm
  (`operaton-bpmn-moddle` and `@operaton/bpmn-moddle` do not exist on the registry); the Operaton
  Modeler keeps its own unpublished copy. The fix when Operaton drops `camunda:` is the same rename
  on save the Operaton Modeler does
  ([operatonXml.js](https://github.com/operaton/operaton-modeler/blob/main/client/src/util/operatonXml.js)),
  and Ants owns the save path here, so it is one place.
- **DMN:** `camunda-dmn-js` gives the Camunda 7 DMN modeler in the same form.
- **Deploy:** Ants' backend posts the XML to Operaton's `/engine-rest/deployment/create` on Save.
  Going through Ants avoids CORS; Operaton Run would otherwise need `operaton.bpm.run.cors` enabled
  ([Operaton docs: Operaton Run, CORS](https://github.com/operaton/documentation/blob/main/docs/documentation/user-guide/operaton-bpm-run.md)).
  Save and deploy become one click, and Ants can validate the Process (every Agent Task names an
  existing Agent and Skill) before the engine sees it.

### Effort

*Inference, not measured:*
- **Thin page:** one HTML file of roughly 150 lines of JS (open a Process from the Processes
  folder, BPMN/DMN tabs, Save = deploy), plus three Ants HTTP endpoints (list/read Processes, serve
  the generated template, save-and-deploy). About two days.
- **What it gives up against A:** the desktop modeler's file tabs, BPMN linting against the C7
  target, "start instance", the template chooser polish, and Camunda's QA on every release. Some come
  free with `camunda-bpmn-js`; linting and multi-file handling would be Ants code.
- **New surface:** issue #1 settles the Ants UI as a TUI in a Varde pane. Route B adds an HTTP page
  Ants serves and the user opens in a browser. Small, but it is a second UI.
- **Maintain:** bump two vendored bundles now and then. bpmn-js ships about weekly (18.30.1 on
  2026-09-24, 18.31.0 on 2026-10-01, GitHub releases), so there is no pressure to follow every one.
