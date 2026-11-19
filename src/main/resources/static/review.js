// Review changes.
//
// The Story tab shows one story step at a time, by ?tab=story&story=<n>&step=<n> (1-based), or the list of stories
// without one; its buttons and links load the step they name. This script hides each step's details (Why, Flow,
// Nudge) and comment form behind buttons, shows the diff on demand, and sends an old #s<story>-<step> link to its step.
// The list of stories comes without its stale marks and Remainder while Ants still checks them; this script puts the
// list with them in place once they are done. Going from step to step keeps where the page is scrolled to, whether
// the Stories disclosure is open, and Show the diff, as the walk did when it was one page.
//
// By repo: a click on a diff line's number picks it for a line comment, filling the comment form's repo, file, side
// and lines; a shift-click on the same file and side takes in the lines between. Without this script the form is
// filled by hand.
//
// A repo whose diff didn't come with the page loads it when its "Show the diff" or a Files link to it is clicked.
// Without this script the link opens the page with that repo's diff in it.
const old = location.hash.match(/^#s(\d+)-(\d+)$/)
if (old) location.replace(`${location.pathname}?tab=story&story=${old[1]}&step=${old[2]}`)

const walk = document.getElementById("walkMain")
if (walk) {
    const kept = JSON.parse(sessionStorage.getItem("walk") || "null")
    sessionStorage.removeItem("walk")
    if (kept?.path === location.pathname) {
        document.querySelector(".spine").open = kept.open
        walk.classList.toggle("diff", kept.diff)
        walk.querySelectorAll(".showdiff").forEach(it => it.checked = kept.diff)
        scrollTo(0, kept.y)
    }
    // A click on to another step or the list of stories, not one away from the Story tab.
    document.addEventListener("click", event => {
        if (!event.target.closest(".spine a, #stories a, button[form^=walk]")) return
        sessionStorage.setItem("walk", JSON.stringify({path: location.pathname, y: scrollY, open: document.querySelector(".spine").open, diff: walk.classList.contains("diff")}))
    })

    const checking = document.querySelector(".spine .srem[data-src]")
    if (checking) fetch(checking.dataset.src).then(it => it.ok ? it.text() : null).then(html => {
        if (!html) return
        const box = document.createElement("div")
        box.innerHTML = html
        const checked = box.querySelector(".spine")
        checked.open = document.querySelector(".spine").open
        document.querySelector(".spine").replaceWith(checked)
    })

    const step = walk.querySelector(".step")
    walk.querySelectorAll(".details, .stepcomment").forEach(it => it.hidden = true)

    walk.addEventListener("click", event => {
        const button = event.target.closest("button[type=button]")
        if (!button || !step) return
        if (button.hasAttribute("data-details")) {
            const box = step.querySelector(".details")
            box.hidden = !box.hidden
            button.textContent = box.hidden ? "Why, flow, nudge" : "Hide details"
        } else if (button.hasAttribute("data-comment")) {
            const form = step.querySelector(".stepcomment")
            form.hidden = false
            form.body.focus()
        }
    })
    walk.addEventListener("change", event => event.target.matches(".showdiff") && walk.classList.toggle("diff", event.target.checked))
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

// Puts the repo's diff in place of its "Show the diff", once.
const load = async lazy => {
    const response = await fetch(lazy.dataset.src)
    if (!response.ok) return
    const diff = document.createElement("div")
    diff.innerHTML = await response.text()
    lazy.replaceWith(diff)
    diff.dispatchEvent(new Event("diff-loaded", {bubbles: true}))
}

document.addEventListener("click", async event => {
    const show = event.target.closest(".lazydiff a")
    if (show) {
        event.preventDefault()
        return load(show.closest(".lazydiff"))
    }
    // A Files link, #f-<repo>-<n>, to a file whose repo's diff isn't loaded yet.
    const link = event.target.closest(".files a[href^='#f-']")
    if (!link || document.getElementById(link.hash.slice(1))) return
    const lazy = document.querySelector(`[data-repo="${CSS.escape(link.hash.slice(3).replace(/-\d+$/, ""))}"] .lazydiff`)
    if (!lazy) return
    event.preventDefault()
    await load(lazy)
    history.pushState(null, "", link.hash)
    document.getElementById(link.hash.slice(1))?.scrollIntoView()
})
