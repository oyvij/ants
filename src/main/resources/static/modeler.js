// The modeler page: camunda-bpmn-js with the palette trimmed to what Ants runs, "append Agent / User / Built-in Task"
// on the context pad, a panel that explains the selected element and edits it, the live Checks under it, and Publish
// through Ants' deploy path. A Process's modeler autosaves the diagram to its Draft (draft.js), whoever changed it, the
// user or the Process Builder; a Template's has Save alone. Each kind of element is one entry in `kinds`; what its panel asks beyond its name
// and explanation (the Agent Task's Agent and Skill, a decision's paths) is a function in `panels`. What it writes as
// text, with no page, is in modeler-text.js.

import {autosave} from "/draft.js"
import {camel, linkedChanges, linkedProperties, listedSkills, operators, readCondition, resultFields, toCamunda, unary} from "/modeler-text.js"

// From the server (modelerFacts in Checks.kt): what Ants calls each element it runs and what it does there (the
// palette's tooltip and the panel's explanation), the Built-in Tasks by topic with their name and what each does, the start-form field types by the
// `ants.type` property each field carries (the engine only knows `string`), Items are Tickets' property and help, the
// Process's Profile property, help and empty choice, Ants' own Run variables, each User Task kind with its fields
// and Result, what the palette and add_element draw for each kind, the multi-instance modes, and the panel's own wording.
const {kinds, builtIns, fieldTypes, itemsAreTickets, profile, level, runVariables, userTasks, drawn, multiInstanceModes, wording} = JSON.parse(document.getElementById("canvas").dataset.facts)

// The three Tasks Ants runs, as the BPMN each one is, and their colour on the diagram and in the palette.
const tasks = {
    agent: {...drawn.agent, icon: "bpmn-icon-service-task ants-pal-agent", marker: "ants-agent"},
    user: {...drawn.user, icon: "bpmn-icon-user-task ants-pal-user", marker: "ants-user"},
    "built-in": {...drawn["built-in"], icon: "bpmn-icon-screw-wrench ants-pal-builtin", marker: "ants-builtin"},
    script: {...drawn.script, icon: "bpmn-icon-script-task ants-pal-script", marker: "ants-script"},
}

function kindOf(element) {
    const bo = element.businessObject
    const definitions = bo.eventDefinitions || []
    const timer = definitions.length === 1 && definitions[0].$type === "bpmn:TimerEventDefinition"
    switch (bo.$type) {
        case "bpmn:Process": return "process"
        case "bpmn:StartEvent": return definitions.length ? null : "start"
        case "bpmn:EndEvent": return definitions.length ? null : "end"
        case "bpmn:IntermediateCatchEvent": return timer ? "timer" : null
        case "bpmn:BoundaryEvent": return timer ? "timer-boundary" : null
        case "bpmn:ExclusiveGateway": return "exclusive"
        case "bpmn:ParallelGateway": return "parallel"
        case "bpmn:ServiceTask": return bo.get("camunda:type") !== "external" ? null : {agent: "agent", script: "script"}[bo.get("camunda:topic")] || "built-in"
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
        "create.start-event": creating("event", "bpmn-icon-start-event-none", "start", shape(drawn.start)),
        "create.timer": creating("event", "bpmn-icon-intermediate-event-catch-timer", "timer",
            shape(drawn.timer)),
        "create.end-event": creating("event", "bpmn-icon-end-event-none", "end", shape(drawn.end)),
        "create.exclusive-gateway": creating("gateway", "bpmn-icon-gateway-xor", "exclusive", shape(drawn.exclusive)),
        "create.parallel-gateway": creating("gateway", "bpmn-icon-gateway-parallel", "parallel", shape(drawn.parallel)),
        "create.subprocess-expanded": {...entries["create.subprocess-expanded"], title: `${kinds.subprocess.name}: ${kinds.subprocess.explain}`},
        "create.call-activity": creating("activity", "bpmn-icon-call-activity", "call", shape(drawn.call)),
        "create.text-annotation": creating("artifact", "bpmn-icon-text-annotation", "annotation", shape(drawn.annotation)),
        "create.group": creating("artifact", "bpmn-icon-group", "group", shape(drawn.group)),
        // The work, at the bottom of the palette.
        "ants-separator": {group: "ants", separator: true},
        ...Object.fromEntries(Object.entries(tasks).map(([kind, {icon}]) =>
            [`create.${kind}-task`, creating("ants", icon, kind, () => taskShape(elementFactory, bpmnFactory, kind))])),
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
    "none-start-event", "none-start", "none-end-event", "none-end", "timer-intermediate-catch", "timer-boundary",
    "exclusive-gateway", "parallel-gateway", "user-task", "call-activity", "expanded-subprocess", "subprocess", "collapsed-subprocess",
    "sequence-flow", "default-flow", "conditional-flow",
])

// The kinds Ants runs as a multi-instance, whose wrench menu keeps the parallel and sequential toggles.
const repeatable = new Set(["agent", "subprocess", "call"])

function AntsPopupMenus(popupMenu) {
    const provider = {
        getPopupMenuEntries: () => entries => Object.fromEntries(Object.entries(entries)
            .filter(([id]) => offered.has(id.replace(/^(create|append|replace-with)-/, "")))),
        // Never a standard loop, which Ants doesn't run: a loop is drawn with an exclusive gateway.
        getPopupMenuHeaderEntries: target => entries => Object.fromEntries(Object.entries(entries)
            .filter(([id]) => id !== "toggle-loop" && (repeatable.has(target?.businessObject && kindOf(target)) || !id.endsWith("-mi")))),
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
            action: () => dropTemplate(name).catch(error => document.getElementById("refusal").textContent = error.message),
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
// Drops it centred on [centre], else in the middle of the view; the dropped sub-process's id.
async function dropTemplate(name, centre) {
    const canvas = modeler.get("canvas")
    const viewbox = canvas.viewbox()
    const {xml} = await modeler.saveXML()
    centre ||= {x: viewbox.x + viewbox.width / 2, y: viewbox.y + viewbox.height / 2}
    const response = await fetch(`/templates/${encodeURIComponent(name)}/drop`, {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml, ...centre})})
    if (!response.ok) throw new Error(`The Template ${name} can't be dropped here.`)
    const {bpmn, element} = await response.json()
    await modeler.importXML(toCamunda(bpmn))
    canvas.viewbox(viewbox)
    modeler.get("selection").select(modeler.get("elementRegistry").get(element))
    return element
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
canvas.canvas = modeler.get("canvas") // the diagram box's zoom buttons (shell.js)
const panel = document.getElementById("explain")
const csrf = document.querySelector("input[name=_csrf]").value

function el(tag, props = {}, ...children) {
    const node = Object.assign(document.createElement(tag), props)
    node.append(...children)
    return node
}

let ids = 0

// A field as every form lays one out: its label, a help line, then the control (labelled by it) and anything after.
function field(label, help, control, ...after) {
    control.id ||= "field-" + ++ids
    return el("div", {className: "fld"}, el("label", {className: "f", htmlFor: control.id, textContent: label}),
        help ? el("div", {className: "help", textContent: help}) : "", control, ...after)
}

const note = (className, text) => el("div", {className, textContent: text})

// The element's name, or what Ants calls its kind.
const nameOf = element => element.businessObject.name || kinds[kindOf(element)]?.name || element.type.replace("bpmn:", "")

const selectElement = element => modeler.get("selection").select(element)

// Makes [element] an Agent, User or Built-in Task, or a decision or parallel gateway ([kind] null).
function replace(element, type, kind) {
    const replaced = modeler.get("bpmnReplace").replaceElement(element, {type})
    if (kind) modeler.get("modeling").updateProperties(replaced, tasks[kind].attrs)
    selectElement(replaced)
}

// What a plain Task, or any Task Ants doesn't run, can become: one big button per Task Ants runs.
const taskButtons = element => Object.entries(tasks).map(([kind, {type, icon}]) => el("button", {className: "kindbtn", onclick: () => replace(element, type, kind)},
    el("span", {className: icon}), el("span", {}, el("b", {textContent: kinds[kind].name}), el("br"), el("span", {className: "muted small", textContent: kinds[kind].explain}))))

// What the panel adds under the explanation for a kind, beyond saying what it is.
const panels = {
    task: taskButtons,
    "built-in": element => {
        const topic = element.businessObject.get("camunda:topic")
        const which = el("select", {onchange: () => modeler.get("modeling").updateProperties(element, {"camunda:topic": which.value})},
            ...Object.entries(builtIns).map(([value, {name}]) => el("option", {value, textContent: name, selected: value === topic})))
        return field("What Ants does", builtIns[topic]?.does || "Ants has no Built-in Task " + topic, which)
    },
}

// True while the panel writes the diagram itself, so it isn't redrawn under the user's cursor.
let quiet = false

// The start event's `camunda:formData` fields as plain objects, and back.
function readForm(bo) {
    const data = (bo.extensionElements?.values || []).find(value => value.$type === "camunda:FormData")
    return (data?.fields || []).map(field => {
        const props = Object.fromEntries((field.properties?.values || []).map(p => [p.id, p.value]))
        return {
            id: field.id || "", label: field.label || "", type: props["ants.type"] || "text", help: props["ants.help"] || "",
            example: props["ants.example"] || "", stored: props["ants.stored"] || "", required: (field.validation?.constraints || []).some(c => c.name === "required"),
        }
    })
}

function writeForm(element, fields) {
    const factory = modeler.get("bpmnFactory")
    const field = f => {
        const props = [["ants.type", f.type], ["ants.help", f.help], ["ants.example", f.example], ["ants.stored", f.stored]].filter(([, value]) => value)
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
    return el("div", {className: "fld"},
        el("label", {className: "f", textContent: "Start form"}),
        note("help", "What you fill in to start a Run. Tasks read each field by its variable name, as ${name}."),
        ...fields.map((f, i) => {
            const name = input(f, "id", {placeholder: "e.g. prompt"})
            const label = input(f, "label", {placeholder: "e.g. Prompt",
                oninput: event => {
                    if (f.id === camel(f.label)) name.value = f.id = camel(event.target.value) // derived until the user renames it
                    f.label = event.target.value
                    save()
                },
            })
            const type = el("select", {onchange: () => { f.type = type.value; save() }},
                ...Object.entries(fieldTypes).map(([value, text]) => el("option", {value, textContent: text, selected: value === f.type})))
            const move = (to, text) => el("button", {textContent: text, disabled: !fields[to], onclick: () => { [fields[i], fields[to]] = [fields[to], fields[i]]; redraw() }})
            return el("div", {className: "card"},
                el("label", {}, "Label ", label), el("br"),
                el("label", {}, "Variable ", name), el("br"),
                el("label", {}, "Type ", type), " ",
                el("label", {}, el("input", {type: "checkbox", checked: f.required, onchange: event => { f.required = event.target.checked; save() }}), " Required"), el("br"),
                el("label", {}, "Help ", input(f, "help", {placeholder: "e.g. What the Run should build"})), el("br"),
                el("label", {}, "Example ", input(f, "example", {placeholder: "e.g. Add a dark mode toggle"})), el("br"),
                el("label", {title: "Prefills the start form; Repos as names separated by commas"}, "Stored value ", input(f, "stored", {placeholder: "e.g. api, web"})), el("br"),
                move(i - 1, "Up"), move(i + 1, "Down"),
                el("button", {textContent: "Remove", onclick: () => { fields.splice(i, 1); redraw() }}))
        }),
        el("button", {textContent: "＋ Add field", onclick: () => { fields.push({id: "", label: "", type: "text", help: "", example: "", stored: "", required: false}); redraw() }}))
}
panels.start = startForm

// A multi-instance's collection and element variable, under the panel of an element that runs as one.
function multiInstance(element) {
    const loop = element.businessObject.loopCharacteristics
    if (loop?.$type !== "bpmn:MultiInstanceLoopCharacteristics") return ""
    const field = (attr, placeholder) => el("input", {
        value: loop.get(attr) || "", placeholder,
        oninput: event => {
            quiet = true
            try {
                modeler.get("modeling").updateModdleProperties(element, loop, {[attr]: event.target.value || undefined})
            } finally {
                quiet = false
            }
        },
    })
    return el("div", {},
        el("h3", {textContent: (loop.isSequential ? "Sequential" : "Parallel") + " multi-instance"}),
        el("p", {textContent: "One instance per item of a list from an earlier Result. Each instance reads its item under the element variable, and the Result is a list in instance order: of Results for a Task, of {taskId: Result} for a sub-process or call activity."}),
        el("label", {}, "Collection ", field("camunda:collection", "${totickets.output.tickets}")), el("br"),
        el("label", {}, "Element variable ", field("camunda:elementVariable", "item")),
        el("p", {}, el("label", {title: itemsAreTickets.help},
            el("input", {
                type: "checkbox", checked: agentProperties(element.businessObject)[itemsAreTickets.property] === "true",
                onchange: event => writeAgentTask(element, {[itemsAreTickets.property]: event.target.checked ? "true" : undefined}),
            }), " Items are Tickets")))
}

// An explanation box: [html] is the page's own words, never the diagram's.
const explainHtml = html => el("div", {className: "explain", innerHTML: html})

// The `${…}` references a picker on [element] offers, from the server: start-form fields, Run variables and the Results
// of the Tasks before it (`offered` in AgentTaskChecks.kt).
// The XML is read without saveXML, whose align-to-origin moves the shapes: a panel redrawn inside a move (that very
// align, when a shape sits above or left of the diagram) would move them again, and again, until the stack overflows.
async function offeredAt(element) {
    const {xml} = await modeler.get("moddle").toXML(modeler.getDefinitions())
    const response = await fetch("/processes/inputs", {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml, element: element.id})})
    return response.json()
}

