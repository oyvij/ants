// The modeler page: camunda-bpmn-js with the palette trimmed to what Ants runs, "append Agent / User / Built-in Task"
// on the context pad, a panel that explains the selected element, and Save and deploy through Ants' deploy path.
// Each kind of element is one entry in `kinds`; a later panel for a kind (the Agent Task's Agent and Skill, a
// gateway's conditions) is a function in `panels`. What it writes as text, with no page, is in modeler-text.js.

import {camel, cronOf, everies, operators, readCondition, readSchedule, toCamunda, unary, weekdays} from "/modeler-text.js"

// What Ants calls each element it runs, and what it does there: the palette's tooltip and the panel's explanation.
const kinds = {
    process: {name: "Process", explain: "How one kind of job gets done. Every Run of it starts at its start event with the start form's fields."},
    start: {name: "Start event", explain: "Where a Run begins. A Process has exactly one."},
    "timer-start": {name: "Timer start event", explain: "Where a Run begins; the Process's Schedule says when it starts on its own."},
    end: {name: "End event", explain: "Where a path ends. The Run ends when every path has."},
    timer: {name: "Timer", explain: "Waits for a time or a duration, then goes on. Dropped on the edge of a User Task, it becomes a timer boundary."},
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

// From the server (modelerFacts in Checks.kt): the Built-in Tasks by topic, the start-form field types by the
// `ants.type` property each field carries (the engine only knows `string`), and Items are Tickets' property and help.
const {builtIns, fieldTypes, itemsAreTickets} = JSON.parse(document.getElementById("canvas").dataset.facts)

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

// The condition builder: a Result field, an operator and a value write each path's JUEL, shown under it, and one path
// is the default. A condition it can't read back shows as is, and the builder replaces it once the user edits it.

// What a condition can read: each Task's Result (an Agent Task's under `output`), its run count and the start form.
function resultFields() {
    const fields = []
    for (const element of modeler.get("elementRegistry").getAll()) {
        const kind = kindOf(element)
        if (kind === "agent") fields.push(`${element.id}.output.`)
        if (kind === "user" || kind === "built-in" || kind === "call") fields.push(`${element.id}.`)
        if (["agent", "user", "built-in"].includes(kind)) fields.push(`runs.${element.id}`)
        if (kind === "start" || kind === "timer-start") fields.push(...readForm(element.businessObject).map(f => f.id).filter(Boolean))
    }
    return el("datalist", {id: "result-fields"}, ...fields.map(value => el("option", {value})))
}

// One path out of [gateway]: the default, or its condition.
function conditionRow(gateway, flow) {
    const modeling = modeler.get("modeling")
    const isDefault = gateway.businessObject.default === flow.businessObject
    const body = flow.businessObject.conditionExpression?.body || ""
    const read = readCondition(body) || {field: "", op: "equals", value: ""}
    const shown = el("code", {textContent: isDefault ? "(default: taken when no condition holds)" : body || "(no condition: always taken, so give it one)"})
    const field = el("input", {value: read.field, placeholder: "review.output.verdict", size: 24})
    field.setAttribute("list", "result-fields")
    const op = el("select", {}, ...Object.keys(operators).map(name => el("option", {value: name, textContent: name, selected: name === read.op})))
    const value = el("input", {value: read.value, size: 10})
    const write = () => {
        value.hidden = unary.has(op.value)
        if (!field.value) return
        const expression = operators[op.value](field.value.trim(), value.value)
        shown.textContent = expression
        quiet = true
        try {
            modeling.updateProperties(flow, {conditionExpression: modeler.get("bpmnFactory").create("bpmn:FormalExpression", {body: expression})})
        } finally {
            quiet = false
        }
    }
    field.oninput = value.oninput = op.onchange = write
    value.hidden = unary.has(read.op)
    const makeDefault = el("input", {type: "radio", name: "default-" + gateway.id, checked: isDefault, onchange: () => {
        modeling.updateProperties(flow, {conditionExpression: undefined})
        modeling.updateProperties(gateway, {default: flow.businessObject})
        explain(modeler.get("selection").get()[0] || gateway)
    }})
    const target = flow.target.businessObject.name || flow.target.id
    return el("fieldset", {},
        el("legend", {textContent: `To ${target}`}),
        el("label", {}, makeDefault, " Default path"), el("br"),
        isDefault ? "" : el("span", {}, field, " ", op, " ", value),
        el("br"), shown)
}

panels.exclusive = gateway => {
    const out = gateway.outgoing.filter(flow => flow.type === "bpmn:SequenceFlow")
    if (out.length < 2) return el("p", {textContent: "With one path out it only merges, so it needs no conditions."})
    return el("div", {}, el("h3", {textContent: "Conditions"}), resultFields(), ...out.map(flow => conditionRow(gateway, flow)))
}
panels.flow = flow => kindOf(flow.source) === "exclusive" && flow.source.outgoing.length > 1
    ? el("div", {}, resultFields(), conditionRow(flow.source, flow)) : ""

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
    const value = el("input", {value: definition[current]?.body || "", placeholder: "PT30M"})
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
    return el("div", {}, el("label", {}, kind, " ", value), hint)
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
    const dom = el("input", {type: "number", min: 1, max: 31, value: schedule.dom || 1})
    const cron = el("input", {value: schedule.cron, placeholder: "0 0 8 * * MON-FRI", size: 20})
    const parts = {at: el("label", {}, " at ", at), on: el("label", {}, " on ", on), dom: el("label", {}, " on day ", dom), cron: el("label", {}, " ", cron)}
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
        el("p", {}, "Every ", every, parts.at, parts.on, parts.dom, parts.cron),
        el("p", {textContent: "Every required start-form field needs a stored value, since a scheduled Run starts with no one there. Pause or remove the Schedule on the Process page."}))
}
panels["timer-start"] = element => el("div", {}, schedulePanel(element), startForm(element))

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
    if (inputs) values.push(factory.create("camunda:InputOutput", {inputParameters: inputs.map(([name, value]) => factory.create("camunda:InputParameter", {name, value}))}))
    quiet = !redraw
    try {
        modeler.get("modeling").updateProperties(element, {extensionElements: factory.create("bpmn:ExtensionElements", {values})})
    } finally {
        quiet = false
    }
}

