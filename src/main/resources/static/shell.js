// What every page's shell does: the page's one stream; the Notification list count, live in the sidebar, the tab title
// and the app badge (where the browser has one; 0 clears it); the sidebar's Runs line, Usage limit notice and update
// notice, live too; a clickable row; the modal's click outside; the toast's timeout; and the diagram box's buttons.

// A page holds one SSE connection, /live (a browser opens at most 6 per host, shared by all its tabs): the sidebar's
// events on every page, and the page's own. A page script (a module, so it runs after this one) handles its events with
// stream.on(name, data => …) and names what it follows with stream.follow(query), which reconnects; a page that names
// nothing gets the sidebar's events once it has loaded. A page in a frame, such as a modal's, leaves them to its parent.
const handlers = {};
let source = null;
const listen = name => source.addEventListener(name, event => handlers[name](event.data));
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
};
if (window.top === window) addEventListener('DOMContentLoaded', () => source || stream.follow());

const title = document.title.replace(/^\(\d+\) /, '');
stream.on('count', data => {
    const count = data === '0' ? '' : data;
    document.getElementById('waiting').textContent = count;
    document.title = (count && `(${count}) `) + title;
    navigator.setAppBadge?.(Number(data)).catch(() => {}); // Safari refuses without notification permission
});
stream.on('runs', html => document.getElementById('runs').innerHTML = html);
stream.on('pauses', html => document.getElementById('paused').innerHTML = html);
stream.on('update', html => document.getElementById('update').innerHTML = html);
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

// A toast goes by itself after a while; its OK button takes it away sooner.
setTimeout(() => document.querySelector('.toast')?.remove(), 12000);

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