// What a decision's condition can read: the start form's fields, the Results of the Tasks before [element], each with
// the Task it is from, and their run counts, Ants' own variables and a multi-instance's item.
async function fieldsBefore(element) {
    const registry = modeler.get("elementRegistry")
    const results = registry.filter(e => !e.labelTarget && ["agent", "user", "built-in", "script", "call", "subprocess"].includes(kindOf(e)))
    const startFields = registry.filter(e => e.type === "bpmn:StartEvent").flatMap(e => readForm(e.businessObject).map(field => field.id))
    return resultFields(await offeredAt(element), Object.fromEntries(results.map(e => [e.id, nameOf(e)])), new Set(results.filter(e => tasks[kindOf(e)]).map(e => e.id)),
        startFields, runVariables)
}

// A path's condition, the JUEL [body] (empty removes it), and making a path its decision's default, which drops its condition.
const writeCondition = (flow, body) => modeler.get("modeling").updateProperties(flow,
    {conditionExpression: body ? modeler.get("bpmnFactory").create("bpmn:FormalExpression", {body}) : undefined})

function writeDefault(gateway, flow) {
    writeCondition(flow, "")
    modeler.get("modeling").updateProperties(gateway, {default: flow.businessObject})
}

// The condition builder, for one path out of [gateway]: its label, whether it is the default, else a Result field from
// [fields], an operator and a value, which write the path's JUEL, shown under it. A condition it can't read back shows
// as is, and the builder replaces it once the user edits it.
function pathEditor(gateway, flow, fields) {
    const modeling = modeler.get("modeling")
    const isDefault = gateway.businessObject.default === flow.businessObject
    const body = flow.businessObject.conditionExpression?.body || ""
    const read = readCondition(body) || {field: "", op: "is true", value: ""}
    const shown = el("div", {className: "help mono", textContent: isDefault ? "(default: taken when no other condition matches)" : body || "(no condition yet)"})
    const missing = read.field && !fields.some(([path]) => path === read.field) ? [[read.field, "not before this decision"]] : []
    const field = el("select", {}, el("option", {value: "", textContent: "Choose a field…"}),
        ...[...missing, ...fields].map(([path, from]) => el("option", {value: path, textContent: `${path} (${from})`, selected: path === read.field})))
    const op = el("select", {}, ...Object.keys(operators).map(name => el("option", {value: name, textContent: name, selected: name === read.op})))
    const value = el("input", {value: read.value, placeholder: "e.g. redo"})
    const write = () => {
        value.hidden = unary.has(op.value)
        if (!field.value) return
        const expression = operators[op.value](field.value, value.value)
        shown.textContent = expression
        quiet = true
        try {
            writeCondition(flow, expression)
        } finally {
            quiet = false
        }
    }
    field.onchange = op.onchange = value.oninput = write
    value.hidden = unary.has(read.op)
    const makeDefault = el("input", {type: "radio", name: "default-" + gateway.id, checked: isDefault, onchange: () => {
        writeDefault(gateway, flow)
        explain(modeler.get("selection").get()[0] || gateway)
    }})
    const label = el("input", {value: flow.businessObject.name || "", placeholder: "e.g. yes", onchange: () => modeling.updateLabel(flow, label.value)})
    return el("div", {className: "card path"},
        el("div", {className: "row small"}, el("b", {className: "grow", textContent: "→ " + nameOf(flow.target)}),
            el("button", {className: "ghost small", textContent: "Select", onclick: () => selectElement(flow)})),
        el("label", {className: "cap"}, "Label on the diagram", label),
        el("label", {className: "row small"}, makeDefault, " Default path: taken when no other condition matches"),
        ...(isDefault ? [] : [el("label", {className: "cap"}, "Take this path when", field),
            el("div", {className: "row"}, el("label", {className: "cap"}, "Test", op), el("label", {className: "cap grow"}, "Value", value)),
            ...(fields.length ? [] : [note("warn", "No Task before this decision returns anything to decide on.")])]),
        shown)
}

panels.exclusive = gateway => {
    const out = gateway.outgoing.filter(flow => flow.type === "bpmn:SequenceFlow")
    const box = el("div", {}, out.length <= 1 && gateway.incoming.length > 1
        ? explainHtml("This decision only <b>merges</b> paths back into one, such as a loop's way back in. It needs no conditions.")
        : explainHtml("A fork in the road: the Run takes <b>exactly one</b> path out. Give each path a condition on a Result from an earlier Task, and make one the <b>default</b>, taken when nothing else matches."))
    if (!out.length) box.append(note("warn", "No paths out yet. Hover the decision and drag its arrow to the next step."))
    else fieldsBefore(gateway).then(fields => box.append(el("b", {className: "small", textContent: `Paths out (${out.length})`}), ...out.map(flow => pathEditor(gateway, flow, fields))))
    return box
}

panels.flow = flow => {
    const source = flow.source
    if (kindOf(source) !== "exclusive" || source.outgoing.length < 2) return note("explain", `This path needs no condition: when ${nameOf(source)} is done, the flow moves on.`)
    const box = el("div")
    fieldsBefore(source).then(fields => box.replaceChildren(pathEditor(source, flow, fields),
        el("button", {className: "ghost", textContent: `See every path out of ${nameOf(source)}`, onclick: () => selectElement(source)})))
    return box
}

panels.parallel = gateway => {
    const out = gateway.outgoing, split = out.length > 1, join = gateway.incoming.length > 1
    return el("div", {},
        explainHtml(split && !join ? `<b>Splits</b> the flow: all ${out.length} branches start at once. Their Agent Tasks each wait for a free slot.`
            : join && !split ? `<b>Joins</b> the branches: waits here until all ${gateway.incoming.length} have arrived, then carries on.`
                : "Use two of these: one to <b>split</b> into branches that run at the same time, one to <b>join</b> them, waiting until all are done."),
        ...(split ? [el("b", {className: "small", textContent: "Branches"}), ...out.map(flow => note("small branch", "→ " + nameOf(flow.target)))] : []))
}

// A timer waits for a duration, until a date, or until a cron time comes round (Operaton's six fields, seconds first).
const times = {
    timeDuration: {name: "Wait for", hint: "An ISO 8601 duration: PT30M is 30 minutes, P1D a day."},
    timeCycle: {name: "Wait until (cron)", hint: "sec min hour day month weekday: 0 0 8 * * * is the next 08:00, 0 0 8 * * MON-FRI the next weekday at 08:00."},
    timeDate: {name: "Wait until (date)", hint: "An ISO 8601 date and time, such as 2026-12-24T08:00:00."},
}

