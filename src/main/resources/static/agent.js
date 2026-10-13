// The Agent page: the CLI picks which fieldsets the form posts (Claude's model, tools and Subagent tick, or codex's
// model and sandbox); a Skill chip's × and "Add a Skill…"; each tool's Limit… with a field per rule, ＋ Add and ×; and
// ＋ Add tool. The fields carry what is posted; the words the page shows come from the server.
const form = document.getElementById("agent")
const cli = form.elements.cli
cli.addEventListener("change", () => form.querySelectorAll("fieldset[data-cli]").forEach(set => set.disabled = set.dataset.cli !== cli.value))

const addSkill = document.getElementById("add-skill")
addSkill?.addEventListener("change", () => {
    const name = addSkill.value.trim()
    if (![...document.querySelectorAll("#skill-list option")].some(option => option.value === name)) return
    if (![...form.querySelectorAll(".chipsel .c input")].some(input => input.value === name)) {
        const chip = document.getElementById("skill-chip").content.firstElementChild.cloneNode(true)
        chip.querySelector("input").value = name
        chip.querySelector("svg").after(name)
        addSkill.before(chip)
    }
    addSkill.value = ""
})

function addRule(limits) {
    const row = Object.assign(document.createElement("div"), {className: "row"})
    row.innerHTML = '<input class="mono grow"><button type="button" class="ghost" title="Remove">×</button>'
    Object.assign(row.firstChild, {name: `limit.${limits.dataset.tool}`, placeholder: `e.g. ${limits.dataset.example}`})
    limits.querySelector("[data-add]").before(row)
    row.firstChild.focus()
}

const adder = form.querySelector(".add-tool")

function addTool() {
    const input = adder.querySelector("input"), name = input.value.trim()
    input.value = ""
    if (!name) return
    const listed = form.elements[`tool.${name}`]
    if (listed) {
        listed.checked = true
        listed.closest("details")?.setAttribute("open", "")
        return
    }
    const added = form.querySelector(".added")
    if ([...added.querySelectorAll("input")].some(box => box.value === name)) return
    const row = Object.assign(document.createElement("div"), {className: "trow"})
    row.innerHTML = '<label><input type="checkbox" name="other-tools" checked> <b class="mono"></b></label><span class="tdesc"><span class="twarn"></span></span><span></span>'
    row.querySelector("input").value = name
    row.querySelector("b").textContent = name
    row.querySelector(".twarn").textContent = `⚠ ${name.startsWith("mcp__") ? adder.dataset.mcp : adder.dataset.unknown}`
    added.append(row)
    added.hidden = false
}

adder?.querySelector("input").addEventListener("keydown", event => {
    if (event.key !== "Enter") return
    event.preventDefault()
    addTool()
})

form.addEventListener("click", event => {
    const button = event.target.closest("button[type=button]")
    if (!button) return
    if (button.title === "Remove") button.parentElement.remove() // a Skill's chip, or a Limit… rule
    else if (button.hasAttribute("data-limit")) {
        const limits = button.parentElement.querySelector(".tlim")
        limits.hidden = false
        button.replaceWith(document.createElement("span"))
        addRule(limits)
    } else if (button.hasAttribute("data-add")) addRule(button.parentElement)
    else if (button.parentElement === adder) addTool()
})
