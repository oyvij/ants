// The modeler page: camunda-bpmn-js with the palette trimmed to what Ants runs, "append Agent / User / Built-in Task"
// on the context pad, a panel that explains the selected element, and Save and deploy through Ants' deploy path.
// Each kind of element is one entry in `kinds`; a later panel for a kind (the Agent Task's Agent and Skill, a
// gateway's conditions) is a function in `panels`.

const OPERATON = "http://operaton.org/schema/1.0/bpmn"
const CAMUNDA = "http://camunda.org/schema/1.0/bpmn"

// What Ants calls each element it runs, and what it does there: the palette's tooltip and the panel's explanation.
const kinds = {
    process: {name: "Process", explain: "How one kind of job gets done. Every Run of it starts at its start event with the start form's fields."},
    start: {name: "Start event", explain: "Where a Run begins. A Process has exactly one."},
    "timer-start": {name: "Timer start event", explain: "Where a Run begins; the Process's Schedule says when it starts on its own."},
    end: {name: "End event", explain: "Where a path ends. The Run ends when every path has."},
    timer: {name: "Timer", explain: "Waits for a time or a duration, then goes on."},
    "timer-boundary": {name: "Timer boundary", explain: "On a User Task only: when it fires, the user's Task is cancelled and the Run takes this path instead, such as Accept recommendations."},
    exclusive: {name: "Exclusive gateway", explain: "Takes the one path whose condition holds, else the default path. Loops are drawn with it."},
    parallel: {name: "Parallel gateway", explain: "Fans out to every path at once, or waits for all of them to join."},
    agent: {name: "Agent Task", explain: "An Agent runs one Skill through its AI CLI and hands back a Result, which later Tasks read under this Task's id."},
    user: {name: "User Task", explain: "Waits on the Notification list until you act on it, such as answering a Round or approving."},
    "built-in": {name: "Built-in Task", explain: "Ants does this itself, with no Agent and no user."},
    task: {name: "Task", explain: "A plain Task does nothing. What should it be?"},
    subprocess: {name: "Sub-process", explain: "Groups Tasks into one step, which can run as a multi-instance."},
    call: {name: "Call activity", explain: "Runs another Process as one step, inside this Run; its Result is every Task Result of the called Process."},
    annotation: {name: "Text annotation", explain: "A note on the diagram. Ants ignores it."},
    group: {name: "Group", explain: "A frame around elements that belong together. Ants ignores it."},
    flow: {name: "Sequence flow", explain: "The path a Run takes from one element to the next."},
}

// The Built-in Tasks, by the topic Ants carries them out on.
const builtIns = {
    "clone-repos": "Clone repos: clones the named repos into the Run workspace on the work branch.",
    "accept-recommendations": "Accept recommendations: answers a Round with every recommendation.",
    push: "Push: waits for you to push the work branch of every repo.",
}

// The three Tasks Ants runs, as the BPMN each one is.
const tasks = {
    agent: {type: "bpmn:ServiceTask", attrs: {"camunda:type": "external", "camunda:topic": "agent"}, icon: "bpmn-icon-service-task"},
    user: {type: "bpmn:UserTask", attrs: {}, icon: "bpmn-icon-user-task"},
    "built-in": {type: "bpmn:ServiceTask", attrs: {"camunda:type": "external", "camunda:topic": "clone-repos"}, icon: "bpmn-icon-screw-wrench"},
}

function kindOf(element) {
    const bo = element.businessObject
    const definitions = bo.eventDefinitions || []
    const timer = definitions.length === 1 && definitions[0].$type === "bpmn:TimerEventDefinition"
    switch (bo.$type) {
        case "bpmn:Process": return "process"
        case "bpmn:StartEvent": return timer ? "timer-start" : definitions.length ? null : "start"
        case "bpmn:EndEvent": return definitions.length ? null : "end"
        case "bpmn:IntermediateCatchEvent": return timer ? "timer" : null
        case "bpmn:BoundaryEvent": return timer ? "timer-boundary" : null
        case "bpmn:ExclusiveGateway": return "exclusive"
        case "bpmn:ParallelGateway": return "parallel"
        case "bpmn:ServiceTask": return bo.get("camunda:type") !== "external" ? null : bo.get("camunda:topic") === "agent" ? "agent" : "built-in"
        case "bpmn:UserTask": return "user"
        case "bpmn:Task": return "task"
        case "bpmn:SubProcess": return bo.triggeredByEvent ? null : "subprocess"
        case "bpmn:CallActivity": return "call"
        case "bpmn:TextAnnotation": return "annotation"
        case "bpmn:Group": return "group"
        case "bpmn:SequenceFlow": return "flow"
        default: return null
    }
}