function timerPanel(element) {
    const definition = element.businessObject.eventDefinitions[0]
    const current = Object.keys(times).find(key => definition[key]) || "timeDuration"
    const kind = el("select", {}, ...Object.entries(times).map(([key, {name}]) => el("option", {value: key, textContent: name, selected: key === current})))
    const hint = el("p", {textContent: times[current].hint})
    const value = el("input", {value: definition[current]?.body || "", placeholder: "e.g. PT30M"})
    const write = () => {
        hint.textContent = times[kind.value].hint
        const properties = Object.fromEntries(Object.keys(times).map(key => [key, undefined]))
        if (value.value.trim()) properties[kind.value] = modeler.get("bpmnFactory").create("bpmn:FormalExpression", {body: value.value.trim()})
        quiet = true
        try {
            modeler.get("modeling").updateModdleProperties(element, definition, properties)
        } finally {
            quiet = false
        }
    }
    kind.onchange = value.oninput = write
    return el("div", {}, el("div", {className: "row"}, el("label", {className: "cap"}, "Wait", kind), el("label", {className: "cap grow"}, "Value", value)), hint)
}
panels.timer = timerPanel
panels["timer-boundary"] = timerPanel

// The Process: the Profile (a Vault and a Source map) its Runs use unless the start form picks another, as its
// Profile property. Offered once the settings have more than one Profile, or when the Process names one.
// Above it, how to draw a Process.
panels.process = element => {
    const box = el("div", {}, explainHtml(wording.drawing))
    fetch("/catalogue.json").then(response => response.json()).then(({profiles}) => {
        const current = agentProperties(element.businessObject)[profile.property] || ""
        if (profiles.length < 2 && !current) return
        const select = el("select", {onchange: () => writeAgentTask(element, {[profile.property]: select.value || undefined})},
            el("option", {value: "", textContent: profile.none, selected: !current}),
            ...[...new Set([...profiles, current].filter(Boolean))].map(name => el("option", {value: name, textContent: name, selected: name === current})))
        box.append(field("Profile", profile.help, select))
    })
    return box
}

// The Agent Task (ADR 0024): its Agent from a live dropdown, its Assignment, the Skills it may use of that Agent's (all,
// none or a ticked few), its inputs (whose pickers offer only start-form fields, Run variables and earlier Results),
// the Vault and Answer History toggles, resume, and its Result schema. All of it lives in the Task's
// `camunda:properties` (ants.*) and `camunda:inputOutput`.
function agentProperties(bo) {
    const properties = (bo.extensionElements?.values || []).find(value => value.$type === "camunda:Properties")
    return Object.fromEntries((properties?.values || []).map(p => [p.name, p.value]))
}

function agentInputs(bo) {
    const io = (bo.extensionElements?.values || []).find(value => value.$type === "camunda:InputOutput")
    return (io?.inputParameters || []).map(p => [p.name, p.value || ""])
}

// Writes [changes] into the Task's properties (undefined removes one) and, when given, replaces its inputs.
function writeAgentTask(element, changes, inputs, redraw = false) {
    const factory = modeler.get("bpmnFactory")
    const bo = element.businessObject
    const properties = Object.entries({...agentProperties(bo), ...changes}).filter(([, value]) => value !== undefined)
    const values = (bo.extensionElements?.values || []).filter(value => value.$type !== "camunda:Properties" && (!inputs || value.$type !== "camunda:InputOutput"))
    values.unshift(factory.create("camunda:Properties", {values: properties.map(([name, value]) => factory.create("camunda:Property", {name, value}))}))
    if (inputs?.length) values.push(factory.create("camunda:InputOutput", {inputParameters: inputs.map(([name, value]) => factory.create("camunda:InputParameter", {name, value}))}))
    quiet = !redraw
    try {
        modeler.get("modeling").updateProperties(element, {extensionElements: factory.create("bpmn:ExtensionElements", {values})})
    } finally {
        quiet = false
    }
}

// The page of an Agent, Skill or Script in a modal over the modeler. Edit opens [src], the item's page, and its Publish
// picks it on the Task once published. New posts [src], the kind's `new/draft`, which makes a Draft-only item as the
// pages' New does, and opens its page; its Use saves the page to the Draft and picks the item on the Task as it is.
function formModal(kind, src, use, who = "") {
    const editing = !src.startsWith(`/${kind}/new/draft`)
    const title = (editing ? "Edit " : "New ") + {agents: "Agent", skills: "Skill", scripts: "Script"}[kind]
    const frame = el("iframe", {title, className: "mb"})
    const named = () => frame.contentWindow.location.pathname.match(new RegExp(`^/${kind}/([^/]+)$`))?.[1]
    const primary = el("button", {type: "button", className: "primary", hidden: true, textContent: editing ? "Publish" : "Use", onclick: async () => {
        const form = frame.contentDocument.querySelector("input[name=use]")?.form
        if (editing) return form?.requestSubmit()
        await form?.draft?.save()
        dialog.close()
        use(decodeURIComponent(named()))
    }})
    const header = el("form", {method: "dialog", className: "mh"}, el("b", {className: `ent ent-${kind.slice(0, -1)}`, textContent: title}),
        el("span", {className: "muted small", textContent: "Esc to close"}), el("span", {className: "sp"}),
        el("button", {className: "ghost", title: "Close", textContent: "Cancel"}), primary)
    const dialog = el("dialog", {className: "modal create"}, header, who, frame)
    dialog.onclose = () => dialog.remove()
    frame.onload = () => {
        const name = named()
        if (name && new URLSearchParams(frame.contentWindow.location.search).has("saved")) {
            dialog.close()
            return use(decodeURIComponent(name))
        }
        // The form, or the form refusing a save.
        const page = frame.contentDocument
        const form = page.querySelector("input[name=use]")?.form
        primary.hidden = !form
        if (!form) return page.querySelector("nav.side")?.remove()
        page.querySelector(".ph")?.replaceWith(page.querySelector(".ph .sub") || "")
        form.querySelectorAll("button.primary:not([type=button])").forEach(button => button.hidden = true)
    }
    document.body.append(dialog)
    dialog.showModal()
    if (editing) return frame.src = src
    const [path, query] = src.split("?")
    fetch(path, {method: "POST", body: new URLSearchParams([...new URLSearchParams(query), ["_csrf", csrf]])}).then(response => frame.src = response.url)
}

// The marker of a Draft-only Agent, Skill or Script, or of one published with a Draft, as the lists show it (draftmark.kte).
const draftMark = item => item?.draft ? el("span", {className: `pill draft-mark ${item.draft.css}`, textContent: item.draft.label}) : ""

// [text] for a picker's option of [item], with its marker's words when it has one.
const marked = (text, item) => item?.draft ? `${text} (${item.draft.label})` : text

panels.agent = element => {
    const box = el("div", {}, el("p", {textContent: "Loading the Agents and Skills…"}))
    agentPanel(element, box)
    return box
}

panels.script = element => {
    const box = el("div", {}, el("p", {textContent: "Loading the Scripts…"}))
    scriptPanel(element, box)
    return box
}

async function scriptPanel(element, box) {
    const [catalogue, offered, read] = await Promise.all([fetch("/catalogue.json").then(response => response.json()), offeredAt(element), laterRead()])
    const bo = element.businessObject
    const name = linkedProperties(agentProperties(bo), catalogue)["ants.script"]
    const script = catalogue.scripts.find(s => s.name === name)
    const write = (changes, inputs) => writeAgentTask(element, linkedChanges(agentProperties(element.businessObject), changes, catalogue), inputs, true)
    const given = Object.fromEntries(agentInputs(bo))
    const pick = picked => {
        const declared = catalogue.scripts.find(s => s.name === picked)?.inputs || []
        write({"ants.script": picked || undefined}, declared.map(input => [input, given[input] || ""]))
    }
    const options = [el("option", {value: "", textContent: "Choose…", selected: !name}),
        ...(name && !script ? [el("option", {value: name, textContent: `${name} (not found)`, selected: true})] : []),
        ...catalogue.scripts.map(s => el("option", {value: s.name, textContent: marked([s.name, s.description].filter(Boolean).join(" · "), s), selected: s.name === name}))]
    const select = el("select", {onchange: () => pick(select.value)}, ...options)
    const edit = script ? el("button", {type: "button", className: "ghost small", textContent: "Edit", onclick: () =>
        formModal("scripts", "/scripts/" + encodeURIComponent(script.name) + "?use", () => scriptPanel(element, box).then(check))}) : ""
    const create = el("button", {type: "button", className: "ghost small", textContent: "＋ New Script", onclick: () =>
        formModal("scripts", "/scripts/new/draft?use", async picked => {
            Object.assign(catalogue, await (await fetch("/catalogue.json")).json())
            pick(picked)
        })})
    const references = el("datalist", {id: "ants-script-references"}, ...offered.map(value => el("option", {value})))
    const names = [...(script?.inputs || []), ...Object.keys(given).filter(input => !script?.inputs.includes(input))]
    const rows = names.map(input => {
        const field = el("input", {id: "ants-script-input-" + input, className: "mono grow", value: given[input] ?? "", placeholder: "e.g. ${prompt}", oninput: () => {
            const all = agentInputs(element.businessObject).filter(([n]) => n !== input)
            writeAgentTask(element, agentProperties(element.businessObject), [...all, [input, field.value]])
        }})
        field.setAttribute("list", references.id)
        const undeclared = !script?.inputs.includes(input)
        const remove = undeclared ? el("button", {type: "button", className: "ghost small", textContent: "×", title: `Remove ${input}`,
            onclick: () => write({}, agentInputs(element.businessObject).filter(([n]) => n !== input))}) : ""
        const about = script?.about?.[input]
        const row = el("div", {className: "row"}, el("label", {htmlFor: field.id}, el("code", {textContent: input})), field,
            undeclared ? el("span", {className: "muted small", textContent: "not declared by the Script"}) : "",
            about ? el("span", {className: "muted small nowrap", textContent: [about.type, about.required ? "required" : ""].filter(Boolean).join(" · ")}) : "", remove)
        const entry = el("div", {}, row, about?.description ? note("help", about.description) : "")
        entry.dataset.input = input
        return entry
    })
    box.replaceChildren(
        field("Script", "What it runs, from your Scripts.", select, draftMark(script), edit, create),
        el("div", {className: "fld"}, el("label", {className: "f", textContent: "Inputs"}),
            note("help", "What the Script reads as ants.inputs, each from start-form fields, Run variables or Results of Tasks before it, as ${…}."), references,
            ...(rows.length ? rows : [note("muted small", script ? "The Script declares no inputs." : "Pick a Script to see its inputs.")])),
        el("div", {className: "help"}, "Later Tasks can read: ", ...(read[element.id] || []).flatMap(reference => [el("code", {textContent: reference}), " "])),
    )
}

