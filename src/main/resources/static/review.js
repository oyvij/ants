// Review changes.
//
// The Story tab walks one story step at a time, its place in the hash, #s<story>-<step> (1-based), and the list of
// stories without one; its buttons go back and next, show the diff, the details (Why, Flow, Nudge) and a comment form on
// the step, and back to the stories. No keys: Ants is clicked, and a key would be lost from the comments typed here.
// A step comment's form posts to a URL with the step's hash, which the redirect back keeps.
// Without this script every story, step, its details and its comment form show at once; this script hides them.
//
// By repo: a click on a diff line's number picks it for a line comment, filling the comment form's repo, file, side
// and lines; a shift-click on the same file and side takes in the lines between. Without this script the form is
// filled by hand.
const walk = document.getElementById("walkMain")
if (walk) {
    const at = () => (location.hash.match(/^#s(\d+)-(\d+)$/) || []).slice(1).map(Number)
    const step = () => walk.querySelector(".walkstory:not([hidden]) .step:not([hidden])")

    const show = () => {
        const [story, n] = at()
        document.getElementById("stories").hidden = story != null
        walk.querySelectorAll(".walkstory").forEach(it => {
            it.hidden = Number(it.dataset.story) !== story
            it.querySelectorAll(".step").forEach(s => s.hidden = it.hidden || Number(s.dataset.step) !== n)
            it.querySelectorAll(".smenu a").forEach(a => a.classList.toggle("on", a.hash === `#s${story}-${n}`))
        })
        document.querySelectorAll(".spine .srow").forEach(a => a.classList.toggle("on", a.hash.startsWith(`#s${story}-`)))
        walk.querySelectorAll(".details, .stepcomment").forEach(it => it.hidden = true)
        walk.querySelectorAll("[data-details]").forEach(it => it.textContent = "Why, flow, nudge")
    }
    addEventListener("hashchange", show)
    show()

    const go = by => {
        const [story, n] = at()
        if (walk.querySelector(`.walkstory[data-story="${story}"] .step[data-step="${n + by}"]`)) location.hash = `#s${story}-${n + by}`
    }
    const diff = on => {
        walk.classList.toggle("diff", on)
        walk.querySelectorAll(".showdiff").forEach(it => it.checked = on)
    }
    const details = () => {
        const box = step().querySelector(".details")
        box.hidden = !box.hidden
        step().querySelector("[data-details]").textContent = box.hidden ? "Why, flow, nudge" : "Hide details"
    }
    const comment = () => {
        const form = step().querySelector(".stepcomment")
        form.hidden = false
        form.body.focus()
    }

    walk.addEventListener("click", event => {
        const button = event.target.closest("button[type=button]")
        if (!button) return
        if (button.dataset.go) go(Number(button.dataset.go))
        else if (button.hasAttribute("data-details")) details()
        else if (button.hasAttribute("data-comment")) comment()
        else if (button.hasAttribute("data-all")) location.hash = ""
    })
    walk.addEventListener("change", event => event.target.matches(".showdiff") && diff(event.target.checked))
}

const form = document.getElementById("line-comment")
let first = null

form && document.addEventListener("click", event => {
    const cell = event.target.closest("table.diff td")
    const row = cell?.closest("tr[data-line]")
    if (!row || cell.cellIndex > 1) return
    const file = row.closest("table").dataset.file
    const repo = row.closest("[data-repo]").dataset.repo
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
