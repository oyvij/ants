import {cronOf, everies, readSchedule, weekdays} from "/modeler-text.js"

const options = (select, choices) => select.append(...Object.entries(choices).map(([value, text]) => new Option(text, value)))

function when(fieldset) {
    const control = name => fieldset.querySelector(`[data-when=${name}]`)
    const [every, at, on, dom, cron, advanced] = ["every", "at", "on", "dom", "cron", "advanced"].map(control)
    const parts = Object.fromEntries([...fieldset.querySelectorAll("[data-part]")].map(part => [part.dataset.part, part]))
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
}

document.querySelectorAll("fieldset:has([data-when=every])").forEach(when)

document.getElementById("schedule-open").onclick = () => {
    document.getElementById("when").hidden = false
    document.getElementById("when-every").focus()
}

for (const edit of document.querySelectorAll("[data-edit]")) {
    const drawer = document.getElementById(edit.getAttribute("aria-controls"))
    const toggle = open => {
        drawer.hidden = !open
        edit.setAttribute("aria-expanded", open)
    }
    edit.onclick = () => {
        toggle(drawer.hidden)
        if (!drawer.hidden) drawer.querySelector("input:not([type=hidden])").focus()
    }
    drawer.querySelector("[data-close]").onclick = () => {
        toggle(false)
        edit.focus()
    }
}