async function laterRead() {
    const {xml} = await modeler.get("moddle").toXML(modeler.getDefinitions())
    return (await fetch("/processes/outputs", {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml})})).json()
}

// The Skill a Task starts with as its job: its one listed Skill, when it has no Assignment.
function startingSkill(props, skills) {
    const listed = listedSkills(props)
    return !props["ants.assignment"]?.trim() && listed?.length === 1 ? skills.find(s => s.name === listed[0]) : undefined
}

// A Task's list of Skills once it gets the Agent [agent], and the Skills dropped from it (ADR 0024): a new Agent drops
// the listed Skills it lacks, and a Task with neither an Assignment nor a list picks the Agent's first. [listed] as it
// was, the same array, when nothing changes.
function newAgentSkills(listed, assignment, agent) {
    if (listed === null && !assignment?.trim()) return [agent?.skills.length ? agent.skills.slice(0, 1) : null, []]
    const dropped = listed && agent ? listed.filter(s => !agent.skills.includes(s)) : []
    return [dropped.length ? listed.filter(s => agent.skills.includes(s)) : listed, dropped]
}

// What the panel says once it is drawn again, by element id: the Skills a new Agent dropped.
const agentNotes = {}

async function agentPanel(element, box) {
    const [catalogue, offered, read] = await Promise.all([fetch("/catalogue.json").then(response => response.json()), offeredAt(element), laterRead()])
    const refresh = async () => Object.assign(catalogue, await (await fetch("/catalogue.json")).json())
    const bo = element.businessObject
    const props = linkedProperties(agentProperties(bo), catalogue)
    const write = (changes, inputs, redraw) => writeAgentTask(element, linkedChanges(agentProperties(element.businessObject), changes, catalogue), inputs, redraw)
    const agent = catalogue.agents.find(a => a.name === props["ants.agent"])
    const listed = listedSkills(props)
    const start = startingSkill(props, catalogue.skills)
    const dropped = agentNotes[element.id]
    delete agentNotes[element.id]
    const options = (names, current, missing, text = name => name) => [el("option", {value: "", textContent: "Choose…", selected: !current}),
        ...(current && !names.includes(current) ? [el("option", {value: current, textContent: `${current} (${missing})`, selected: true})] : []),
        ...names.map(name => el("option", {value: name, textContent: text(name), selected: name === current}))]
    // The Task's own list of Skills: null for the Agent's, [] for none.
    const skillChanges = names => ({"ants.skills": names?.join(", ")})
    const pickSkills = names => write(skillChanges(names), undefined, true)
    const pickAgent = name => {
        const [names, dropped] = newAgentSkills(listed, props["ants.assignment"], catalogue.agents.find(a => a.name === name))
        if (dropped.length) agentNotes[element.id] = `Dropped ${dropped.join(", ")}: ${name} doesn't have ${dropped.length > 1 ? "them" : "it"}.`
        write({"ants.agent": name || undefined, ...(names === listed ? {} : skillChanges(names))}, undefined, true)
    }
    const described = name => {
        const agent = catalogue.agents.find(a => a.name === name)
        return marked([name, agent?.description].filter(Boolean).join(" · "), agent)
    }
    const agentSelect = el("select", {onchange: () => pickAgent(agentSelect.value)},
        ...options(catalogue.agents.map(a => a.name), props["ants.agent"], "not found", described))
    const readOnly = ({name, folder}) => `${name} is in ${folder} and read-only: Copy it on its page to edit.`
    const editAgent = agent ? el("button", {type: "button", className: "ghost small", textContent: "Edit", disabled: !agent.own, title: agent.own ? "" : readOnly(agent), onclick: () =>
        formModal("agents", "/agents/" + encodeURIComponent(agent.name) + "?use", async name => {
            await refresh()
            if (name !== agent.name) return write({}, undefined, true)
            agentPanel(element, box)
            check()
        })}) : ""
    const newAgent = el("button", {type: "button", className: "ghost small", textContent: "＋ New Agent", onclick: () =>
        formModal("agents", "/agents/new/draft?use" + (listed?.length ? "&skills=" + encodeURIComponent(listed.join(", ")) : ""), async name => {
            await refresh()
            pickAgent(name)
        })})
    // New Skill's Use lists it in the Draft of the Task's Agent when that Agent is the user's own, and in the Draft of
    // its Your Skills when it is shipped.
    const who = el("div", {className: "who"}, !agent ? note("warn", "This Task has no Agent yet. The Skill is created, and you add it to an Agent's Skills it runs.")
        : agent.own ? note("okbox", wording.skillListed.replace("{agent}", agent.name))
            : agent.shipped ? note("okbox", wording.skillInYourSkills.replace("{agent}", agent.name))
            : note("warn", `${agent.name} is in ${agent.folder} and read-only, so the Skill can't be added to it. Copy the Agent, or pick one of your own, then choose this Skill.`))
    const newSkill = el("button", {type: "button", className: "ghost small", textContent: "＋ New Skill", onclick: () =>
        formModal("skills", "/skills/new/draft?use=" + encodeURIComponent(props["ants.agent"] || ""), async name => {
            if (agent?.own || agent?.shipped) await fetch(`/agents/${encodeURIComponent(agent.name)}/draft/skills`, {method: "POST", body: new URLSearchParams({_csrf: csrf, skill: name})})
            await refresh()
            pickSkills(listed === null && props["ants.assignment"]?.trim() ? null : [...(listed || []), name])
        }, who)})
    const editSkill = skill => el("button", {type: "button", className: "ghost small", textContent: "Edit", disabled: !skill.own, title: skill.own ? "" : readOnly(skill), onclick: () =>
        formModal("skills", "/skills/" + encodeURIComponent(skill.name) + "?use=", async name => {
            await refresh()
            if (name !== skill.name && listed?.includes(skill.name)) return write({}, undefined, true)
            agentPanel(element, box)
            check()
        })})
    // The Skills picker: the Agent's Skills, none, or only those ticked of the Agent's.
    const mode = listed === null ? "agent" : listed.length ? "some" : "none"
    const offeredSkills = agent?.skills || []
    const modeSelect = el("select", {id: "ants-skills", disabled: !agent, onchange: () =>
        pickSkills(modeSelect.value === "agent" ? null : modeSelect.value === "none" ? [] : offeredSkills)},
        ...[["agent", "The Agent's Skills"], ["none", "None"], ["some", "Only these"]].map(([value, textContent]) => el("option", {value, textContent, selected: value === mode})))
    const skillRows = [...offeredSkills, ...(listed || []).filter(name => !offeredSkills.includes(name))].map(name => {
        const skill = catalogue.skills.find(s => s.name === name)
        const box = el("input", {type: "checkbox", id: "ants-skill-" + name, checked: mode === "agent" || listed.includes(name), disabled: mode !== "some",
            onchange: () => pickSkills([...offeredSkills, ...listed].filter((n, i, all) => all.indexOf(n) === i && (n === name ? box.checked : listed.includes(n))))})
        const missing = !skill ? "not found" : offeredSkills.includes(name) ? "" : "not listed by this Agent"
        return el("div", {className: "row"}, box, el("label", {htmlFor: box.id, textContent: name}),
            draftMark(skill), el("span", {className: "muted small grow", textContent: missing || skill.description || ""}), skill ? editSkill(skill) : "")
    })
    const assignment = el("textarea", {id: "ants-assignment", rows: 4, value: props["ants.assignment"] || "",
        placeholder: "e.g. Write the release notes for the merged PRs, one line each.",
        oninput: () => write({"ants.assignment": assignment.value.trim() ? assignment.value : undefined})})
    assignment.dataset.markdown = ""
    const references = el("datalist", {id: "ants-references"}, ...offered.map(value => el("option", {value})))
    const inputs = agentInputs(bo).map(([name, value], i) => {
        const input = el("input", {className: "mono grow", value, placeholder: `e.g. \${${name}}`, oninput: () => {
            const all = agentInputs(bo)
            all[i][1] = input.value
            write({}, all)
        }})
        input.setAttribute("list", "ants-references")
        const remove = el("button", {type: "button", className: "ghost small", textContent: "×", title: `Remove ${name}`,
            onclick: () => write({}, agentInputs(bo).filter((_, j) => j !== i), true)})
        return el("label", {className: "row"}, el("code", {textContent: name}), input, remove)
    })
    const newInput = el("input", {id: "ants-new-input", className: "mono grow", placeholder: "Input name, e.g. ticket"})
    const addInput = el("button", {type: "button", className: "ghost small", textContent: "＋ Add input", onclick: () => {
        const name = newInput.value.trim()
        if (name && !agentInputs(bo).some(([n]) => n === name)) write({}, [...agentInputs(bo), [name, ""]], true)
    }})
    const tick = (label, checked, onchange) => el("label", {}, el("input", {type: "checkbox", checked, onchange}), " " + label)
    const toggle = (name, label) => tick(label, props[name] === "true",
        event => write({[name]: String(event.target.checked)}))
    const override = el("textarea", {
        rows: 4, value: props["ants.result-schema"] || "",
        placeholder: "No Result schema: the Task hands back done or stuck and a summary. Give one here to read its Result's fields.",
        oninput: () => write({"ants.result-schema": override.value.trim() || undefined}),
    })
    const levelSelect = el("select", {onchange: () => write({[level.property]: levelSelect.value || undefined})},
        el("option", {value: "", textContent: level.none, selected: !props[level.property]}),
        ...level.levels.map(value => el("option", {value, textContent: value, selected: value === props[level.property]})))
    box.replaceChildren(
        field("Agent", "Who does it.", agentSelect, draftMark(agent), editAgent, newAgent),
        props["ants.agent"] && !agent ? note("warn", `No Agent folder holds "${props["ants.agent"]}".`) : "",
        field("Level", level.help, levelSelect),
        field("Assignment", "The job, in a few lines. Leave it out when the Task lists one Skill: that Skill is then the job.", assignment),
        field("Skills", "What the Agent may use for it: all of its Skills, none, or only the ones ticked.", modeSelect,
            el("div", {className: "skillpick"}, ...(skillRows.length ? skillRows : [note("muted small", agent ? "The Agent has no Skills." : "Pick an Agent to see its Skills.")])),
            dropped ? note("warn", dropped) : "", newSkill),
        el("div", {className: "help"}, start?.description || "", start?.description ? el("br") : "", "Later Tasks can read: ",
            ...(read[element.id] || []).flatMap(reference => [el("code", {textContent: reference}), " "])),
        el("div", {className: "fld"}, el("label", {className: "f", textContent: "Inputs"}),
            note("help", "What it reads: start-form fields, Run variables and Results of Tasks before it in the diagram."), references,
            ...(inputs.length ? inputs : [note("muted small", "No inputs yet.")]), el("div", {className: "row"}, el("label", {htmlFor: newInput.id, textContent: "New input"}), newInput, addInput)),
        el("div", {className: "fld"}, el("label", {className: "f", textContent: "When the flow comes back"}),
            tick("Resume the session (Round answers, Send back)", props["ants.resume"] === "true",
                event => write({"ants.resume": event.target.checked ? "true" : undefined}))),
        el("div", {className: "fld"}, el("label", {className: "f", textContent: "Knowledge"}),
            toggle("ants.vault", "Vault and Source map"), toggle("ants.answer-history", "Answer History")),
        field("Result schema", "The fields of its Result, as a JSON Schema, for later Tasks to read.", override),
    )
}

