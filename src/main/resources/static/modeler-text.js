// What the modeler writes into the diagram as text and reads back from it: the namespace, a field's variable name, a
// path's condition (and what it can read) and a Schedule's cron. Nothing here needs the page, so `node --test` checks it (src/test/js).

const OPERATON = "http://operaton.org/schema/1.0/bpmn"
const CAMUNDA = "http://camunda.org/schema/1.0/bpmn"

// camunda-bpmn-js reads and writes camunda:; Ants' deploy path turns it back into operaton:.
export const toCamunda = xml => xml.replaceAll(OPERATON, CAMUNDA)

// A field's variable name, derived from its label: "Work branch" is workBranch.
export const camel = label => label.toLowerCase().replace(/^[^a-z]+/, "").replace(/[^a-z0-9]+(.)?/g, (_, c) => c ? c.toUpperCase() : "")

// The condition builder's operators: a Result field, and for some a value, written as the path's JUEL.
export const operators = {
    "is true": f => `\${${f}}`,
    "is false": f => `\${!${f}}`,
    "is empty": f => `\${empty ${f}}`,
    "is not empty": f => `\${not empty ${f}}`,
    equals: (f, v) => `\${${f} == ${literal(v)}}`,
    "does not equal": (f, v) => `\${${f} != ${literal(v)}}`,
    "is more than": (f, v) => `\${${f} > ${literal(v)}}`,
    "is less than": (f, v) => `\${${f} < ${literal(v)}}`,
}
export const unary = new Set(["is true", "is false", "is empty", "is not empty"])
const literal = v => /^-?\d+(\.\d+)?$/.test(v) ? v : `'${v.replaceAll("\\", "\\\\").replaceAll("'", "\\'")}'`

// The builder's {field, op, value} for an expression it wrote, else null.
export function readCondition(body) {
    const field = "([\\w.]+)"
    const shapes = [
        [`^\\$\\{${field}\\}$`, "is true"], [`^\\$\\{!${field}\\}$`, "is false"],
        [`^\\$\\{empty ${field}\\}$`, "is empty"], [`^\\$\\{not empty ${field}\\}$`, "is not empty"],
        [`^\\$\\{${field} == (.+)\\}$`, "equals"], [`^\\$\\{${field} != (.+)\\}$`, "does not equal"],
        [`^\\$\\{${field} > (.+)\\}$`, "is more than"], [`^\\$\\{${field} < (.+)\\}$`, "is less than"],
    ]
    for (const [shape, op] of shapes) {
        const match = body.match(new RegExp(shape))
        if (!match) continue
        const value = match[2] === undefined ? "" : match[2].startsWith("'") && match[2].endsWith("'")
            ? match[2].slice(1, -1).replace(/\\(.)/g, "$1") : match[2]
        if (match[2] === undefined || literal(value) === match[2]) return {field: match[1], op, value}
    }
    return null
}

// What a decision's condition picks from, each [path, where it is from], of the `${…}` references [offered] before it:
// the start form's [startFields], the Results of [tasks] (each Task's id and name), then the run count of each of
// those that is [counted] in `runs`, Ants' own [runVariables], and anything else, a multi-instance's item.
export function resultFields(offered, tasks, counted, startFields, runVariables) {
    const paths = offered.map(ref => ref.slice(2, -1)), root = path => path.split(".")[0]
    const results = paths.filter(path => root(path) in tasks).map(path => [path, `from ${tasks[root(path)]}`])
    const roots = [...new Set(results.map(([path]) => root(path)))].filter(it => counted.has(it))
    const rest = paths.filter(path => !(root(path) in tasks) && !startFields.includes(path))
    return [
        ...paths.filter(path => startFields.includes(path)).map(path => [path, "from the start form"]),
        ...results, ...roots.map(it => [`runs.${it}`, `times ${tasks[it]} ran`]),
        ...rest.filter(path => runVariables.includes(root(path))).map(path => [path, "Ants' own"]),
        ...rest.filter(path => !runVariables.includes(root(path))).map(path => [path, "a multi-instance's item"]),
    ]
}

