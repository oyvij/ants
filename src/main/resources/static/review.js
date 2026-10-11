// Review changes: a click on a diff line's number picks it for a line comment, filling the comment form's repo, file,
// side and lines; a shift-click on the same file and side takes in the lines between. Without this script the form
// is filled by hand.
const form = document.getElementById("line-comment")
let first = null

document.addEventListener("click", event => {
    const cell = event.target.closest("table.diff td")
    const row = cell?.closest("tr[data-line]")
    if (!row || cell.cellIndex > 1) return
    const file = row.closest("table").dataset.file
    const repo = row.closest("section.repo").dataset.repo
    const line = Number(row.dataset.line)
    const extend = event.shiftKey && first && first.file === file && first.side === row.dataset.side
    if (!extend) first = {file, side: row.dataset.side, line}
    form.repo.value = repo
    form.file.value = file
    form.side.value = row.dataset.side
    form.from.value = Math.min(first.line, line)
    form.to.value = Math.max(first.line, line)
    document.querySelectorAll("tr.picked").forEach(it => it.classList.remove("picked"))
    row.closest("table").querySelectorAll(`tr[data-side="${first.side}"]`).forEach(it => {
        if (it.dataset.line >= Number(form.from.value) && it.dataset.line <= Number(form.to.value)) it.classList.add("picked")
    })
    form.body.focus()
})