// The call activity: the Process it calls (bound latest, so a fix to it reaches every caller), and one input per
// start-form field of that Process, filled like an Agent Task's input and written as one `camunda:in` each.
function callInputs(bo) {
    return (bo.extensionElements?.values || []).filter(value => value.$type === "camunda:In").map(i => [i.target, i.sourceExpression || ""])
}

function writeCall(element, changes, inputs, redraw = false) {
    const factory = modeler.get("bpmnFactory")
    const values = (element.businessObject.extensionElements?.values || []).filter(value => value.$type !== "camunda:In")
    values.push(...inputs.map(([target, sourceExpression]) => factory.create("camunda:In", {target, sourceExpression})))
    quiet = !redraw
    try {
        modeler.get("modeling").updateProperties(element, {...changes, extensionElements: factory.create("bpmn:ExtensionElements", {values})})
    } finally {
        quiet = false
    }
}

panels.call = element => {
    const box = el("div", {}, el("p", {textContent: "Loading the Processes…"}))
    callPanel(element, box)
    return box
}

async function callPanel(element, box) {
    const [processes, offered] = await Promise.all([fetch("/processes.json").then(response => response.json()), offeredAt(element)])
    const bo = element.businessObject
    const current = bo.calledElement
    const called = processes.find(p => p.key === current)
    const select = el("select", {
        onchange: () => {
            const picked = processes.find(p => p.key === select.value)
            const kept = Object.fromEntries(callInputs(bo))
            writeCall(element, {calledElement: select.value || undefined}, (picked?.fields || []).map(f => [f.name, kept[f.name] || ""]), true)
        },
    }, el("option", {value: "", textContent: "Choose…", selected: !current}),
        ...(current && !called ? [el("option", {value: current, textContent: `${current} (not deployed)`, selected: true})] : []),
        ...processes.map(p => el("option", {value: p.key, textContent: p.name, selected: p.key === current})))
    const labels = Object.fromEntries((called?.fields || []).map(f => [f.name, f.label + (f.required ? " (required)" : "")]))
    const references = el("datalist", {id: "ants-call-references"}, ...offered.map(value => el("option", {value})))
    const inputs = callInputs(bo).map(([name, value], i) => {
        const input = el("input", {className: "mono grow", value, placeholder: `e.g. \${${name}}`, oninput: () => {
            const all = callInputs(bo)
            all[i][1] = input.value
            writeCall(element, {}, all)
        }})
        input.setAttribute("list", "ants-call-references")
        return el("label", {className: "row"}, el("span", {className: "small", textContent: labels[name] || name}), input)
    })
    box.replaceChildren(
        field("Process", "The Process it runs, always its latest version.", select),
        el("div", {className: "fld"}, el("label", {className: "f", textContent: "Inputs"}),
            note("help", "One per start-form field of the called Process."), references,
            ...(inputs.length ? inputs : [note("muted small", called ? "The Process's start form has no fields." : "Pick a Process to see its start-form fields.")])),
    )
}

// The User Task: its kind (`ants.user-task`, none for an Approve), that kind's fields, each an input parameter or an
// extension property naming an Agent Task, and the fields of its Result.
panels.user = element => {
    const bo = element.businessObject
    const props = agentProperties(bo)
    const kind = userTasks.find(k => k.value === (props["ants.user-task"] || "")) || userTasks.find(k => !k.value)
    // Switching kind drops the fields only the old kind has, such as Approve's title and show.
    const what = el("select", {onchange: () => {
        const next = userTasks.find(k => k.value === what.value)
        const stale = userTasks.flatMap(k => k.fields).filter(f => !next.fields.some(g => g.property === f.property && g.input === f.input))
        const cleared = Object.fromEntries(stale.filter(f => f.property).map(f => [f.property, undefined]))
        writeAgentTask(element, {...cleared, "ants.user-task": what.value || undefined}, agentInputs(bo).filter(([name]) => !stale.some(f => f.input === name)), true)
    }}, ...userTasks.map(k => el("option", {value: k.value, textContent: k.name, selected: k === kind})))
    const agentTasks = modeler.get("elementRegistry").filter(e => !e.labelTarget && kindOf(e) === "agent")
    const control = ({property, input, example}) => {
        if (input) {
            const box = el("input", {value: Object.fromEntries(agentInputs(bo))[input] || "", placeholder: `e.g. ${example}`, onchange: () => {
                const inputs = agentInputs(bo).filter(([name]) => name !== input)
                writeAgentTask(element, {}, box.value ? [...inputs, [input, box.value]] : inputs)
            }})
            return box
        }
        const current = props[property] || ""
        const missing = current && !agentTasks.some(e => e.id === current) ? [el("option", {value: current, textContent: `${current} (not in this diagram)`, selected: true})] : []
        const box = el("select", {onchange: () => writeAgentTask(element, {[property]: box.value || undefined})},
            el("option", {value: "", textContent: "Choose…", selected: !current}), ...missing,
            ...agentTasks.map(e => el("option", {value: e.id, textContent: `${nameOf(e)} (${e.id})`, selected: e.id === current})))
        return box
    }
    return el("div", {}, field("What you do", "", what), ...kind.fields.map(f => field(f.label, f.help, control(f))),
        el("div", {className: "help reads"}, "Later Tasks and decisions can read: ",
            ...kind.result.flatMap(f => [el("code", {textContent: `\${${element.id}.${f}}`}), " "])))
}

// The panel's head: what the element is, and its name, which the diagram shows.
function head(element, kind) {
    const name = el("input", {className: "name", value: element.businessObject.name || "", placeholder: "e.g. Write the Spec",
        onchange: () => modeler.get("modeling").updateLabel(element, name.value)})
    return [el("div", {className: "kind", textContent: kind}), field("Name", "", name)]
}

// The kinds whose panel explains them itself, or warns instead.
const ownWords = new Set(["process", "agent", "exclusive", "parallel", "flow", "task"])

function explain(element) {
    const target = element.labelTarget || element
    // Importing clears the diagram first, which selects the implicit root: no BPMN behind it yet, so nothing to explain.
    // Throwing here would abort the import.
    if (!target.businessObject) return
    const kind = kindOf(target)
    if (kind === "process") return panel.replaceChildren(el("div", {className: "kind", textContent: kinds.process.name}), el("h3", {textContent: nameOf(target)}), panels.process(target))
    if (kind && kind !== "task") {
        return panel.replaceChildren(...head(target, kinds[kind].name), ownWords.has(kind) ? "" : note("explain", kinds[kind].explain),
            panels[kind]?.(target) || "", multiInstance(target))
    }
    // A plain Task, or anything Ants doesn't run: what it can become.
    const type = target.type.replace("bpmn:", ""), event = target.businessObject.eventDefinitions?.[0]?.$type.replace("bpmn:", "")
    const gateway = (text, type) => el("button", {textContent: text, onclick: () => replace(target, type)})
    const options = kind || /Task$/.test(type) ? taskButtons(target)
        : /Gateway$/.test(type) ? [gateway(kinds.exclusive.name, "bpmn:ExclusiveGateway"), " ", gateway(kinds.parallel.name, "bpmn:ParallelGateway")] : []
    panel.replaceChildren(
        ...(kind ? head(target, kinds.task.name) : [el("div", {className: "kind", textContent: type + (event ? " · " + event : "")}), el("h3", {textContent: nameOf(target)})]),
        note("warn", kind ? kinds.task.explain : wording.notRun),
        ...(kind || !options.length ? [] : [note("small change", "Change it to:")]), ...options)
}

// Agent, User and Built-in Tasks in their colours on the diagram.
function mark(element) {
    const canvas = modeler.get("canvas")
    for (const {marker} of Object.values(tasks)) canvas.removeMarker(element, marker)
    const marker = tasks[kindOf(element)]?.marker
    if (marker && !element.labelTarget) canvas.addMarker(element, marker)
}
modeler.on("import.done", () => modeler.get("elementRegistry").forEach(mark))
modeler.on(["shape.added", "element.changed"], ({element}) => element.businessObject && mark(element))

modeler.on("selection.changed", ({newSelection}) => explain(newSelection.length === 1 ? newSelection[0] : modeler.get("canvas").getRootElement()))
modeler.on("element.changed", ({element}) => {
    if (quiet) return
    const selection = modeler.get("selection").get()
    if (selection.length === 1 && selection[0] === element) explain(element)
})

// The Draft's autosave starts once the diagram is in, so what it loaded is what it compares each change with. A
// Template dropped in replaces the diagram by importing, which the command stack doesn't see.
let draft
fetch(canvas.dataset.src)
    .then(response => response.text())
    .then(xml => modeler.importXML(toCamunda(xml)))
    .then(() => {
        modeler.get("canvas").zoom("fit-viewport", "auto")
        explain(modeler.get("canvas").getRootElement())
        if (!canvas.dataset.autosave) return
        draft = autosave({endpoint: canvas.dataset.autosave, version: canvas.dataset.version, csrf, fields: async () => ({bpmn: (await modeler.saveXML({format: true})).xml})})
        modeler.on("commandStack.changed", draft.changed)
        modeler.on("import.done", draft.changed)
    })

