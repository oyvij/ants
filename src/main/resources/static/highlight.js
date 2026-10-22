// Colours the code on Review changes by its language, which the file's name says: each diff table (By repo) and each
// step's code (Story). A side's lines are read as one text, so a comment or string that spans lines is coloured whole:
// a diff's kept and added lines are the new file, its kept and removed lines the old one. A file whose language Ants
// doesn't know, or a page without this script, shows the code as it was, in the diff's colours.
import {StreamLanguage} from "@codemirror/language"
import {classHighlighter, highlightCode} from "@lezer/highlight"

const modes = {
    clike: () => import("@codemirror/legacy-modes/mode/clike"),
    css: () => import("@codemirror/legacy-modes/mode/css"),
    dockerfile: () => import("@codemirror/legacy-modes/mode/dockerfile"),
    go: () => import("@codemirror/legacy-modes/mode/go"),
    groovy: () => import("@codemirror/legacy-modes/mode/groovy"),
    javascript: () => import("@codemirror/legacy-modes/mode/javascript"),
    properties: () => import("@codemirror/legacy-modes/mode/properties"),
    python: () => import("@codemirror/legacy-modes/mode/python"),
    ruby: () => import("@codemirror/legacy-modes/mode/ruby"),
    rust: () => import("@codemirror/legacy-modes/mode/rust"),
    shell: () => import("@codemirror/legacy-modes/mode/shell"),
    sql: () => import("@codemirror/legacy-modes/mode/sql"),
    swift: () => import("@codemirror/legacy-modes/mode/swift"),
    toml: () => import("@codemirror/legacy-modes/mode/toml"),
    xml: () => import("@codemirror/legacy-modes/mode/xml"),
    yaml: () => import("@codemirror/legacy-modes/mode/yaml"),
}

// A file's extension, or its whole name, and the mode and its export that read it.
const byName = {
    kt: ["clike", "kotlin"], kts: ["clike", "kotlin"], java: ["clike", "java"], scala: ["clike", "scala"],
    c: ["clike", "c"], h: ["clike", "c"], cpp: ["clike", "cpp"], cc: ["clike", "cpp"], hpp: ["clike", "cpp"],
    cs: ["clike", "csharp"], dart: ["clike", "dart"],
    js: ["javascript", "javascript"], mjs: ["javascript", "javascript"], cjs: ["javascript", "javascript"],
    jsx: ["javascript", "javascript"], ts: ["javascript", "typescript"], tsx: ["javascript", "typescript"],
    mts: ["javascript", "typescript"], json: ["javascript", "json"],
    css: ["css", "css"], scss: ["css", "sCSS"], less: ["css", "less"],
    html: ["xml", "html"], kte: ["xml", "html"], jte: ["xml", "html"], xml: ["xml", "xml"], bpmn: ["xml", "xml"],
    svg: ["xml", "xml"],
    py: ["python", "python"], sh: ["shell", "shell"], bash: ["shell", "shell"], zsh: ["shell", "shell"],
    yml: ["yaml", "yaml"], yaml: ["yaml", "yaml"], toml: ["toml", "toml"], sql: ["sql", "standardSQL"],
    go: ["go", "go"], rs: ["rust", "rust"], gradle: ["groovy", "groovy"], groovy: ["groovy", "groovy"],
    properties: ["properties", "properties"], rb: ["ruby", "ruby"], swift: ["swift", "swift"],
}
const byWholeName = {Dockerfile: ["dockerfile", "dockerFile"]}

const languages = new Map()
const language = path => {
    const name = path.split("/").pop()
    const [mode, parser] = byWholeName[name] || (name.includes(".") && byName[name.split(".").pop()]) || []
    if (!mode) return null
    const key = `${mode}.${parser}`
    if (!languages.has(key)) languages.set(key, modes[mode]().then(it => StreamLanguage.define(it[parser]), () => null))
    return languages.get(key)
}

// The lines of text, each its coloured spans and plain text as a fragment.
const paint = (lines, lang) => {
    if (!lines.length) return []
    const text = lines.join("\n")
    const out = [document.createDocumentFragment()]
    highlightCode(text, lang.parser.parse(text), classHighlighter,
        (piece, classes) => out.at(-1).append(classes ? Object.assign(document.createElement("span"), {className: classes, textContent: piece}) : piece),
        () => out.push(document.createDocumentFragment()))
    return out
}

const sign = text => Object.assign(document.createElement("span"), {className: "sign", textContent: text})

// Colours the cells, each by the text it holds after its first `skip` characters, read in order as one text.
const colour = (cells, lang, skip = 0) => {
    paint(cells.map(it => it.textContent.slice(skip)), lang).forEach((line, i) => {
        const cell = cells[i]
        cell.replaceChildren(...(skip ? [sign(cell.textContent.slice(0, skip))] : []), line)
    })
}

for (const table of document.querySelectorAll("table.diff[data-file]")) {
    const lang = await language(table.dataset.file)
    if (!lang) continue
    const rows = [...table.querySelectorAll("tr[data-line]")]
    const text = row => row.cells[2]
    // The kept and added lines are the new file; the removed ones are coloured as the old file reads them, kept lines between.
    colour(rows.filter(it => !it.classList.contains("del")).map(text), lang, 1)
    const old = rows.filter(it => !it.classList.contains("add"))
    paint(old.map(it => text(it).textContent.slice(1)), lang).forEach((line, i) => {
        if (old[i].classList.contains("del")) text(old[i]).replaceChildren(sign("-"), line)
    })
    table.classList.add("hl")
}

for (const code of document.querySelectorAll(".code[data-file]")) {
    const lang = await language(code.dataset.file)
    if (!lang) continue
    // The step's side, and apart from it the other side's lines, which only the diff shows.
    colour([...code.querySelectorAll(".cl:not(.extra) .tx")], lang)
    colour([...code.querySelectorAll(".cl.extra .tx")], lang)
    code.classList.add("hl")
}
