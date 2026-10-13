// What every page's shell does: the page's one stream, patching what changed; the Notification list count, live in the
// sidebar, the tab title and the app badge (where the browser has one; 0 clears it); the sidebar's Runs line, Usage
// limit notice and update notice, live too; the toasts the stream brings; liveness, the spinners, clocks and rotating
// verbs of whatever runs; a clickable row; the modal's click outside; the toast's timeout; and the diagram box's buttons.

// A page holds one SSE connection, /live (a browser opens at most 6 per host, shared by all its tabs): the sidebar's
// events on every page, and the page's own. A page script (a module, so it runs after this one) handles its events with
// stream.on(name, data => …) and names what it follows with stream.follow(query), which reconnects; a page that names
// nothing gets the sidebar's events once it has loaded. A page in a frame, such as a modal's, leaves them to its parent.
// stream.patch(element, html) brings an element's content up to date in place, as stream.morph(element, like) brings
// the element itself, so an open form, the drawer or a running animation stays.
const handlers = {};
let source = null;
const listen = name => source.addEventListener(name, event => { handlers[name](event.data); liven(); });
window.stream = {
    on(name, handler) {
        if (!(name in handlers) && source) listen(name);
        handlers[name] = handler;
    },
    follow(query = '') {
        source?.close();
        source = new EventSource('/live' + (query && '?' + query));
        Object.keys(handlers).forEach(listen);
    },
    patch(element, html) {
        const like = element.cloneNode(false);
        like.innerHTML = html;
        morph(element, like);
    },
    morph: (element, like) => morph(element, like),
};
if (window.top === window) addEventListener('DOMContentLoaded', () => source || stream.follow());

// Brings [old] to look like [now] in place: its attributes, its text and its children, which match by id or data-href,
// else in order. A new child that has either, a new table's rows, and an element whose data-flash changes flash once,
// as a new Notification list entry, a Task's new state or a new count do. Returns the node now in [old]'s place.
function morph(old, now) {
    if (old.nodeType !== now.nodeType || old.nodeName !== now.nodeName) {
        old.replaceWith(now);
        return fresh(now);
    }
    if (old.nodeType !== Node.ELEMENT_NODE) {
        if (old.nodeValue !== now.nodeValue) old.nodeValue = now.nodeValue;
        return old;
    }
    const changed = old.dataset.flash !== now.dataset.flash, flashing = old.classList.contains('flash');
    for (const { name } of [...old.attributes]) if (!now.hasAttribute(name)) old.removeAttribute(name);
    for (const { name, value } of now.attributes) if (old.getAttribute(name) !== value) old.setAttribute(name, value);
    if (flashing) old.classList.add('flash'); // the attributes above take it off mid-flash
    if (changed) flash(old);
    const key = node => node.nodeType === Node.ELEMENT_NODE && (node.id || node.dataset.href) || null;
    const olds = [...old.childNodes], keyed = new Map(olds.filter(key).map(node => [key(node), node])), loose = olds.filter(node => !key(node));
    const kids = [...now.childNodes].map(node => {
        const was = key(node) ? keyed.get(key(node)) : loose.shift();
        return was ? morph(was, node) : fresh(node);
    });
    for (const node of olds) if (!kids.includes(node)) node.remove();
    kids.forEach((node, i) => { if (old.childNodes[i] !== node) old.insertBefore(node, old.childNodes[i] ?? null); });
    return old;
}

// A node new to the page: it flashes if it has an id or data-href, else its rows do.
function fresh(node) {
    if (node.nodeType !== Node.ELEMENT_NODE) return node;
    if (node.id || node.dataset.href) flash(node);
    else node.querySelectorAll('tr[data-href]').forEach(flash);
    return node;
}

// One flash (.flash in ants.css), which prefers-reduced-motion turns off there.
function flash(element) {
    element.classList.remove('flash');
    void element.offsetWidth; // so a second flash starts over
    element.classList.add('flash');
    element.addEventListener('animationend', () => element.classList.remove('flash'), { once: true });
}