// Publish (or a Template's Save). The Draft is saved first, so leaving for the Process page has nothing left to save.
document.getElementById("save").onclick = async () => {
    save.disabled = true
    try {
        await draft?.save()
        const {xml} = await modeler.saveXML({format: true})
        const response = await fetch(save.dataset.action, {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml})})
        if (response.ok) return location = response.url
        document.getElementById("refusal").textContent = await response.text()
    } catch (failure) {
        document.getElementById("refusal").textContent = failure.message
    }
    save.disabled = false
}

// The live Checks: everything Publish would refuse, from the Checks it runs, and the warnings, under a summary
// of how many to fix. Publish is disabled while any Check fails; clicking a Check selects its element, inside
// a collapsed sub-process too.
const save = document.getElementById("save")
const checks = document.getElementById("checks")
const summary = document.getElementById("summary")

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
    const failing = found.filter(item => !item.warning).length
    save.disabled = failing > 0
    save.title = save.disabled ? "Fix the Checks first" : ""
    summary.className = "pill " + (failing ? "p-waiting" : "p-ready")
    summary.textContent = failing ? `${failing} to fix` : "Ready to deploy"
    checks.replaceChildren(...found.map(item => el("li", {className: item.warning ? "warning" : "error"}, item.element
        ? el("a", {href: "#" + item.element, textContent: item.message, onclick: event => { event.preventDefault(); jump(item.element) }})
        : item.message)))
}

let pending
modeler.on("commandStack.changed", () => { clearTimeout(pending); pending = setTimeout(check, 300) })
modeler.on("import.done", check)

// The Process Builder's session (ADR 0019, ProcessBuilderTools.kt): a page that can be edited by hand opens one, and
// answers each call Ants relays to it on its stream from bpmn-js, with a POST back. Its token is on the canvas.
const relayed = {
    xml: async () => (await modeler.saveXML()).xml,
    add_element: addElement,
    remove: ({id, from, to}) => {
        if (id !== undefined && (from !== undefined || to !== undefined)) throw new Error("Give remove an id, or from and to, not both.")
        const element = id === undefined ? between(from, to) : found(id)
        const removed = [element, ...(element.incoming || []), ...(element.outgoing || [])].map(e => e.id)
        edit(() => modeler.get("modeling").removeElements([element]))
        return {removed}
    },
    rename: ({id, name}) => {
        const element = found(id)
        edit(() => modeler.get("modeling").updateLabel(element, name || ""))
        return {id, name: name || ""}
    },
    move: args => {
        const element = found(args.id)
        if (element.waypoints) throw new Error(`${args.id} is a connection: move the elements it connects.`)
        const x = number(args, "x"), y = number(args, "y")
        edit(() => modeler.get("modeling").moveShape(element, {x: x - element.x, y: y - element.y}))
        return {id: element.id, x, y}
    },
    insert,
    connect: ({from, to, id, name}) => {
        const source = found(from), target = found(to)
        const allowed = modeler.get("bpmnRules").canConnect(source, target)
        if (!allowed?.type) throw new Error(`${from} can't be connected to ${to}.`)
        checkIdValidAndFree(id)
        const businessObject = modeler.get("bpmnFactory").create(allowed.type, id ? {id} : {})
        return {id: edit(() => {
            const connection = modeler.get("modeling").connect(source, target, {type: allowed.type, businessObject})
            if (name) modeler.get("modeling").updateLabel(connection, name)
            return connection.id
        })}
    },
    set_condition: ({id, condition}) => {
        const flow = path(id)
        edit(() => {
            if (flow.source.businessObject.default === flow.businessObject) modeler.get("modeling").updateProperties(flow.source, {default: undefined})
            writeCondition(flow, condition || "")
        })
        return {id, condition: condition || null}
    },
    set_default: ({id}) => {
        const flow = path(id)
        edit(() => writeDefault(flow.source, flow))
        return {id, default: true}
    },
    set_agent_task: setAgentTask,
    set_start_form: setStartForm,
    set_user_task: setUserTask,
    set_call: setCall,
    set_built_in: setBuiltIn,
    set_multi_instance: setMultiInstance,
    drop_template: async args => {
        const at = args.x === undefined && args.y === undefined ? undefined : {x: number(args, "x") + 50, y: number(args, "y") + 40} // a collapsed sub-process is 100 by 80
        return {id: await dropTemplate(String(args.name ?? ""), at)}
    },
}

// The Process Builder's changes: each tool checks everything it was given against the diagram as it is now, then
// changes it through bpmn-js inside one command, so the change is one undo step and nothing is left half done.
modeler.get("commandStack").register("ants.builder", {preExecute: ({run}) => run()})

function edit(change) {
    let result
    modeler.get("commandStack").execute("ants.builder", {run: () => { result = change() }})
    return result
}

function found(id) {
    const element = id ? modeler.get("elementRegistry").get(String(id)) : undefined
    if (!element?.parent || element.labelTarget) throw new Error(`No element ${id}.`)
    return element
}

// A name JUEL can read, as an id or an input name is: letters, digits and underscores, starting with a letter.
function checkJuelName(name, what) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) throw new Error(`${name} isn't ${what}: use letters, digits and underscores, starting with a letter.`)
}

// A new element's id, when one is given: a JUEL name no element has yet.
function checkIdValidAndFree(id) {
    if (id === undefined || id === "") return
    checkJuelName(id, "an id")
    if (modeler.get("elementRegistry").get(id)) throw new Error(`The diagram already has an element ${id}.`)
}

function number(args, key) {
    const value = Number(args[key])
    if (args[key] === undefined || args[key] === "" || Number.isNaN(value)) throw new Error(`${key} must be a number, not ${args[key]}.`)
    return value
}

// A path out of a decision, the only paths that take a condition.
function path(id) {
    const flow = found(id)
    if (flow.type !== "bpmn:SequenceFlow") throw new Error(`${id} isn't a connection.`)
    if (kindOf(flow.source) !== "exclusive") throw new Error(`${id} leaves ${flow.source.id}, which isn't a decision: only a decision's paths take a condition or a default.`)
    return flow
}

// The Process's start event, not a sub-process's.
const startEvent = () => modeler.get("elementRegistry").find(e => e.type === "bpmn:StartEvent" && e.parent.businessObject?.$type === "bpmn:Process")

function addElement(args) {
    const registry = modeler.get("elementRegistry")
    let parent = registry.find(e => !e.parent && e.type === "bpmn:Process")
    if (args.in !== undefined) {
        const sub = found(args.in)
        if (kindOf(sub) !== "subprocess") throw new Error(`${args.in} isn't a sub-process.`)
        parent = registry.get(sub.id + "_plane") || sub // a collapsed one's inside is a plane of its own
    }
    const {shape, draw} = newShape(args)
    const at = args.x === undefined && args.y === undefined ? undefined : {x: number(args, "x"), y: number(args, "y")}
    const centre = at ? {x: at.x + shape.width / 2, y: at.y + shape.height / 2} : nextTo(parent, shape)
    return {id: edit(() => draw(centre, parent).id)}
}

// An element of [kind] to draw, checked against the diagram as it is: its shape, and [draw], which draws it inside an edit.
function newShape(args) {
    const {kind, id, name, topic, properties = {}} = args
    if (!drawn[kind]) throw new Error(`No kind ${kind}: the kinds are ${Object.keys(drawn).join(", ")}.`)
    if (topic !== undefined && kind !== "built-in") throw new Error("Only a Built-in Task takes a topic.")
    if (topic !== undefined && !builtIns[topic]) throw new Error(`No Built-in Task ${topic}: the topics are ${Object.keys(builtIns).join(", ")}.`)
    checkIdValidAndFree(id)
    const inputs = Object.entries(args.inputs || {}).map(([input, value]) => {
        checkJuelName(input, "an input name")
        return [input, String(value ?? "")]
    })
    const {type, attrs = {}, ...rest} = drawn[kind]
    const businessObject = modeler.get("bpmnFactory").create(type, {...attrs, ...(topic ? {"camunda:topic": topic} : {}), ...(id ? {id} : {})})
    const shape = modeler.get("elementFactory").createShape({...rest, type, businessObject})
    return {shape, draw: (centre, parent) => {
        const created = modeler.get("modeling").createShape(shape, centre, parent)
        if (name) modeler.get("modeling").updateLabel(created, name)
        if (Object.keys(properties).length || inputs.length) writeAgentTask(created, properties, inputs, true)
        return created
    }}
}

// The one connection from [from] to [to].
function between(from, to) {
    if (from === undefined || to === undefined) throw new Error("Name a connection by its id, or by from and to.")
    const flows = modeler.get("elementRegistry").filter(e => e.waypoints && e.source?.id === String(from) && e.target?.id === String(to))
    if (!flows.length) throw new Error(`No connection from ${from} to ${to}.`)
    if (flows.length > 1) throw new Error(`${flows.length} connections go from ${from} to ${to}: ${flows.map(f => f.id).join(", ")}. Give the one you mean by its id.`)
    return flows[0]
}

// A new element on the connection from [from] to [to]: the connection now ends at it, keeping its id, name, condition
// and default, and a new one goes on from it to the old target.
function insert(args) {
    const flow = between(args.from, args.to)
    if (flow.type !== "bpmn:SequenceFlow") throw new Error(`${flow.id} isn't a sequence flow.`)
    if (["start", "end", "annotation", "group"].includes(args.kind)) throw new Error(`A ${args.kind} can't go on a connection.`)
    checkIdValidAndFree(args.connection)
    if (args.connection !== undefined && args.connection === args.id) throw new Error("The new element and its connection need ids of their own.")
    const {shape, draw} = newShape(args)
    const {source, target} = flow
    const at = args.x === undefined && args.y === undefined ? undefined : {x: number(args, "x"), y: number(args, "y")}
    const centre = at ? {x: at.x + shape.width / 2, y: at.y + shape.height / 2}
        : {x: (source.x + source.width / 2 + target.x + target.width / 2) / 2, y: source.y + source.height / 2}
    const modeling = modeler.get("modeling")
    return edit(() => {
        const created = draw(centre, flow.parent)
        modeling.reconnectEnd(flow, created, {x: created.x + created.width / 2, y: created.y + created.height / 2})
        const businessObject = modeler.get("bpmnFactory").create("bpmn:SequenceFlow", args.connection ? {id: args.connection} : {})
        const next = modeling.connect(created, target, {type: "bpmn:SequenceFlow", businessObject})
        return {id: created.id, incoming: flow.id, outgoing: next.id}
    })
}

