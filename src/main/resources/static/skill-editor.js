// Turns the Skill form's text areas into CodeMirror editors. The schema is checked as you type, by the same check
// Save runs, and Save is disabled while it fails. The text areas stay in the form and carry what is posted.
import {basicSetup, EditorView} from "codemirror"
import {EditorState} from "@codemirror/state"
import {json, jsonParseLinter} from "@codemirror/lang-json"
import {linter, lintGutter} from "@codemirror/lint"

const form = document.querySelector("form.skill")
const save = form.querySelector("button")
const csrf = form.querySelector("input[name=_csrf]").value
const parse = jsonParseLinter()

const schemaCheck = linter(async view => {
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
    return diagnostics
})

for (const textarea of form.querySelectorAll("textarea")) {
    const view = new EditorView({
        doc: textarea.value,
        extensions: [
            basicSetup,
            EditorState.readOnly.of(textarea.readOnly),
            EditorView.updateListener.of(update => { if (update.docChanged) textarea.value = update.state.doc.toString() }),
            ...(textarea.name === "schema" ? [json(), schemaCheck, lintGutter()] : []),
        ],
    })
    textarea.after(view.dom)
    textarea.hidden = true
}