// The Schedule in words: every hour, day, weekday, week or month at a time, written as the cron Ants runs it on
// (seconds first, as Schedules.kt reads it back); any other cron under Advanced.
export const weekdays = {MON: "Monday", TUE: "Tuesday", WED: "Wednesday", THU: "Thursday", FRI: "Friday", SAT: "Saturday", SUN: "Sunday"}
export const everies = {hour: "hour", day: "day", weekday: "weekday", week: "week", month: "month", advanced: "Advanced (six fields)"}

export function readSchedule(cron) {
    const advanced = {every: "advanced", cron}
    const [s, m, h, dom, mon, dow, ...rest] = cron.trim().split(/\s+/)
    if (rest.length || dow === undefined || s !== "0" || mon !== "*") return advanced
    if (m === "0" && h === "*" && dom === "*" && dow === "*") return {every: "hour", cron}
    if (!/^\d+$/.test(h) || !/^\d+$/.test(m) || +h > 23 || +m > 59) return advanced
    const at = `${h.padStart(2, "0")}:${m.padStart(2, "0")}`
    if (dom === "*" && dow === "*") return {every: "day", at, cron}
    if (dom === "*" && dow === "MON-FRI") return {every: "weekday", at, cron}
    if (dom === "*" && weekdays[dow]) return {every: "week", at, on: dow, cron}
    if (dow === "*" && /^\d+$/.test(dom) && +dom >= 1 && +dom <= 31) return {every: "month", at, dom, cron}
    return advanced
}

export function cronOf({every, at, on, dom, cron}) {
    if (every === "advanced") return cron
    if (every === "hour") return "0 0 * * * *"
    const [h, m] = (at || "08:00").split(":").map(Number)
    return `0 ${m} ${h} ` + {day: "* * *", weekday: "* * MON-FRI", week: `* * ${on || "MON"}`, month: `${dom || 1} * *`}[every]
}

export const listedSkills = props => props["ants.skills"] === undefined ? null : props["ants.skills"].split(/[\s,]+/).filter(Boolean)

function linkedName(props, value, items, nameBeside) {
    const kept = props[nameBeside + value]
    return (items.find(item => item.id === value) || items.find(item => item.name === (kept ?? value)))?.name ?? kept ?? value
}

export function linkedProperties(props, catalogue) {
    const {nameBeside} = catalogue
    const named = Object.fromEntries(Object.entries(props).filter(([name]) => !name.startsWith(nameBeside)))
    if (props["ants.agent"] !== undefined) named["ants.agent"] = linkedName(props, props["ants.agent"], catalogue.agents, nameBeside)
    if (props["ants.skills"] !== undefined) named["ants.skills"] = listedSkills(props).map(value => linkedName(props, value, catalogue.skills, nameBeside)).join(", ")
    if (props["ants.script"] !== undefined) named["ants.script"] = linkedName(props, props["ants.script"], catalogue.scripts || [], nameBeside)
    return named
}

export function linkedChanges(props, changes, catalogue) {
    const {nameBeside} = catalogue
    const now = {...linkedProperties(props, catalogue), ...changes}
    const unmatched = Object.fromEntries([props["ants.agent"], props["ants.script"], ...(listedSkills(props) || [])].filter(value => props[nameBeside + value] !== undefined)
        .map(value => [props[nameBeside + value], [value, props[nameBeside + value]]]))
    const link = (name, items) => {
        const item = items.find(i => i.name === name)
        return item?.id ? [item.id, name] : unmatched[name] ?? [name, null]
    }
    const agent = now["ants.agent"] === undefined ? null : link(now["ants.agent"], catalogue.agents)
    const skills = listedSkills(now)?.map(name => link(name, catalogue.skills)) ?? null
    const script = now["ants.script"] === undefined ? null : link(now["ants.script"], catalogue.scripts || [])
    const names = Object.fromEntries(Object.keys(props).filter(name => name.startsWith(nameBeside)).map(name => [name, undefined]))
    for (const [id, name] of [agent, script, ...(skills || [])].filter(Boolean)) if (name) names[nameBeside + id] = name
    return {...changes, ...names, ...(agent ? {"ants.agent": agent[0]} : {}), ...(skills ? {"ants.skills": skills.map(([id]) => id).join(", ")} : {}), ...(script ? {"ants.script": script[0]} : {})}
}
