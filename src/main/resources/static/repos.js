const entries = value => value.split(/[,\s]+/).filter(Boolean)

function element(tag, attributes = {}, text = "") {
    const made = document.createElement(tag)
    for (const [name, value] of Object.entries(attributes)) made.setAttribute(name, value)
    made.textContent = text
    return made
}

function repoPicker(field) {
    const typed = field.querySelector("input:not([type=checkbox])")
    const boxes = [...field.querySelectorAll("input[type=checkbox]")]
    const {name, id} = typed
    const picked = [...boxes.filter(box => box.checked).map(box => box.value), ...entries(typed.value)]
        .filter((repo, i, all) => all.indexOf(repo) === i)

    const input = element("input", {
        id, role: "combobox", placeholder: field.dataset.filterPlaceholder, autocomplete: "off",
        "aria-autocomplete": "list", "aria-expanded": "false", "aria-controls": `${id}-list`,
    })
    const list = element("ul", {id: `${id}-list`, role: "listbox", "aria-multiselectable": "true", "aria-label": "Source map repos"})
    list.hidden = true
    const options = boxes.map((box, i) => list.appendChild(element("li", {id: `${id}-${i}`, role: "option"}, box.value)))
    const combo = element("div", {class: "combo"})
    combo.append(element("div", {class: "chipsel"}), list)
    combo.firstChild.append(input)
    const tags = element("div", {class: "tags", role: "list", "aria-label": "Picked repos"})
    const none = field.querySelector("[data-none]")

    field.querySelector(".picks")?.remove()
    typed.replaceWith(combo)
    combo.after(tags)

    function render() {
        for (const option of options) option.setAttribute("aria-selected", picked.includes(option.textContent))
        tags.replaceChildren(...picked.map(repo => {
            const tag = element("span", {class: "c", role: "listitem"})
            tag.append(element("span", {class: "mono"}, repo), element("input", {type: "hidden", name, value: repo}),
                element("button", {type: "button", title: "Remove", "aria-label": `Remove ${repo}`}, "×"))
            return tag
        }))
        none.hidden = picked.length > 0
    }

    function toggle(repo) {
        const at = picked.indexOf(repo)
        if (at < 0) picked.push(repo)
        else picked.splice(at, 1)
        render()
    }

    const shown = () => options.filter(option => !option.hidden)
    const active = () => options.find(option => option.classList.contains("on"))

    function activate(option) {
        for (const each of options) each.classList.toggle("on", each === option)
        if (option) {
            input.setAttribute("aria-activedescendant", option.id)
            option.scrollIntoView({block: "nearest"})
        } else input.removeAttribute("aria-activedescendant")
    }

    function filter() {
        const query = input.value.trim().toLowerCase()
        for (const option of options) option.hidden = !option.textContent.toLowerCase().includes(query)
        activate(query && !/[/:]/.test(query) ? shown()[0] : null)
    }

    function addTyped() {
        for (const repo of entries(input.value)) if (!picked.includes(repo)) picked.push(repo)
        input.value = ""
        render()
    }

    function open() {
        if (!list.hidden || !options.length) return
        list.hidden = false
        tags.hidden = true
        input.setAttribute("aria-expanded", "true")
        filter()
    }

    function close() {
        list.hidden = true
        tags.hidden = false
        input.setAttribute("aria-expanded", "false")
        activate(null)
    }

    function move(step) {
        open()
        const visible = shown()
        if (!visible.length) return
        const at = visible.indexOf(active())
        activate(visible[at < 0 ? (step > 0 ? 0 : visible.length - 1) : (at + step + visible.length) % visible.length])
    }

    input.addEventListener("focus", open)
    input.addEventListener("click", open)
    input.addEventListener("input", () => {
        open()
        filter()
    })
    input.addEventListener("keydown", event => {
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault()
            move(event.key === "ArrowDown" ? 1 : -1)
        } else if (event.key === "Escape" && !list.hidden) {
            event.preventDefault()
            close()
        } else if (event.key === "Enter") {
            const option = !list.hidden && active()
            if (option) toggle(option.textContent)
            else if (input.value.trim()) {
                addTyped()
                filter()
            } else return
            event.preventDefault()
        } else if (event.key === "Tab") close()
    })
    input.form.addEventListener("submit", addTyped)
    list.addEventListener("mousedown", event => event.preventDefault())
    list.addEventListener("click", event => {
        const option = event.target.closest("[role=option]")
        if (!option) return
        activate(option)
        toggle(option.textContent)
    })
    combo.addEventListener("focusout", event => {
        if (!combo.contains(event.relatedTarget)) close()
    })
    tags.addEventListener("click", event => {
        const button = event.target.closest("button")
        if (!button) return
        const at = [...tags.children].indexOf(button.parentElement)
        toggle(button.parentElement.querySelector("input").value)
        const next = tags.children[at] ?? tags.children[at - 1]
        if (next) next.querySelector("button").focus()
    })
    render()
}

document.querySelectorAll("#start [data-filter-placeholder]").forEach(repoPicker)