// ＋ New Agent and ＋ New Skill: the create form (/agents/new, /skills/new) in a modal over the modeler. The modeler
// page never navigates, so the diagram, its unsaved changes and the selection stay. Create and use lands the frame on
// the new one's page; [use] then picks that name on the Task.
function createAndUse(kind, src, use) {
    const title = kind === "agents" ? "New Agent" : "New Skill"
    const frame = el("iframe", {src, title, className: "mb"})
    frame.style = "border: 0; display: block; padding: 0; width: 100%"
    const close = el("button", {className: "ghost", title: "Close", ariaLabel: "Close", textContent: "✕"})
    const header = el("form", {method: "dialog", className: "mh"}, el("b", {textContent: title}), el("span", {className: "sp"}),
        el("span", {className: "muted small", textContent: "Esc to close"}), close)
    const dialog = el("dialog", {className: "modal"}, header, frame)
    dialog.onclose = () => dialog.remove()
    frame.onload = () => {
        const name = frame.contentWindow.location.pathname.match(new RegExp(`^/${kind}/([^/]+)$`))?.[1]
        if (!name || name === "new") return // the form, or the form refusing a save
        dialog.close()
        use(decodeURIComponent(name))
    }
    document.body.append(dialog)
    dialog.showModal()
}

panels.agent = element => {
    const box = el("div", {}, el("p", {textContent: "Loading the Agents and Skills…"}))
    agentPanel(element, box)
    return box
}

