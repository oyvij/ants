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
let source = null, toasted = 0; // the latest toast shown, which the next stream starts after
const listen = name => source.addEventListener(name, event => { handlers[name](event.data); liven(); });
window.stream = {
    on(name, handler) {
        if (!(name in handlers) && source) listen(name);
        handlers[name] = handler;
    },
    follow(query = '') {
        source?.close();
        const all = [query, toasted && 'toast=' + toasted].filter(Boolean).join('&');
        source = new EventSource('/live' + (all && '?' + all));
        Object.keys(handlers).forEach(listen);
    },
    patch(element, html) {
        const like = element.cloneNode(false);
        like.innerHTML = html;
        morph(element, like);
    },
    morph: (element, like) => morph(element, like),
};
if (window.top === window) {
    addEventListener('DOMContentLoaded', () => source || stream.follow());
    let submitted = false;
    addEventListener('submit', event => submitted = !event.defaultPrevented && event.target.method !== 'dialog');
    addEventListener('pagehide', () => sessionStorage.setItem('scroll', JSON.stringify({ path: location.pathname, y: scrollY, submitted })));
    const left = JSON.parse(sessionStorage.getItem('scroll'));
    sessionStorage.removeItem('scroll');
    const reloaded = performance.getEntriesByType('navigation')[0]?.type === 'reload';
    if (left?.path === location.pathname && (reloaded || left.submitted)) addEventListener('load', () => scrollTo(0, left.y), { once: true });
}

// A button that sends something shows it the moment it is pressed: a spinner and its data-pending verb ("Approving…"),
// else its label, never narrower than it was, until the next page comes. A second press of any of its form's buttons
// does nothing meanwhile; they can't be disabled, since a disabled button leaves its name and value out of what the
// form sends. Back to the page undoes it.
function pending(button) {
    const was = [...button.childNodes], width = button.style.minWidth;
    const spin = document.createElement('span');
    spin.className = 'spin';
    spin.textContent = '✻';
    spin.ariaHidden = 'true';
    button.style.minWidth = button.offsetWidth + 'px';
    button.replaceChildren(spin, button.dataset.pending ?? button.textContent.trim());
    button.ariaBusy = 'true';
    return () => {
        button.replaceChildren(...was);
        button.style.minWidth = width;
        button.ariaBusy = null;
    };
}
let sending = null; // undoes the form being sent
addEventListener('submit', event => { if (event.target.ariaBusy) event.preventDefault(); }, true);
addEventListener('submit', event => {
    const form = event.target, button = event.submitter;
    if (event.defaultPrevented || form.method === 'dialog' || form.target) return;
    const others = [...form.elements].filter(other => other.type === 'submit' && other !== button);
    const undo = button ? pending(button) : () => {};
    others.forEach(other => other.ariaDisabled = 'true');
    form.ariaBusy = 'true';
    sending = () => {
        undo();
        others.forEach(other => other.ariaDisabled = null);
        form.ariaBusy = null;
    };
});
addEventListener('pageshow', event => { if (event.persisted) sending?.(); });

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

// The sidebar's events, on a page with the sidebar: a modal's page, without one, may still follow its own (the Script helper).
const menu = document.getElementById('menu');
const title = document.title.replace(/^\(\d+\) /, '');
if (menu) stream.on('count', data => {
    const count = data === '0' ? '' : data;
    for (const waiting of [document.getElementById('waiting'), document.getElementById('topbar-waiting')]) {
        if (waiting.textContent === count) continue;
        waiting.textContent = count;
        flash(waiting);
    }
    document.title = (count && `(${count}) `) + title;
    navigator.setAppBadge?.(Number(data)).catch(() => {}); // Safari refuses without notification permission
});
if (menu) stream.on('runs', html => stream.patch(document.getElementById('runs'), html));
if (menu) stream.on('pauses', html => document.getElementById('paused').innerHTML = html);
if (menu) stream.on('update', html => document.getElementById('update').innerHTML = html);
const toast = element => setTimeout(() => element.remove(), 12000);
// Each is numbered, so one a reconnected stream sends again shows once.
if (menu) stream.on('toast', html => {
    const seq = Number(/data-seq="(\d+)"/.exec(html)?.[1]);
    if (seq <= toasted) return;
    toasted = seq;
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

document.addEventListener('click', event => {
    if (!menu?.checked || event.target.closest('label[for=menu], #menu')) return;
    if (event.target.closest('nav.side')) {
        if (event.target.closest('a')) menu.checked = false;
        return;
    }
    event.preventDefault();
    event.stopPropagation();
    menu.checked = false;
}, true);
addEventListener('keydown', event => { if (event.key === 'Escape' && menu) menu.checked = false; });

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
        (document.fullscreenEnabled ? '<button type="button" data-z="full" title="Fullscreen">⛶</button>' : '') + '</div>');
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

const composer = document.querySelector('.composer');
if (composer) new ResizeObserver(() => document.documentElement.style.setProperty('--composer-height', composer.offsetHeight + 'px')).observe(composer, { box: 'border-box' });