// Liveness, from what the stream brings, never invented: a spinner cycles Claude Code's glyphs every 120 ms; a clock
// (data-since, the epoch ms it counts from) ticks every second; a verb waiting for an event to name it (data-rotate)
// rotates every 4 s. A paused or stopped Task has none of them. prefers-reduced-motion stops the spinners only.
const GLYPHS = ['·', '✢', '✳', '✶', '✻', '✽', '✻', '✶', '✳', '✢'];
const VERBS = ['Pondering', 'Brewing', 'Crafting', 'Mulling', 'Weaving', 'Sketching', 'Untangling', 'Cogitating'];
const still = matchMedia('(prefers-reduced-motion: reduce)');
const set = (element, text) => { if (element.textContent !== text) element.textContent = text; };
function liven() {
    const now = Date.now();
    if (!still.matches) for (const spin of document.querySelectorAll('.spin')) set(spin, GLYPHS[Math.floor(now / 120) % GLYPHS.length]);
    for (const since of document.querySelectorAll('[data-since]')) {
        const s = Math.max(0, Math.floor((now - Number(since.dataset.since)) / 1000));
        // Its two largest units, as the server's duration() writes it.
        const units = [[Math.floor(s / 86400), 'd'], [Math.floor(s / 3600) % 24, 'h'], [Math.floor(s / 60) % 60, 'm'], [s % 60, 's']];
        const first = Math.max(units.findIndex(([n]) => n > 0), s ? 0 : 3);
        set(since, units.slice(first, first + 2).map(([n, unit]) => n + unit).join(' '));
    }
    for (const verb of document.querySelectorAll('[data-rotate]')) set(verb, VERBS[Math.floor(now / 4000) % VERBS.length] + '…');
}
setInterval(liven, 120);
addEventListener('DOMContentLoaded', liven);

const title = document.title.replace(/^\(\d+\) /, '');
stream.on('count', data => {
    const count = data === '0' ? '' : data, waiting = document.getElementById('waiting');
    if (waiting.textContent !== count) {
        waiting.textContent = count;
        flash(waiting);
    }
    document.title = (count && `(${count}) `) + title;
    navigator.setAppBadge?.(Number(data)).catch(() => {}); // Safari refuses without notification permission
});
stream.on('runs', html => stream.patch(document.getElementById('runs'), html));
stream.on('pauses', html => document.getElementById('paused').innerHTML = html);
stream.on('update', html => document.getElementById('update').innerHTML = html);
// A toast, such as a Round arriving or a queued Task taking a free slot, fades in; it goes by itself after a while,
// and its OK button takes it away sooner.
const toast = element => setTimeout(() => element.remove(), 12000);
stream.on('toast', html => {
    document.querySelectorAll('.toast').forEach(old => old.remove()); // the latest says it
    document.body.insertAdjacentHTML('beforeend', html);
    toast(document.body.lastElementChild);
});
document.querySelectorAll('.toast').forEach(toast);
// macOS badges an installed app only with notification permission, which a page may ask for only on a click.
const ask = document.getElementById('badge-permission');
if (ask && navigator.setAppBadge && window.Notification?.permission === 'default') {
    ask.hidden = false;
    ask.querySelector('button').onclick = () => Notification.requestPermission().then(() => ask.hidden = true);
}
// The sidebar marks the page it is on, or the page a deeper one sits under.
for (const link of document.querySelectorAll('nav.side > a')) {
    if (link.pathname === '/' ? location.pathname === '/' : location.pathname.startsWith(link.pathname)) link.classList.add('on');
}

// A clickable row (tr.click) opens its data-href, unless the click was on a link, button or form of its own.
document.addEventListener('click', event => {
    const row = event.target.closest('tr.click[data-href]');
    if (row && !event.target.closest('a, button, form')) location.href = row.dataset.href;
});


// A modal (<dialog class="modal">) closes on Esc and on its ✕ by itself; a click outside it, on the backdrop, closes it too.
document.addEventListener('click', event => {
    if (event.target instanceof HTMLDialogElement && event.target.classList.contains('modal')) event.target.close();
});

// The diagram box (.dia): ＋ and － zoom, Fit fits the diagram, ⛶ makes it fullscreen. The page puts its bpmn-js
// canvas on the box as `box.canvas` once the diagram is drawn.
for (const box of document.querySelectorAll('.dia')) {
    box.insertAdjacentHTML('beforeend', '<div class="zoom"><button type="button" data-z="in" title="Zoom in">＋</button>' +
        '<button type="button" data-z="out" title="Zoom out">－</button><button type="button" data-z="fit" title="Fit">Fit</button>' +
        '<button type="button" data-z="full" title="Fullscreen">⛶</button></div>');
    box.querySelector('.zoom').onclick = event => {
        const zoom = event.target.closest('[data-z]')?.dataset.z, canvas = box.canvas;
        if (!zoom || !canvas) return;
        if (zoom === 'in') canvas.zoom(canvas.zoom() * 1.25);
        else if (zoom === 'out') canvas.zoom(canvas.zoom() / 1.25);
        else if (zoom === 'fit') canvas.zoom('fit-viewport', 'auto');
        else if (document.fullscreenElement === box) document.exitFullscreen();
        else box.requestFullscreen();
    };
    box.addEventListener('fullscreenchange', () => setTimeout(() => { box.canvas?.resized(); box.canvas?.zoom('fit-viewport', 'auto'); }, 50));
}
