// The modeler page: camunda-bpmn-js with the palette trimmed to what Ants runs, "append Agent / User / Built-in Task"
// on the context pad, a panel that explains the selected element and edits it, the live Checks under it, and Save and
// deploy through Ants' deploy path. Each kind of element is one entry in `kinds`; what its panel asks beyond its name
// and explanation (the Agent Task's Agent and Skill, a decision's paths) is a function in `panels`. What it writes as
// text, with no page, is in modeler-text.js.

import {camel, cronOf, everies, operators, readCondition, readSchedule, resultFields, toCamunda, unary, weekdays} from "/modeler-text.js"

// From the server (modelerFacts in Checks.kt): what Ants calls each element it runs and what it does there (the
// palette's tooltip and the panel's explanation), the Built-in Tasks by topic with their name and what each does, the start-form field types by the
// `ants.type` property each field carries (the engine only knows `string`), Items are Tickets' property and help, the
// Process's Profile property, help and empty choice, Ants' own Run variables, and each User Task kind with its fields
// and Result.
const {kinds, builtIns, fieldTypes, itemsAreTickets, profile, runVariables, userTasks} = JSON.parse(document.getElementById("canvas").dataset.facts)

// The three Tasks Ants runs, as the BPMN each one is, and their colour on the diagram and in the palette.
const tasks = {
    agent: {type: "bpmn:ServiceTask", attrs: {"camunda:type": "external", "camunda:topic": "agent"}, icon: "bpmn-icon-service-task ants-pal-agent", marker: "ants-agent"},
    user: {type: "bpmn:UserTask", attrs: {}, icon: "bpmn-icon-user-task ants-pal-user", marker: "ants-user"},
    "built-in": {type: "bpmn:ServiceTask", attrs: {"camunda:type": "external", "camunda:topic": "clone-repos"}, icon: "bpmn-icon-screw-wrench ants-pal-builtin", marker: "ants-builtin"},
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
        "create.subprocess-expanded": {...entries["create.subprocess-expanded"], title: `${kinds.subprocess.name}: ${kinds.subprocess.explain}`},
        "create.call-activity": creating("activity", "bpmn-icon-call-activity", "call", shape({type: "bpmn:CallActivity"})),
        "create.text-annotation": creating("artifact", "bpmn-icon-text-annotation", "annotation", shape({type: "bpmn:TextAnnotation"})),
        "create.group": creating("artifact", "bpmn-icon-group", "group", shape({type: "bpmn:Group"})),
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
    "none-start-event", "none-start", "timer-start", "none-end-event", "none-end", "timer-intermediate-catch", "timer-boundary",
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
    await modeler.importXML(toCamunda(bpmn))
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
                el("label", {title: "Used by the Schedule; Repos as names separated by commas"}, "Stored value ", input(f, "stored", {placeholder: "e.g. api, web"})), el("br"),
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
async function offeredAt(element) {
    const {xml} = await modeler.saveXML()
    const response = await fetch("/processes/inputs", {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml, element: element.id})})
    return response.json()
}