const LAST = 1 // the lowest priority: Ants' providers see, and trim, what every other provider offered

function taskShape(elementFactory, bpmnFactory, kind) {
    const {type, attrs} = tasks[kind]
    return elementFactory.createShape({type, businessObject: bpmnFactory.create(type, {...attrs})})
}

function AntsPalette(palette, create, elementFactory, bpmnFactory) {
    palette.registerProvider(LAST, this)
    const creating = (group, className, kind, shape) => ({
        group, className, title: `${kinds[kind].name}: ${kinds[kind].explain}`,
        action: {dragstart: event => create.start(event, shape()), click: event => create.start(event, shape())},
    })
    const shape = attrs => () => elementFactory.createShape(attrs)
    this.getPaletteEntries = () => entries => ({
        "hand-tool": entries["hand-tool"],
        "lasso-tool": entries["lasso-tool"],
        "space-tool": entries["space-tool"],
        "global-connect-tool": entries["global-connect-tool"],
        "tool-separator": entries["tool-separator"],
        "create.start-event": creating("event", "bpmn-icon-start-event-none", "start", shape({type: "bpmn:StartEvent"})),
        "create.timer": creating("event", "bpmn-icon-intermediate-event-catch-timer", "timer",
            shape({type: "bpmn:IntermediateCatchEvent", eventDefinitionType: "bpmn:TimerEventDefinition"})),
        "create.end-event": creating("event", "bpmn-icon-end-event-none", "end", shape({type: "bpmn:EndEvent"})),
        "create.exclusive-gateway": creating("gateway", "bpmn-icon-gateway-xor", "exclusive", shape({type: "bpmn:ExclusiveGateway"})),
        "create.parallel-gateway": creating("gateway", "bpmn-icon-gateway-parallel", "parallel", shape({type: "bpmn:ParallelGateway"})),
        ...Object.fromEntries(Object.entries(tasks).map(([kind, {icon}]) =>
            [`create.${kind}-task`, creating("activity", icon, kind, () => taskShape(elementFactory, bpmnFactory, kind))])),
        "create.subprocess-expanded": {...entries["create.subprocess-expanded"], title: `${kinds.subprocess.name}: ${kinds.subprocess.explain}`},
        "create.call-activity": creating("activity", "bpmn-icon-call-activity", "call", shape({type: "bpmn:CallActivity"})),
        "create.text-annotation": creating("artifact", "bpmn-icon-text-annotation", "annotation", shape({type: "bpmn:TextAnnotation"})),
        "create.group": creating("artifact", "bpmn-icon-group", "group", shape({type: "bpmn:Group"})),
    })
}
AntsPalette.$inject = ["palette", "create", "elementFactory", "bpmnFactory"]

// The hover menu: "append Agent / User / Built-in Task" where bpmn-js offers to append a Task, and nothing Ants
// doesn't run, such as a plain Task, any intermediate event or the "append anything" menu.
function AntsContextPad(contextPad, autoPlace, create, elementFactory, bpmnFactory) {
    contextPad.registerProvider(LAST, this)
    this.getContextPadEntries = () => entries => {
        if (!entries["append.append-task"]) return entries
        delete entries["append.append-task"]
        delete entries["append.intermediate-event"]
        delete entries.append
        for (const [kind, {icon}] of Object.entries(tasks)) {
            entries[`append.${kind}-task`] = {
                group: "model", className: icon, title: `Append ${kinds[kind].name}`,
                action: {
                    click: (event, element) => autoPlace.append(element, taskShape(elementFactory, bpmnFactory, kind)),
                    dragstart: (event, element) => create.start(event, taskShape(elementFactory, bpmnFactory, kind), {source: element}),
                },
            }
        }
        return entries
    }
}
AntsContextPad.$inject = ["contextPad", "autoPlace", "create", "elementFactory", "bpmnFactory"]