// Where an element goes when no position is given: right of the rightmost element in [parent], level with it.
function nextTo(parent, shape) {
    const siblings = parent.children.filter(e => !e.waypoints && !e.labelTarget)
    if (!siblings.length) return parent.parent ? {x: parent.x + 50 + shape.width / 2, y: parent.y + parent.height / 2} : {x: 150 + shape.width / 2, y: 200}
    const last = siblings.reduce((a, b) => b.x + b.width > a.x + a.width ? b : a)
    return {x: last.x + last.width + 50 + shape.width / 2, y: last.y + last.height / 2}
}

// The Agent Task's Agent, Assignment, Skills and inputs, written as the panel's pickers and input boxes write them
// (ADR 0024): skills null gives the Task its Agent's, [] none; a new Agent drops the listed Skills it lacks, and picks
// its first for a Task with neither an Assignment nor a list.
async function setAgentTask({id, agent, assignment, assignmentEdit, assignmentAppend, skills, inputs, resultSchema, resultSchemaPatch, resume, vault, answerHistory}) {
    const element = foundAs(id, "agent", "an Agent Task")
    if (assignment !== undefined && (assignmentEdit !== undefined || assignmentAppend !== undefined)) throw new Error("Give the whole assignment, or assignmentEdit and assignmentAppend, not both.")
    if (resultSchema !== undefined && resultSchemaPatch !== undefined) throw new Error("Give the whole resultSchema, or resultSchemaPatch, not both.")
    const catalogue = await (await fetch("/catalogue.json")).json()
    const current = linkedProperties(agentProperties(element.businessObject), catalogue)
    const agentName = agent ?? current["ants.agent"]
    const picked = catalogue.agents.find(a => a.name === agentName)
    if (agent !== undefined && !picked) throw new Error(`No Agent ${agent}: the Agents are ${catalogue.agents.map(a => a.name).join(", ")}.`)
    if (skills !== undefined && skills !== null && (!Array.isArray(skills) || skills.some(s => typeof s !== "string"))) throw new Error("skills must be a list of Skill names, or null.")
    const text = assignment === undefined ? editedAssignment(current["ants.assignment"] || "", assignmentEdit, assignmentAppend) : String(assignment ?? "").trim()
    let listed = skills === undefined ? listedSkills(current) : skills && [...new Set(skills)]
    let dropped = []
    if (skills === undefined && agent !== undefined && agent !== current["ants.agent"]) [listed, dropped] = newAgentSkills(listed, text, picked)
    const lacking = skills ? listed.filter(s => !picked?.skills.includes(s)) : []
    if (lacking.length) throw new Error(`${agentName ? "The Agent " + agentName : "The Task's Agent"} doesn't have the Skill${lacking.length > 1 ? "s" : ""} ${lacking.join(", ")}` +
        (picked ? `: it has ${picked.skills.join(", ") || "none"}.` : ": give the Task an Agent first."))
    const written = Object.entries({...Object.fromEntries(agentInputs(element.businessObject)), ...(inputs || {})}).map(([name, value]) => [name, String(value ?? "")])
    const changes = {"ants.agent": agentName || undefined, "ants.assignment": text || undefined, "ants.skills": listed?.join(", ")}
    // The panel's Result schema box and toggles: resume is set or gone, Vault and Answer History say true or false.
    if (resultSchema !== undefined) {
        const schema = String(resultSchema).trim()
        let parsed
        try {
            parsed = schema && JSON.parse(schema)
        } catch {}
        if (schema && (typeof parsed !== "object" || !parsed)) throw new Error("resultSchema isn't a JSON Schema: give a JSON object such as {\"type\": \"object\", \"properties\": {…}}.")
        changes["ants.result-schema"] = schema || undefined
    }
    const patched = resultSchemaPatch === undefined ? undefined : patchedSchema(current["ants.result-schema"], resultSchemaPatch)
    if (patched) changes["ants.result-schema"] = patched.text
    if (resume !== undefined) changes["ants.resume"] = yes(resume, "resume") ? "true" : undefined
    if (vault !== undefined) changes["ants.vault"] = String(yes(vault, "vault"))
    if (answerHistory !== undefined) changes["ants.answer-history"] = String(yes(answerHistory, "answerHistory"))
    edit(() => writeAgentTask(element, linkedChanges(agentProperties(element.businessObject), changes, catalogue), written, true))
    return {id, agent: agentName || null, assignment: text || null, skills: listed, ...(dropped.length ? {dropped} : {}), inputs: Object.fromEntries(written),
        ...(patched?.reformatted ? {note: "The Result schema was written in a layout of its own, so it is now laid out as compact JSON."} : {}),
        properties: linkedProperties(agentProperties(element.businessObject), catalogue)}
}

// The Assignment [text] with [edit]'s old replaced by its new, then [append] on a line of its own; the rest as it was.
function editedAssignment(text, edit, append) {
    if (edit !== undefined) {
        const old = String(edit?.old ?? ""), replacement = String(edit?.new ?? "")
        if (!old) throw new Error("assignmentEdit needs the old text to replace.")
        const times = text.split(old).length - 1
        if (times !== 1) throw new Error(`The Assignment has the old text ${times ? times + " times" : "nowhere"}: give a longer piece of it, exactly as it is, that it has once.`)
        text = text.replace(old, () => replacement)
    }
    if (append === undefined || append === "") return text
    return text && !text.endsWith("\n") ? `${text}\n${append}` : text + append
}

// Ways a Result schema is laid out: compact, on one line with a space after each colon and comma, or indented.
const spaced = value => Array.isArray(value) ? `[${value.map(spaced).join(", ")}]`
    : value && typeof value === "object" ? `{${Object.entries(value).map(([key, v]) => `${JSON.stringify(key)}: ${spaced(v)}`).join(", ")}}` : JSON.stringify(value)
const layouts = [value => JSON.stringify(value), spaced, ...[2, 4, "\t"].map(indent => value => JSON.stringify(value, null, indent))]

// The Result schema [written] with [patch]'s fields added, replaced or removed and its required list changed, in the
// layout it was written in; reformatted when that layout is none of [layouts].
function patchedSchema(written, patch) {
    const text = String(written ?? "").trim()
    let schema
    try {
        schema = text ? JSON.parse(text) : {type: "object"}
    } catch {
        throw new Error("The Task's Result schema isn't JSON: give resultSchema whole instead.")
    }
    if (!schema || typeof schema !== "object" || Array.isArray(schema)) throw new Error("The Task's Result schema isn't a JSON object: give resultSchema whole instead.")
    const layout = text ? layouts.find(l => l(JSON.parse(text)) === text) : layouts[0]
    const list = (key) => {
        const value = patch?.[key] ?? []
        if (!Array.isArray(value) || value.some(v => typeof v !== "string")) throw new Error(`resultSchemaPatch's ${key} must be a list of field names.`)
        return value
    }
    const added = patch?.properties ?? {}
    if (typeof added !== "object" || Array.isArray(added) || Object.values(added).some(v => !v || typeof v !== "object")) throw new Error("resultSchemaPatch's properties must give each field a JSON Schema object.")
    const [remove, required, optional] = ["remove", "required", "optional"].map(list)
    if (Object.keys(added).length) schema.properties = Object.assign(schema.properties || {}, added)
    for (const field of remove) {
        if (!schema.properties?.[field]) throw new Error(`The Result schema has no field ${field}.`)
        delete schema.properties[field]
    }
    for (const field of required) if (!schema.properties?.[field]) throw new Error(`The Result schema has no field ${field} to require.`)
    if (remove.length || required.length || optional.length) {
        const now = (schema.required || []).filter(f => !remove.includes(f) && !optional.includes(f))
        now.push(...required.filter(f => !now.includes(f)))
        if (now.length) schema.required = now
        else delete schema.required
    }
    return {text: (layout || layouts[0])(schema), reformatted: !layout}
}

// The start form's fields, all of them in order, as the panel writes them.
function setStartForm({fields}) {
    const start = startEvent()
    if (!start) throw new Error("The Process has no start event: add one first.")
    if (!Array.isArray(fields)) throw new Error("fields must be a list of fields.")
    const written = fields.map((f, i) => {
        const label = String(f?.label ?? ""), id = String(f?.id || camel(label)), type = f?.type || "text"
        if (!id) throw new Error(`Field ${i + 1} needs a label or an id.`)
        if (!fieldTypes[type]) throw new Error(`Field ${id} has no type ${type}: the types are ${Object.keys(fieldTypes).join(", ")}.`)
        return {id, label, type, help: String(f.help ?? ""), example: String(f.example ?? ""), stored: String(f.stored ?? ""), required: f.required === true || f.required === "true"}
    })
    const twice = written.map(f => f.id).find((id, i, all) => all.indexOf(id) !== i)
    if (twice) throw new Error(`Two fields are named ${twice}.`)
    edit(() => writeForm(start, written))
    return {id: start.id, fields: written.map(f => f.id)}
}

// The element [id], which must be of [kind]; [what] says what it isn't when it isn't.
function foundAs(id, kind, what) {
    const element = found(id)
    if (kindOf(element) !== kind) throw new Error(`${id} is ${kinds[kindOf(element)]?.name || element.type}, not ${what}.`)
    return element
}

function yes(value, key) {
    if (value === true || value === "true") return true
    if (value === false || value === "false") return false
    throw new Error(`${key} must be true or false, not ${value}.`)
}

// An extension property that names an Agent Task, such as a User Task's Send back resumes.
function agentTaskNamed(key, value) {
    const task = modeler.get("elementRegistry").get(String(value))
    if (value && !(task?.businessObject && kindOf(task) === "agent")) throw new Error(`${key} names an Agent Task, and the diagram has no Agent Task ${value}.`)
}

