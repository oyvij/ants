// What every page's shell does: the Notification list count, live in the sidebar, the tab title and the app badge
// (where the browser has one; 0 clears it); the sidebar's live slots, the Usage limit notice and the update notice,
// each from the stream it names; the modal's click outside; the toast's timeout; and the diagram box's buttons.
const title = document.title.replace(/^\(\d+\) /, '');
new EventSource('/notifications/count').onmessage = event => {
    const count = event.data === '0' ? '' : event.data;
    document.getElementById('waiting').textContent = count;
    document.title = (count && `(${count}) `) + title;
    navigator.setAppBadge?.(Number(event.data)).catch(() => {}); // Safari refuses without notification permission
};
// macOS badges an installed app only with notification permission, which a page may ask for only on a click.
const ask = document.getElementById('badge-permission');
if (ask && navigator.setAppBadge && window.Notification?.permission === 'default') {
    ask.hidden = false;
    ask.querySelector('button').onclick = () => Notification.requestPermission().then(() => ask.hidden = true);
}
for (const slot of document.querySelectorAll('nav [data-src]')) {
    new EventSource(slot.dataset.src).onmessage = event => slot.innerHTML = event.data;
}
// The sidebar marks the page it is on, or the page a deeper one sits under.
for (const link of document.querySelectorAll('nav.side > a')) {
    if (link.pathname === '/' ? location.pathname === '/' : location.pathname.startsWith(link.pathname)) link.classList.add('on');
}

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