// What a decision's condition can read: the start form's fields, the Results of the Tasks before [element], each with
// the Task it is from, and their run counts, Ants' own variables and a multi-instance's item.
async function fieldsBefore(element) {
    const registry = modeler.get("elementRegistry")
    const results = registry.filter(e => !e.labelTarget && ["agent", "user", "built-in", "call", "subprocess"].includes(kindOf(e)))
    const startFields = registry.filter(e => e.type === "bpmn:StartEvent").flatMap(e => readForm(e.businessObject).map(field => field.id))
    return resultFields(await offeredAt(element), Object.fromEntries(results.map(e => [e.id, nameOf(e)])), new Set(results.filter(e => tasks[kindOf(e)]).map(e => e.id)),
        startFields, runVariables)
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
            modeling.updateProperties(flow, {conditionExpression: modeler.get("bpmnFactory").create("bpmn:FormalExpression", {body: expression})})
        } finally {
            quiet = false
        }
    }
    field.onchange = op.onchange = value.oninput = write
    value.hidden = unary.has(read.op)
    const makeDefault = el("input", {type: "radio", name: "default-" + gateway.id, checked: isDefault, onchange: () => {
        modeling.updateProperties(flow, {conditionExpression: undefined})
        modeling.updateProperties(gateway, {default: flow.businessObject})
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

// The Schedule in words (readSchedule and cronOf), any other cron under Advanced.
function schedulePanel(element) {
    const definition = element.businessObject.eventDefinitions[0]
    const schedule = readSchedule(definition.timeCycle?.body || "0 0 8 * * *")
    const every = el("select", {}, ...Object.entries(everies).map(([value, text]) => el("option", {value, textContent: text, selected: value === schedule.every})))
    const at = el("input", {type: "time", value: schedule.at || "08:00"})
    const on = el("select", {}, ...Object.entries(weekdays).map(([value, text]) => el("option", {value, textContent: text, selected: value === schedule.on})))
    const dom = el("input", {type: "number", min: 1, max: 31, value: schedule.dom || 1, placeholder: "e.g. 1"})
    const cron = el("input", {value: schedule.cron, placeholder: "e.g. 0 0 8 * * MON-FRI", size: 20})
    const parts = {at: el("label", {}, " at ", at), on: el("label", {}, " on ", on), dom: el("label", {}, " on day ", dom), cron: el("label", {}, " cron ", cron)}
    const show = () => {
        parts.at.hidden = ["hour", "advanced"].includes(every.value)
        parts.on.hidden = every.value !== "week"
        parts.dom.hidden = every.value !== "month"
        parts.cron.hidden = every.value !== "advanced"
    }
    const write = () => {
        show()
        const body = cronOf({every: every.value, at: at.value, on: on.value, dom: dom.value, cron: cron.value.trim()})
        if (every.value !== "advanced") cron.value = body
        quiet = true
        try {
            modeler.get("modeling").updateModdleProperties(element, definition, {
                timeDuration: undefined, timeDate: undefined,
                timeCycle: body ? modeler.get("bpmnFactory").create("bpmn:FormalExpression", {body}) : undefined,
            })
        } finally {
            quiet = false
        }
    }
    every.onchange = on.onchange = write
    at.oninput = dom.oninput = cron.oninput = write
    show()
    if (!definition.timeCycle?.body) write() // a new timer start gets every day at 08:00
    return el("div", {},
        el("h3", {textContent: "Schedule"}),
        el("p", {}, el("label", {}, "Every ", every), parts.at, parts.on, parts.dom, parts.cron),
        el("p", {textContent: "Every required start-form field needs a stored value, since a scheduled Run starts with no one there. Pause or remove the Schedule on the Process page."}))
}
panels["timer-start"] = element => el("div", {}, schedulePanel(element), startForm(element))

// The Process: the Profile (a Vault and a Source map) its Runs use unless the start form picks another, as its
// Profile property. Offered once the settings have more than one Profile, or when the Process names one.
// Above it, how to draw a Process.
panels.process = element => {
    const box = el("div", {}, explainHtml(`<b>Drawing a Process</b><ol>
        <li>A Run begins at the <b>start event</b> (its start form) and finishes at an <b>end event</b>.</li>
        <li>Add work from the bottom of the palette, or click a shape and pick the next step from its menu: <b>Agent</b>, <b>User</b> or <b>Built-in Task</b>.</li>
        <li>Choose between paths with a <b>decision</b> (◇ ×). Loop by drawing a path back to an earlier Task.</li>
        <li>Run branches side by side with two <b>parallel gateways</b> (◇ +): one splits, one joins.</li>
        <li>Fix everything under <b>Checks</b>, then <b>Save and deploy</b>.</li></ol>`))
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

// The Agent Task: its Agent from a live dropdown, then one of the Skills that Agent lists, one input per name the Skill
// declares (its pickers offer only start-form fields, Run variables and earlier Results), the Vault and Answer History
// toggles (defaulting from the Skill), resume, and a result-schema override. All of it lives in the Task's
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

// ＋ New Agent and ＋ New Skill: the create form (/agents/new, /skills/new) in a modal over the modeler. The modeler
// page never navigates, so the diagram, its unsaved changes and the selection stay. Create and use lands the frame on
// the new one's page; [use] then picks that name on the Task. The modal's header carries the form's title and its
// Create and use, so the frame shows the form alone; [who] says who runs a new Skill.
function createAndUse(kind, src, use, who = "") {
    const title = kind === "agents" ? "New Agent" : "New Skill"
    const frame = el("iframe", {src, title, className: "mb"})
    const create = el("button", {type: "button", className: "primary", textContent: "Create and use",
        onclick: () => frame.contentDocument.querySelector("input[name=use]")?.form.requestSubmit()})
    const header = el("form", {method: "dialog", className: "mh"}, el("b", {className: `ent ent-${kind.slice(0, -1)}`, textContent: title}),
        el("span", {className: "muted small", textContent: "Esc to close"}), el("span", {className: "sp"}),
        el("button", {className: "ghost", title: "Close", textContent: "Cancel"}), create)
    const dialog = el("dialog", {className: "modal create"}, header, who, frame)
    dialog.onclose = () => dialog.remove()
    frame.onload = () => {
        const name = frame.contentWindow.location.pathname.match(new RegExp(`^/${kind}/([^/]+)$`))?.[1]
        if (name && name !== "new") {
            dialog.close()
            return use(decodeURIComponent(name))
        }
        // The form, or the form refusing a save.
        const page = frame.contentDocument
        page.querySelector(".ph")?.remove()
        page.querySelector("input[name=use]")?.form.querySelectorAll("button.primary").forEach(button => button.hidden = true)
    }
    document.body.append(dialog)
    dialog.showModal()
}

panels.agent = element => {
    const box = el("div", {}, el("p", {textContent: "Loading the Agents and Skills…"}))
    agentPanel(element, box)
    return box
}

// The fields of the Result a Skill promises, as later Tasks read them, from the Task's override when it has one.
function promised(skill, override) {
    try {
        return Object.keys((override ? JSON.parse(override) : skill?.output)?.properties || {})
    } catch {
        return []
    }
}

async function agentPanel(element, box) {
    const [catalogue, offered] = await Promise.all([fetch("/catalogue.json").then(response => response.json()), offeredAt(element)])
    const bo = element.businessObject
    const props = agentProperties(bo)
    const agent = catalogue.agents.find(a => a.name === props["ants.agent"])
    const skill = catalogue.skills.find(s => s.name === props["ants.skill"])
    const options = (names, current, missing, text = name => name) => [el("option", {value: "", textContent: "Choose…", selected: !current}),
        ...(current && !names.includes(current) ? [el("option", {value: current, textContent: `${current} (${missing})`, selected: true})] : []),
        ...names.map(name => el("option", {value: name, textContent: text(name), selected: name === current}))]
    // One input per name the Skill [name] declares, keeping what the user already filled in.
    const skillWrite = name => {
        const kept = Object.fromEntries(agentInputs(bo))
        return [{"ants.skill": name || undefined}, (catalogue.skills.find(s => s.name === name)?.inputs || []).map(input => [input, kept[input] || ""])]
    }
    const pickSkill = name => writeAgentTask(element, ...skillWrite(name), true)
    // Picking an Agent picks its first Skill.
    const pickAgent = name => {
        const [changes, inputs] = skillWrite(catalogue.agents.find(a => a.name === name)?.skills[0])
        writeAgentTask(element, {"ants.agent": name || undefined, ...changes}, inputs, true)
    }
    const described = name => [name, catalogue.agents.find(a => a.name === name)?.description].filter(Boolean).join(" · ")
    const agentSelect = el("select", {onchange: () => pickAgent(agentSelect.value)},
        ...options(catalogue.agents.map(a => a.name), props["ants.agent"], "not found", described))
    const skillSelect = el("select", {disabled: !agent, onchange: () => pickSkill(skillSelect.value)},
        ...options(agent?.skills || [], props["ants.skill"], "not listed by this Agent"))
    const newAgent = el("button", {type: "button", className: "ghost small", textContent: "＋ New Agent", onclick: () =>
        createAndUse("agents", "/agents/new?use" + (props["ants.skill"] ? "&skills=" + encodeURIComponent(props["ants.skill"]) : ""), async name => {
            catalogue.agents = (await (await fetch("/catalogue.json")).json()).agents
            pickAgent(name)
        })})
    // The Skill's page adds it to the Task's Agent when that Agent is the user's own.
    const who = el("div", {className: "who"}, !agent ? note("warn", "This Task has no Agent yet. The Skill is created, and you add it to an Agent's Skills it runs.")
        : agent.own ? note("okbox", `Added to ${agent.name}'s Skills, and picked on this Task.`)
            : note("warn", `${agent.name} is in ${agent.folder} and read-only, so the Skill can't be added to it. Copy the Agent, or pick one of your own, then choose this Skill.`))
    const newSkill = el("button", {type: "button", className: "ghost small", textContent: "＋ New Skill", onclick: () =>
        createAndUse("skills", "/skills/new?use=" + encodeURIComponent(props["ants.agent"] || ""), async name => {
            catalogue.skills = (await (await fetch("/catalogue.json")).json()).skills
            pickSkill(name)
        }, who)})
    const fields = promised(skill, props["ants.result-schema"])
    const references = el("datalist", {id: "ants-references"}, ...offered.map(value => el("option", {value})))
    const inputs = agentInputs(bo).map(([name, value], i) => {
        const input = el("input", {className: "mono grow", value, placeholder: `e.g. \${${name}}`, oninput: () => {
            const all = agentInputs(bo)
            all[i][1] = input.value
            writeAgentTask(element, {}, all)
        }})
        input.setAttribute("list", "ants-references")
        return el("label", {className: "row"}, el("code", {textContent: name}), input)
    })
    const check = (label, checked, onchange) => el("label", {}, el("input", {type: "checkbox", checked, onchange}), " " + label)
    const toggle = (name, label, fallback) => check(label, props[name] === undefined ? fallback : props[name] === "true",
        event => writeAgentTask(element, {[name]: String(event.target.checked)}))
    const override = el("textarea", {
        rows: 4, value: props["ants.result-schema"] || "",
        placeholder: skill?.output ? JSON.stringify(skill.output) : "The Skill promises no result schema: give one here to read its Result's fields",
        oninput: () => writeAgentTask(element, {"ants.result-schema": override.value.trim() || undefined}),
    })
    box.replaceChildren(
        field("Agent", "Who does it.", agentSelect, newAgent),
        props["ants.agent"] && !agent ? note("warn", `No Agent folder holds "${props["ants.agent"]}".`) : "",
        field("Skill", "One of the Agent's Skills: the one job this Task does.", skillSelect, newSkill),
        skill ? el("div", {className: "help"}, skill.description || "", skill.description ? el("br") : "", "Later Tasks can read: ",
            ...(fields.length ? fields.flatMap(f => [el("code", {textContent: `\${${element.id}.output.${f}}`}), " "]) : [el("code", {textContent: `\${${element.id}.summary}`})])) : "",
        el("div", {className: "fld"}, el("label", {className: "f", textContent: "Inputs"}),
            note("help", "What it reads: start-form fields, Run variables and Results of Tasks before it in the diagram."), references,
            ...(inputs.length ? inputs : [note("muted small", skill ? "The Skill declares no inputs." : "Pick a Skill to see its inputs.")])),
        el("div", {className: "fld"}, el("label", {className: "f", textContent: "When the flow comes back"}),
            check("Resume the session (Round answers, Send back)", props["ants.resume"] === "true",
                event => writeAgentTask(element, {"ants.resume": event.target.checked ? "true" : undefined}))),
        el("div", {className: "fld"}, el("label", {className: "f", textContent: "Knowledge"}),
            toggle("ants.vault", "Vault and Source map", skill?.vault || false), toggle("ants.answer-history", "Answer History", skill?.answerHistory || false)),
        field("Result schema", "Overrides the Skill's result.schema.json, so an unmodified Skill can still answer in JSON.", override),
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
        note("warn", kind ? kinds.task.explain : "Ants doesn't run this element, so Save and deploy will refuse it."),
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

fetch(canvas.dataset.src)
    .then(response => response.text())
    .then(xml => modeler.importXML(toCamunda(xml)))
    .then(() => {
        modeler.get("canvas").zoom("fit-viewport", "auto")
        explain(modeler.get("canvas").getRootElement())
    })

document.getElementById("save").onclick = async () => {
    const {xml} = await modeler.saveXML({format: true})
    const response = await fetch(document.getElementById("save").dataset.action, {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml})})
    if (response.ok) location = response.url
    else document.getElementById("refusal").textContent = await response.text()
}

// The live Checks: everything Save and deploy would refuse, from the Checks it runs, and the warnings, under a summary
// of how many to fix. Save and deploy is disabled while any Check fails; clicking a Check selects its element, inside
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
