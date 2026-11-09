// Export a Setup: ticking an item ticks what it uses (its row's data-uses), each used item says which ticked ones use
// it, and unticking one a ticked item uses warns that Import then needs it to exist.
const rows = [...document.querySelectorAll("form.export tr[data-key]")]
const byKey = new Map(rows.map(row => [row.dataset.key, row]))
const box = row => row.querySelector("input[type=checkbox]")
const uses = row => row.dataset.uses.split(" ").filter(Boolean)

function show() {
    for (const row of rows) {
        const users = rows.filter(user => box(user).checked && uses(user).includes(row.dataset.key)).map(user => user.dataset.name)
        row.querySelector(".used-by").textContent = users.length ? `used by ${users.join(", ")}` : ""
        const warning = row.querySelector(".warn")
        warning.hidden = box(row).checked || !users.length
        warning.textContent = `${users.join(", ")} ${users.length > 1 ? "use" : "uses"} this: Import then needs it to exist already.`
    }
}

for (const row of rows) box(row).addEventListener("change", () => {
    if (box(row).checked) for (const key of uses(row)) { const used = byKey.get(key); if (used) box(used).checked = true }
    show()
})
show()