// The create (N), append (A) and replace (wrench) menus offer only elements Ants runs, by their entry ids without the
// `create-`, `append-` or `replace-with-` prefix. Agent and Built-in Tasks come from the palette and the hover menu.
const offered = new Set([
    "none-start-event", "none-start", "timer-start", "none-end-event", "none-end", "timer-intermediate-catch", "timer-boundary",
    "exclusive-gateway", "parallel-gateway", "user-task", "call-activity", "expanded-subprocess", "subprocess", "collapsed-subprocess",
    "sequence-flow", "default-flow", "conditional-flow",
])

function AntsPopupMenus(popupMenu) {
    const provider = {
        getPopupMenuEntries: () => entries => Object.fromEntries(Object.entries(entries)
            .filter(([id]) => offered.has(id.replace(/^(create|append|replace-with)-/, "")))),
    }
    // 950: before Camunda's grouping (900) nests the entries.
    for (const menu of ["bpmn-create", "bpmn-append", "bpmn-replace"]) popupMenu.registerProvider(menu, 950, provider)
}
AntsPopupMenus.$inject = ["popupMenu"]

// The palette's Templates section: a searchable list of the Templates, and picking one drops a copy of it as a
// collapsed sub-process in the middle of the view. Ants does the drop (keeping the Template's ids, suffixed only on a
// clash) and the page loads the result.
function AntsTemplates(palette, popupMenu, canvas) {
    let templates = []
    popupMenu.registerProvider("ants-templates", {
        getPopupMenuEntries: () => Object.fromEntries(templates.map(({name, title, shipped}) => [`template-${name}`, {
            label: title, description: name + (shipped ? " (shipped)" : ""), className: "bpmn-icon-subprocess-collapsed",
            action: () => dropTemplate(name),
        }])),
    })
    palette.registerProvider(LAST - 1, {
        getPaletteEntries: () => entries => ({
            ...entries,
            "templates-separator": {group: "templates", separator: true},
            "create.template": {
                group: "templates", className: "bpmn-icon-subprocess-collapsed", title: "Templates: drop a copy of a Template as a collapsed sub-process",
                action: {
                    click: async event => {
                        templates = await (await fetch("/templates.json")).json()
                        const box = event.target.getBoundingClientRect()
                        popupMenu.open(canvas.getRootElement(), "ants-templates", {x: box.right + 10, y: box.top},
                            {title: "Templates", search: true, width: 300})
                    },
                },
            },
        }),
    })
}
AntsTemplates.$inject = ["palette", "popupMenu", "canvas"]

// ponytail: the drop replaces the whole diagram (importXML), so Cmd+Z can't undo it and it always lands in the
// Process's own plane; building the shapes through `modeling` would keep undo but means the id work again in JS.
async function dropTemplate(name) {
    const canvas = modeler.get("canvas")
    const viewbox = canvas.viewbox()
    const {xml} = await modeler.saveXML()
    const centre = {x: viewbox.x + viewbox.width / 2, y: viewbox.y + viewbox.height / 2}
    const response = await fetch(`/templates/${name}/drop`, {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml, ...centre})})
    if (!response.ok) return document.getElementById("refusal").textContent = `The Template ${name} can't be dropped here.`
    const {bpmn, element} = await response.json()
    await modeler.importXML(bpmn.replaceAll(OPERATON, CAMUNDA))
    canvas.viewbox(viewbox)
    modeler.get("selection").select(modeler.get("elementRegistry").get(element))
}

const modeler = new BpmnModeler({
    container: "#canvas",
    elementTemplateChooser: false,
    additionalModules: [{
        __init__: ["antsPalette", "antsContextPad", "antsPopupMenus"],
        antsPalette: ["type", AntsPalette],
        antsContextPad: ["type", AntsContextPad],
        antsPopupMenus: ["type", AntsPopupMenus],
    }, {__init__: ["antsTemplates"], antsTemplates: ["type", AntsTemplates]}],
})
const canvas = document.getElementById("canvas")
const panel = document.getElementById("explain")
const csrf = document.querySelector("input[name=_csrf]").value

