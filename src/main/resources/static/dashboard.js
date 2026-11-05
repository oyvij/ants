// A Dashboard's page: its title is edited in place and saved as typed, showing Saving… then Saved, and the menu and
// the tab show the title kept, which an emptied one makes "Untitled dashboard". Enter ends the edit. A new Dashboard
// (`?new`) opens with its title selected, ready to type over.
//
// Its grid of Widgets opens in view mode, where nothing moves and text can be selected; **Edit layout** moves,
// resizes, renames and removes them, clears their data and sets the days a log keeps, saving each change as it is
// made, reads **Done** while on, and Escape leaves it.
// **+ Widget** adds one at once, in edit mode, its title selected; a chart's kind is switched in its header there.
// Posts arrive live, each Widget flashing as it changes.
import { drawCharts } from '/charts.js';

const title = document.getElementById('title'), saved = document.getElementById('saved');
const id = title.dataset.id, csrf = document.querySelector('input[name=_csrf]').value;
const link = document.querySelector(`#dashboards-list a[href="/dashboards/${id}"] .clip`);
let timer = null, sending = Promise.resolve();

/** Posts [fields] to [path] with the page's CSRF token; the answer's JSON. */
const send = (path, fields) => fetch(path, { method: 'POST', body: new URLSearchParams({ _csrf: csrf, ...fields }) })
    .then(response => response.ok ? response.json() : Promise.reject(new Error(response.statusText)));

function save() {
    clearTimeout(timer);
    timer = null;
    const typed = title.textContent;
    sending = sending
        .then(() => send(`/dashboards/${id}/title`, { title: typed }))
        .then(({ title: kept }) => {
            if (link) link.textContent = kept;
            document.title = `${kept} · Ants`;
            if (document.activeElement !== title) title.textContent = kept;
            if (!timer) saved.textContent = 'Saved';
        })
        .catch(() => saved.textContent = 'Not saved');
}

title.addEventListener('input', () => {
    saved.textContent = 'Saving…';
    clearTimeout(timer);
    timer = setTimeout(save, 500);
});
title.addEventListener('keydown', event => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    title.blur();
});
title.addEventListener('blur', () => timer ? save() : !title.textContent.trim() && (title.textContent = 'Untitled dashboard'));

if (new URLSearchParams(location.search).has('new')) {
    history.replaceState(null, '', location.pathname);
    title.focus();
    getSelection().selectAllChildren(title);
}

// The grid: view mode at first, a static grid.
const main = document.querySelector('main.dashboard'), edit = document.getElementById('edit');
const empty = document.getElementById('no-widgets'), addresses = document.getElementById('addresses');
const grid = GridStack.init({ cellHeight: 70, margin: 8, handle: '.w-head', float: false, staticGrid: true, column: 12 }, '#grid');
let loading = false;

function setEditing(on) {
    main.classList.toggle('editing', on);
    grid.setStatic(!on);
    document.querySelectorAll('.w-head .t').forEach(t => t.contentEditable = on ? 'plaintext-only' : 'false');
    edit.textContent = on ? 'Done' : 'Edit layout';
    edit.ariaPressed = String(on);
}
const editing = () => main.classList.contains('editing');
edit.addEventListener('click', () => setEditing(!editing()));
document.addEventListener('keydown', event => { if (event.key === 'Escape' && editing()) { document.activeElement?.blur(); setEditing(false); } });

grid.on('change', (event, items) => {
    if (loading) return;
    send(`/dashboards/${id}/layout`, { layout: JSON.stringify(items.map(({ id, x, y, w, h }) => ({ id, x, y, w, h }))) });
});

