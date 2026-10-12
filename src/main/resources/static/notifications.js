// The Notification list count, live in the nav, the tab title and the app badge (where the browser has one; 0 clears
// it), and the nav's live slots, the Usage limit pill and the update notice, each from the stream it names.
const title = document.title;
new EventSource('/notifications/count').onmessage = event => {
    const count = event.data === '0' ? '' : `(${event.data})`;
    document.getElementById('waiting').textContent = count;
    document.title = (count && `${count} `) + title;
    navigator.setAppBadge?.(Number(event.data)).catch(() => {}); // Safari refuses without notification permission
};
for (const slot of document.querySelectorAll('nav [data-src]')) {
    new EventSource(slot.dataset.src).onmessage = event => slot.innerHTML = event.data;
}
