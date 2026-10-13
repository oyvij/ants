// Turns the Skill form's schema into a CodeMirror JSON editor in the prototype's editor box (SKILL.md is a markdown
// field, markdown.js). The bar says whether the schema is valid, by the same check Save runs, and Save is disabled
// while it fails. The text area stays in the form and carries what is posted.
import {EditorView, drawSelection, keymap} from "@codemirror/view"
import {EditorState} from "@codemirror/state"
import {defaultKeymap, history, historyKeymap} from "@codemirror/commands"
import {bracketMatching, defaultHighlightStyle, syntaxHighlighting} from "@codemirror/language"
import {json, jsonParseLinter} from "@codemirror/lang-json"
import {linter, lintGutter} from "@codemirror/lint"

const form = document.querySelector("form.skill")
const save = document.querySelector("button[form=skill]")
const csrf = form.querySelector("input[name=_csrf]").value
const parse = jsonParseLinter()

function status(bar, html) {
    bar.querySelector(".st").innerHTML = html
}

const schemaCheck = bar => linter(async view => {
    const text = view.state.doc.toString()
    let diagnostics = text.trim() ? parse(view) : []
    if (!diagnostics.length && text.trim()) {
        const response = await fetch("/skills/schema", {method: "POST", body: new URLSearchParams({_csrf: csrf, schema: text})})
        diagnostics = (await response.json()).map(message => ({from: 0, to: text.length, severity: "error", message}))
    }
    if (save) {
        save.disabled = diagnostics.length > 0
        save.title = save.disabled ? "result.schema.json is invalid" : ""
    }
    status(bar,!text.trim() ? '<span class="muted">empty: any output</span>'
        : diagnostics.length ? `<span class="bad">✗ ${diagnostics[0].message.replace(/[&<>]/g, c => `&#${c.charCodeAt(0)};`)}</span>` : '<span class="ok">✓ valid schema</span>')
    return diagnostics
})

for (const textarea of form.querySelectorAll("textarea[name=schema]")) {
    const ed = Object.assign(document.createElement("div"), {className: "ed json" + (textarea.hasAttribute("data-tall") ? " tall" : "")})
    const bar = Object.assign(document.createElement("div"), {className: "bar"})
    bar.innerHTML = `<span class="sp">JSON Schema</span><span class="st"></span>${textarea.readOnly ? '<span class="st muted">Read-only · Copy to edit</span>' : ""}`
    const view = new EditorView({
        doc: textarea.value,
        extensions: [
            history(), drawSelection(), EditorView.lineWrapping, keymap.of([...defaultKeymap, ...historyKeymap]),
            EditorState.readOnly.of(textarea.readOnly),
            EditorView.updateListener.of(update => { if (update.docChanged) textarea.value = update.state.doc.toString() }),
            json(), syntaxHighlighting(defaultHighlightStyle), bracketMatching(), schemaCheck(bar), lintGutter(),
        ],
    })
    ed.append(bar, view.dom)
    if (textarea.readOnly) ed.classList.add("ro")
    textarea.after(ed)
    textarea.hidden = true
}