const userTaskOf = bo => userTasks.find(k => k.value === (agentProperties(bo)["ants.user-task"] || "")) || userTasks.find(k => !k.value)
// A User Task field, by its input (title, show) or its property without `ants.` (send-back, asker, walkthrough).
const userFieldName = f => f.input || f.property.replace(/^ants\./, "")

// The User Task's kind and fields, as the panel's kind picker and field boxes write them.
function setUserTask({id, kind, fields}) {
    const element = foundAs(id, "user", "a User Task")
    const bo = element.businessObject
    const next = kind === undefined ? userTaskOf(bo) : userTasks.find(k => k.key === kind)
    if (!next) throw new Error(`No User Task kind ${kind}: the kinds are ${userTasks.map(k => k.key).join(", ")}.`)
    const given = Object.entries(fields || {}).map(([name, value]) => {
        const f = next.fields.find(f => userFieldName(f) === name)
        if (!f) throw new Error(`${next.name} has no field ${name}: it has ${next.fields.map(userFieldName).join(", ")}.`)
        if (f.property) agentTaskNamed(name, value ?? "")
        return [f, String(value ?? "")]
    })
    // Switching kind drops the fields only the old kind has, as the panel does.
    const stale = userTasks.flatMap(k => k.fields).filter(f => !next.fields.some(g => g.property === f.property && g.input === f.input))
    const changes = {...Object.fromEntries(stale.filter(f => f.property).map(f => [f.property, undefined])), "ants.user-task": next.value || undefined}
    let inputs = agentInputs(bo).filter(([name]) => !stale.some(f => f.input === name))
    for (const [f, value] of given) {
        if (f.property) changes[f.property] = value || undefined
        else inputs = [...inputs.filter(([name]) => name !== f.input), ...(value ? [[f.input, value]] : [])]
    }
    edit(() => writeAgentTask(element, changes, inputs, true))
    return {id, kind: next.key, fields: Object.fromEntries(next.fields.map(f =>
        [userFieldName(f), f.property ? agentProperties(bo)[f.property] || "" : Object.fromEntries(agentInputs(bo))[f.input] || ""]))}
}

// The call activity's Process and inputs, as the panel's Process picker and input boxes write them.
async function setCall({id, process, inputs}) {
    const element = foundAs(id, "call", "a call activity")
    const bo = element.businessObject
    const processes = await (await fetch("/processes.json")).json()
    const key = process ?? bo.calledElement
    const called = processes.find(p => p.key === key)
    if (process !== undefined && !called) throw new Error(`No Process ${process} is deployed: the Processes are ${processes.map(p => p.key).join(", ")}.`)
    const kept = Object.fromEntries(callInputs(bo))
    const names = called ? called.fields.map(f => f.name) : Object.keys(kept)
    const given = inputs || {}
    const unknown = Object.keys(given).filter(name => !names.includes(name))
    if (unknown.length) throw new Error(`${key ? "The Process " + key : "A call activity calling no Process"} has no start-form field ${unknown.join(", ")}; its fields are ${names.join(", ") || "none"}.`)
    const written = names.map(name => [name, String(given[name] ?? kept[name] ?? "")])
    edit(() => writeCall(element, {calledElement: key || undefined}, written, true))
    return {id, process: key || null, inputs: Object.fromEntries(written)}
}

function setBuiltIn({id, topic, inputs, asker}) {
    const script = kindOf(found(id)) === "script"
    const element = script ? found(id) : foundAs(id, "built-in", "a Built-in Task or a Script Task")
    if (script && (topic !== undefined || asker !== undefined)) throw new Error(`${id} is a Script Task: set only its inputs.`)
    const bo = element.businessObject
    if (topic !== undefined && !builtIns[topic]) throw new Error(`No Built-in Task ${topic}: the topics are ${Object.keys(builtIns).join(", ")}.`)
    const now = topic ?? bo.get("camunda:topic")
    if (asker !== undefined && !builtIns[now]?.asker) {
        throw new Error(`Only ${Object.keys(builtIns).filter(topic => builtIns[topic].asker).join(", ")} names an asker; ${id} is ${now}.`)
    }
    if (asker !== undefined) agentTaskNamed("asker", asker)
    const given = Object.entries(inputs || {}).map(([name, value]) => {
        checkJuelName(name, "an input name")
        return [name, String(value ?? "")]
    })
    const values = Object.fromEntries(given)
    const written = [...agentInputs(bo).map(([name, value]) => [name, values[name] ?? value]), ...given.filter(([name]) => !agentInputs(bo).some(([n]) => n === name))]
        .filter(([, value]) => value)
    edit(() => {
        if (topic !== undefined) modeler.get("modeling").updateProperties(element, {"camunda:topic": topic})
        if (inputs !== undefined || asker !== undefined) writeAgentTask(element, asker === undefined ? {} : {"ants.asker": asker || undefined}, written, true)
    })
    return {id, topic: now, inputs: Object.fromEntries(agentInputs(bo)), asker: agentProperties(bo)["ants.asker"] || null}
}

// Multi-instance, as the wrench menu's parallel and sequential toggles write it, and the panel's collection, element
// variable and Items are Tickets.
function setMultiInstance({id, mode, collection, elementVariable, itemsAreTickets: tickets}) {
    const element = found(id)
    if (!repeatable.has(kindOf(element))) throw new Error(`${id} is ${kinds[kindOf(element)]?.name || element.type}: only an Agent Task, a sub-process or a call activity runs as a multi-instance.`)
    if (mode !== undefined && !multiInstanceModes.includes(mode)) throw new Error(`mode is one of ${multiInstanceModes.join(", ")}, not ${mode}.`)
    const MI = "bpmn:MultiInstanceLoopCharacteristics"
    const on = mode === undefined ? element.businessObject.loopCharacteristics?.$type === MI : mode !== "none"
    const settings = [collection, elementVariable, tickets].some(value => value !== undefined)
    if (!on && settings) throw new Error(`${id} doesn't run as a multi-instance: give mode parallel or sequential.`)
    const ticketed = tickets === undefined ? undefined : yes(tickets, "itemsAreTickets")
    const modeling = modeler.get("modeling")
    edit(() => {
        if (mode === "none") return modeling.updateProperties(element, {loopCharacteristics: undefined})
        let loop = element.businessObject.loopCharacteristics
        if (mode && loop?.$type === MI) modeling.updateModdleProperties(element, loop, {isSequential: mode === "sequential"})
        else if (mode) modeling.updateProperties(element, {loopCharacteristics: loop = modeler.get("moddle").create(MI, {isSequential: mode === "sequential"})})
        if (collection !== undefined) modeling.updateModdleProperties(element, loop, {"camunda:collection": String(collection) || undefined})
        if (elementVariable !== undefined) modeling.updateModdleProperties(element, loop, {"camunda:elementVariable": String(elementVariable) || undefined})
        if (ticketed !== undefined) writeAgentTask(element, {[itemsAreTickets.property]: ticketed ? "true" : undefined})
    })
    const loop = element.businessObject.loopCharacteristics
    return {id, mode: loop?.$type === MI ? (loop.isSequential ? "sequential" : "parallel") : "none",
        collection: loop?.get("camunda:collection") || null, elementVariable: loop?.get("camunda:elementVariable") || null}
}

async function openBuilder() {
    const endpoint = builderPanel.dataset.path
    // A reload in the tab gives the token it had, so it keeps its session and dialog while Ants does (ProcessBuilderTools).
    const kept = "ants.builder." + location.pathname
    const {token} = await (await fetch(endpoint, {method: "POST", body: new URLSearchParams({_csrf: csrf, token: sessionStorage.getItem(kept) ?? ""})})).json()
    sessionStorage.setItem(kept, token)
    stream.on("builder", async data => {
        const {id, tool, arguments: args} = JSON.parse(data)
        const body = {_csrf: csrf, token, call: id}
        try {
            body.answer = JSON.stringify(await relayed[tool](args))
        } catch (error) {
            body.error = error.message
        }
        fetch(endpoint + "/answer", {method: "POST", body: new URLSearchParams(body)})
    })
    // The dialog: Send posts the message into the page's claude session, Stop interrupts its turn; the server sends
    // the whole dialog whenever it changes, with whether a turn runs.
    const form = document.getElementById("pb-form"), message = document.getElementById("pb-message"), error = document.getElementById("pb-error")
    const stop = document.getElementById("pb-stop"), send = document.getElementById("pb-send"), live = document.getElementById("pb-live")
    const dialog = document.getElementById("pb-dialog")
    const post = (path, fields) => fetch(path, {method: "POST", body: new URLSearchParams({_csrf: csrf, token, ...fields})})
    let sent = false // Send stays disabled until the first dialog after the server took the message
    stream.on("dialog", html => {
        const bottom = dialog.scrollHeight - dialog.scrollTop - dialog.clientHeight < 40
        stream.patch(live, html)
        const busy = live.querySelector("#pb-state")?.dataset.busy === "true"
        stop.hidden = !busy
        send.hidden = busy
        if (sent) send.disabled = false
        sent = false
        if (bottom) dialog.scrollTop = dialog.scrollHeight
    })
    form.addEventListener("submit", async event => {
        event.preventDefault()
        if (send.hidden || send.disabled || !message.value.trim()) return
        const text = message.value
        message.value = ""
        message.dispatchEvent(new Event("change"))
        send.disabled = true
        const answer = await post(endpoint + "/send", {message: text}).then(response => response.json()).catch(failure => ({error: failure.message}))
        error.textContent = answer.error || ""
        if (!answer.error) sent = true
        else {
            send.disabled = false
            message.value = text
            message.dispatchEvent(new Event("change"))
        }
    })
    stop.addEventListener("click", () => post(endpoint + "/stop", {}))
    stream.follow("builder=" + token)
    canvas.dataset.builder = token
}

const builderPanel = document.getElementById("builder")
if (builderPanel) {
    const toggle = document.getElementById("builder-toggle")
    const show = shown => {
        builderPanel.hidden = !shown
        toggle.setAttribute("aria-pressed", String(shown))
        localStorage.setItem("ants.builder", shown ? "shown" : "hidden")
    }
    show(localStorage.getItem("ants.builder") !== "hidden")
    toggle.addEventListener("click", () => show(builderPanel.hidden))
    document.getElementById("pb-form").addEventListener("submit", event => event.preventDefault()) // never a reload, which would lose the diagram
    if (!builderPanel.dataset.off) openBuilder()
}