function el(tag, props = {}, ...children) {
    const node = Object.assign(document.createElement(tag), props)
    node.append(...children)
    return node
}

// What the panel adds under the explanation for a kind, beyond saying what it is.
const panels = {
    task: element => el("p", {}, ...Object.keys(tasks).map(kind => el("button", {
        textContent: kinds[kind].name,
        onclick: () => {
            const replaced = modeler.get("bpmnReplace").replaceElement(element, {type: tasks[kind].type})
            modeler.get("modeling").updateProperties(replaced, tasks[kind].attrs)
            modeler.get("selection").select(replaced)
        },
    }))),
    "built-in": element => {
        const topic = element.businessObject.get("camunda:topic")
        const select = el("select", {onchange: () => modeler.get("modeling").updateProperties(element, {"camunda:topic": select.value})},
            ...Object.entries(builtIns).map(([value, text]) => el("option", {value, textContent: text.split(":")[0], selected: value === topic})))
        return el("div", {}, el("label", {}, "Which ", select), el("p", {textContent: builtIns[topic] || "Ants has no Built-in Task " + topic}))
    },
}

// The start form's field types, by the `ants.type` property each field carries (the engine only knows `string`).
const fieldTypes = {text: "Text", markdown: "Markdown", repos: "Repos"}

// A field's variable name, derived from its label: "Work branch" is workBranch.
const camel = label => label.toLowerCase().replace(/^[^a-z]+/, "").replace(/[^a-z0-9]+(.)?/g, (_, c) => c ? c.toUpperCase() : "")

// True while the panel writes the diagram itself, so it isn't redrawn under the user's cursor.
let quiet = false

// The start event's `camunda:formData` fields as plain objects, and back.
function readForm(bo) {
    const data = (bo.extensionElements?.values || []).find(value => value.$type === "camunda:FormData")
    return (data?.fields || []).map(field => {
        const props = Object.fromEntries((field.properties?.values || []).map(p => [p.id, p.value]))
        return {
            id: field.id || "", label: field.label || "", type: props["ants.type"] || "text", help: props["ants.help"] || "",
            stored: props["ants.stored"] || "", required: (field.validation?.constraints || []).some(c => c.name === "required"),
        }
    })
}

function writeForm(element, fields) {
    const factory = modeler.get("bpmnFactory")
    const field = f => {
        const props = [["ants.type", f.type], ["ants.help", f.help], ["ants.stored", f.stored]].filter(([, value]) => value)
        const attrs = {id: f.id, label: f.label, type: "string"}
        if (props.length) attrs.properties = factory.create("camunda:Properties", {values: props.map(([id, value]) => factory.create("camunda:Property", {id, value}))})
        if (f.required) attrs.validation = factory.create("camunda:Validation", {constraints: [factory.create("camunda:Constraint", {name: "required"})]})
        return factory.create("camunda:FormField", attrs)
    }
    const others = (element.businessObject.extensionElements?.values || []).filter(value => value.$type !== "camunda:FormData")
    const values = fields.length ? [...others, factory.create("camunda:FormData", {fields: fields.map(field)})] : others
    quiet = true
    try {
        modeler.get("modeling").updateProperties(element, {extensionElements: factory.create("bpmn:ExtensionElements", {values})})
    } finally {
        quiet = false
    }
}

