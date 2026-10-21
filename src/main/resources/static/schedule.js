import {cronOf, everies, readSchedule, weekdays} from "/modeler-text.js"

const when = document.getElementById("when")
const every = document.getElementById("when-every")
const at = document.getElementById("when-at")
const on = document.getElementById("when-on")
const dom = document.getElementById("when-dom")
const cron = document.getElementById("when-cron")
const advanced = document.getElementById("when-advanced")
const parts = Object.fromEntries([...when.querySelectorAll("[data-part]")].map(part => [part.dataset.part, part]))

const options = (select, choices) => select.append(...Object.entries(choices).map(([value, text]) => new Option(text, value)))
options(every, everies)
options(on, weekdays)

function show() {
    parts.at.hidden = ["hour", "advanced"].includes(every.value)
    parts.on.hidden = every.value !== "week"
    parts.dom.hidden = every.value !== "month"
    if (every.value === "advanced") advanced.open = true
}

function read() {
    const schedule = readSchedule(cron.value)
    every.value = schedule.every
    if (schedule.at) at.value = schedule.at
    if (schedule.on) on.value = schedule.on
    if (schedule.dom) dom.value = schedule.dom
    show()
}

function write() {
    show()
    if (every.value !== "advanced") cron.value = cronOf({every: every.value, at: at.value, on: on.value, dom: dom.value})
}

every.onchange = on.onchange = write
at.oninput = dom.oninput = write
cron.oninput = read
read()

document.getElementById("schedule-open").onclick = () => {
    when.hidden = false
    every.focus()
}