// A Widget's title is renamed in place in edit mode, saved as typed; ✕ removes the Widget once the user confirms.
function wire(item) {
    const widget = item.getAttribute('gs-id'), t = item.querySelector('.t');
    let wait = null;
    const keep = () => { clearTimeout(wait); wait = null; send(`/dashboards/${id}/widgets/${widget}/title`, { title: t.textContent }); };
    t.addEventListener('input', () => { clearTimeout(wait); wait = setTimeout(keep, 500); });
    t.addEventListener('blur', () => wait && keep());
    t.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); t.blur(); } });
    item.querySelector('.x').addEventListener('click', async () => {
        if (!confirm(`Remove the Widget “${t.textContent}”? Its Posts go with it.`)) return;
        await send(`/dashboards/${id}/widgets/${widget}/delete`, {});
        grid.removeWidget(item);
        addresses.querySelectorAll(`[data-widget="${widget}"]`).forEach(row => row.remove());
        empty.hidden = grid.getGridItems().length > 0;
    });
    // Clear data empties it once the user confirms; a log's days are kept as typed.
    const body = item.querySelector('.w-body');
    item.querySelector('.clear').addEventListener('click', async () => {
        if (!confirm(`Clear the data of “${t.textContent}”? It keeps its id and title.`)) return;
        const cleared = await send(`/dashboards/${id}/widgets/${widget}/clear`, {});
        body.innerHTML = shown[widget] = cleared.body;
    });
    // A chart's kind, picked in edit mode, draws the same points another way.
    const kind = item.querySelector('.kind');
    kind?.addEventListener('change', async () => {
        const drawn = await send(`/dashboards/${id}/widgets/${widget}/kind`, { kind: kind.value });
        body.innerHTML = shown[widget] = drawn.body;
        drawCharts(body);
    });
    const days = item.querySelector('.keep');
    days?.addEventListener('change', () => days.checkValidity() && send(`/dashboards/${id}/widgets/${widget}/keep`, { days: days.value }));
    showNewest(item);
}

/** A log shows its newest line, at the bottom; other Widgets show from the top. */
function showNewest(item) {
    const body = item.querySelector('.w-body');
    if (item.querySelector('.widget').dataset.type === 'log') body.scrollTop = body.scrollHeight;
}
grid.getGridItems().forEach(wire);
drawCharts(document.getElementById('grid'));

// + Widget: a menu of types; picking one adds it at once, in edit mode, its title selected for typing.
const add = document.getElementById('add'), menu = document.getElementById('add-menu');
const showMenu = open => { menu.hidden = !open; add.ariaExpanded = String(open); if (open) menu.querySelector('button').focus(); };
add.addEventListener('click', event => { event.stopPropagation(); showMenu(menu.hidden); });
document.addEventListener('click', event => { if (!menu.contains(event.target)) showMenu(false); });
menu.addEventListener('keydown', event => { if (event.key === 'Escape') { event.stopPropagation(); showMenu(false); add.focus(); } });
for (const choice of menu.querySelectorAll('button[data-type]')) {
    choice.addEventListener('click', async () => {
        showMenu(false);
        const added = await send(`/dashboards/${id}/widgets`, { type: choice.dataset.type, ...(choice.dataset.kind && { kind: choice.dataset.kind }) });
        const holder = document.createElement('template');
        holder.innerHTML = added.html;
        const item = holder.content.firstElementChild;
        document.getElementById('grid').append(item);
        loading = true;
        grid.makeWidget(item);
        loading = false;
        wire(item);
        shown[added.id] = added.body;
        empty.hidden = true;
        addresses.insertAdjacentHTML('beforeend', `<dt data-widget="${added.id}"><code>${id}/${added.id}</code> · ${added.type}</dt><dd data-widget="${added.id}"><code></code></dd>`);
        addresses.querySelector(`dd[data-widget="${added.id}"] code`).textContent = added.shape;
        setEditing(true);
        item.scrollIntoView({ block: 'nearest' });
        const t = item.querySelector('.t');
        t.focus();
        getSelection().selectAllChildren(t);
    });
}

// Posting: the panel of addresses and the data each Widget takes.
const posting = document.getElementById('posting'), toggle = document.getElementById('posting-toggle');
toggle.addEventListener('click', () => { posting.hidden = !posting.hidden; toggle.ariaExpanded = String(!posting.hidden); });

// Posts, live: each Widget's body as the stream brings it, flashing when it changed since the last one.
const shown = {};
let first = true;
stream.on('widgets', data => {
    for (const [widget, html] of Object.entries(JSON.parse(data))) {
        const body = document.getElementById(`body-${widget}`);
        const changed = !first && shown[widget] !== html;
        shown[widget] = html;
        if (!body || !changed) continue;
        body.innerHTML = html;
        drawCharts(body);
        showNewest(body.closest('.grid-stack-item'));
        body.classList.remove('flash');
        void body.offsetWidth;
        body.classList.add('flash');
    }
    first = false;
});
stream.follow('dashboard=' + id);