async function agentPanel(element, box) {
    const {xml} = await modeler.saveXML()
    const [catalogue, offered] = await Promise.all([
        fetch("/catalogue.json").then(response => response.json()),
        fetch("/processes/inputs", {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml, element: element.id})}).then(response => response.json()),
    ])
    const bo = element.businessObject
    const props = agentProperties(bo)
    const agent = catalogue.agents.find(a => a.name === props["ants.agent"])
    const skill = catalogue.skills.find(s => s.name === props["ants.skill"])
    const options = (names, current, missing) => [el("option", {value: "", textContent: "Pick one", selected: !current}),
        ...(current && !names.includes(current) ? [el("option", {value: current, textContent: `${current} (${missing})`, selected: true})] : []),
        ...names.map(name => el("option", {value: name, textContent: name, selected: name === current}))]
    const agentSelect = el("select", {onchange: () => writeAgentTask(element, {"ants.agent": agentSelect.value || undefined}, undefined, true)},
        ...options(catalogue.agents.map(a => a.name), props["ants.agent"], "not found"))
    const pickSkill = (name, skills) => {
        const picked = skills.find(s => s.name === name)
        const kept = Object.fromEntries(agentInputs(bo))
        // One input per declared name, keeping what the user already filled in.
        writeAgentTask(element, {"ants.skill": name || undefined}, (picked?.inputs || []).map(input => [input, kept[input] || ""]), true)
    }
    const skillSelect = el("select", {disabled: !agent, onchange: () => pickSkill(skillSelect.value, catalogue.skills)},
        ...options(agent?.skills || [], props["ants.skill"], "not listed by this Agent"))
    const newAgent = el("button", {type: "button", textContent: "＋ New Agent", onclick: () =>
        createAndUse("agents", "/agents/new?use", name => writeAgentTask(element, {"ants.agent": name}, undefined, true))})
    // The Skill's page adds it to the Task's Agent when that Agent is the user's own.
    const newSkill = el("button", {type: "button", textContent: "＋ New Skill", onclick: () =>
        createAndUse("skills", "/skills/new?use=" + encodeURIComponent(props["ants.agent"] || ""),
            async name => pickSkill(name, (await (await fetch("/catalogue.json")).json()).skills))})
    const references = el("datalist", {id: "ants-references"}, ...offered.map(value => el("option", {value})))
    const inputs = agentInputs(bo).map(([name, value], i) => {
        const field = el("input", {value, size: 28, oninput: () => {
            const all = agentInputs(bo)
            all[i][1] = field.value
            writeAgentTask(element, {}, all)
        }})
        field.setAttribute("list", "ants-references")
        return el("p", {}, el("label", {}, name, " ", field))
    })
    const toggle = (name, label, fallback) => el("label", {}, el("input", {
        type: "checkbox", checked: props[name] === undefined ? fallback : props[name] === "true",
        onchange: event => writeAgentTask(element, {[name]: String(event.target.checked)}),
    }), " ", label)
    const override = el("textarea", {
        rows: 4, cols: 32, value: props["ants.result-schema"] || "",
        placeholder: skill?.output ? JSON.stringify(skill.output) : "The Skill promises no result schema: give one here to read its Result's fields",
        oninput: () => writeAgentTask(element, {"ants.result-schema": override.value.trim() || undefined}),
    })
    box.replaceChildren(
        el("p", {}, el("label", {}, "Agent ", agentSelect), " ", newAgent),
        el("p", {}, el("label", {}, "Skill ", skillSelect), " ", newSkill),
        skill?.description ? el("p", {textContent: skill.description}) : "",
        el("h3", {textContent: "Inputs"}), references,
        ...(inputs.length ? inputs : [el("p", {textContent: skill ? "The Skill declares no inputs." : "Pick a Skill to see its inputs."})]),
        el("h3", {textContent: "Context"}),
        el("p", {}, toggle("ants.vault", "Vault and Source map", skill?.vault || false)), el("p", {}, toggle("ants.answer-history", "Answer History", skill?.answerHistory || false)),
        el("p", {}, el("label", {}, el("input", {
            type: "checkbox", checked: props["ants.resume"] === "true",
            onchange: event => writeAgentTask(element, {"ants.resume": event.target.checked ? "true" : undefined}),
        }), " Resume its session when the flow comes back")),
        el("h3", {textContent: "Result schema override"}), override,
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
    const {xml} = await modeler.saveXML()
    const [processes, offered] = await Promise.all([
        fetch("/processes.json").then(response => response.json()),
        fetch("/processes/inputs", {method: "POST", body: new URLSearchParams({_csrf: csrf, bpmn: xml, element: element.id})}).then(response => response.json()),
    ])
    const bo = element.businessObject
    const current = bo.calledElement
    const called = processes.find(p => p.key === current)
    const select = el("select", {
        onchange: () => {
            const picked = processes.find(p => p.key === select.value)
            const kept = Object.fromEntries(callInputs(bo))
            writeCall(element, {calledElement: select.value || undefined}, (picked?.fields || []).map(f => [f.name, kept[f.name] || ""]), true)
        },
    }, el("option", {value: "", textContent: "Pick one", selected: !current}),
        ...(current && !called ? [el("option", {value: current, textContent: `${current} (not deployed)`, selected: true})] : []),
        ...processes.map(p => el("option", {value: p.key, textContent: p.name, selected: p.key === current})))
    const labels = Object.fromEntries((called?.fields || []).map(f => [f.name, f.label + (f.required ? " (required)" : "")]))
    const references = el("datalist", {id: "ants-call-references"}, ...offered.map(value => el("option", {value})))
    const inputs = callInputs(bo).map(([name, value], i) => {
        const field = el("input", {value, size: 28, oninput: () => {
            const all = callInputs(bo)
            all[i][1] = field.value
            writeCall(element, {}, all)
        }})
        field.setAttribute("list", "ants-call-references")
        return el("p", {}, el("label", {}, labels[name] || name, " ", field))
    })
    box.replaceChildren(
        el("p", {}, el("label", {}, "Process ", select)),
        el("h3", {textContent: "Inputs"}), references,
        ...(inputs.length ? inputs : [el("p", {textContent: called ? "The Process's start form has no fields." : "Pick a Process to see its start-form fields."})]),
    )
}

function explain(element) {
    const target = element.labelTarget || element
    // Importing clears the diagram first, which selects the implicit root: no BPMN behind it yet, so nothing to explain.
    // Throwing here would abort the import.
    if (!target.businessObject) return
    const kind = kindOf(target)
    panel.replaceChildren(kind
        ? el("div", {}, el("h2", {textContent: kinds[kind].name}), el("p", {textContent: kinds[kind].explain}), panels[kind]?.(target) || "", multiInstance(target))
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
    .then(xml => modeler.importXML(toCamunda(xml)))
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