// The start form: each field's label, variable name, type, required, help and stored value, in order.
function startForm(element) {
    const fields = readForm(element.businessObject)
    const save = () => writeForm(element, fields)
    const redraw = () => { save(); explain(element) }
    const input = (f, key, props = {}) => el("input", {value: f[key], ...props, oninput: event => { f[key] = event.target.value; save() }})
    return el("div", {},
        el("h3", {textContent: "Start form"}),
        ...fields.map((f, i) => {
            const name = input(f, "id")
            const label = input(f, "label", {
                oninput: event => {
                    if (f.id === camel(f.label)) name.value = f.id = camel(event.target.value) // derived until the user renames it
                    f.label = event.target.value
                    save()
                },
            })
            const type = el("select", {onchange: () => { f.type = type.value; save() }},
                ...Object.entries(fieldTypes).map(([value, text]) => el("option", {value, textContent: text, selected: value === f.type})))
            const move = (to, text) => el("button", {textContent: text, disabled: !fields[to], onclick: () => { [fields[i], fields[to]] = [fields[to], fields[i]]; redraw() }})
            return el("fieldset", {},
                el("label", {}, "Label ", label), el("br"),
                el("label", {}, "Variable ", name), el("br"),
                el("label", {}, "Type ", type), " ",
                el("label", {}, el("input", {type: "checkbox", checked: f.required, onchange: event => { f.required = event.target.checked; save() }}), " Required"), el("br"),
                el("label", {}, "Help ", input(f, "help")), el("br"),
                el("label", {title: "Used by the Schedule; Repos as names separated by commas"}, "Stored value ", input(f, "stored")), el("br"),
                move(i - 1, "Up"), move(i + 1, "Down"),
                el("button", {textContent: "Remove", onclick: () => { fields.splice(i, 1); redraw() }}))
        }),
        el("button", {textContent: "Add field", onclick: () => { fields.push({id: "", label: "", type: "text", help: "", stored: "", required: false}); redraw() }}))
}
panels.start = startForm
panels["timer-start"] = startForm

function explain(element) {
    const target = element.labelTarget || element
    const kind = kindOf(target)
    panel.replaceChildren(kind
        ? el("div", {}, el("h2", {textContent: kinds[kind].name}), el("p", {textContent: kinds[kind].explain}), panels[kind]?.(target) || "")
        : el("div", {}, el("h2", {textContent: target.type.replace("bpmn:", "")}), el("p", {textContent: "Ants doesn't run this element."})))
}

modeler.on("selection.changed", ({newSelection}) => explain(newSelection.length === 1 ? newSelection[0] : modeler.get("canvas").getRootElement()))
modeler.on("element.changed", ({element}) => {
    if (quiet) return
    const selection = modeler.get("selection").get()
    if (selection.length === 1 && selection[0] === element) explain(element)
})

fetch(canvas.dataset.src)
    .then(response => response.text())
    // camunda-bpmn-js reads and writes camunda:; Ants' deploy path turns it back into operaton:.
    .then(xml => modeler.importXML(xml.replaceAll(OPERATON, CAMUNDA)))
    .then(() => {
        modeler.get("canvas").zoom("fit-viewport")
        explain(modeler.get("canvas").getRootElement())
    })

document.getElementById("save").onclick = async () => {
    const {xml} = await modeler.saveXML({format: true})
    const response = await fetch(document.getElementById("save").dataset.action, {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml})})
    if (response.ok) location = response.url
    else document.getElementById("refusal").textContent = await response.text()
}

// The live Checks: everything Save and deploy would refuse, from the Checks it runs, and the warnings. Save and deploy
// is disabled while any Check fails; clicking a Check selects its element, inside a collapsed sub-process too.
const save = document.getElementById("save")
const checks = document.getElementById("checks")

function jump(id) {
    const element = modeler.get("elementRegistry").get(id)
    if (!element?.parent) return // the Process itself, or gone
    const canvas = modeler.get("canvas")
    canvas.setRootElement(canvas.findRoot(element))
    canvas.scrollToElement(element)
    modeler.get("selection").select(element)
}

async function check() {
    const {xml} = await modeler.saveXML()
    const found = await (await fetch("/processes/checks", {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml})})).json()
    save.disabled = found.some(item => !item.warning)
    save.title = save.disabled ? "Fix the Checks first" : ""
    checks.replaceChildren(...found.map(item => el("li", {className: item.warning ? "warning" : "error"}, item.element
        ? el("a", {href: "#" + item.element, textContent: item.message, onclick: event => { event.preventDefault(); jump(item.element) }})
        : item.message)))
}

let pending
modeler.on("commandStack.changed", () => { clearTimeout(pending); pending = setTimeout(check, 300) })
modeler.on("import.done", check)
