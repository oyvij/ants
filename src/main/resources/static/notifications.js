// The Notification list count, live in the nav, the tab title and the app badge (where the browser has one; 0 clears
// it), and the nav's live slots, the Usage limit pill and the update notice, each from the stream it names.
const title = document.title;
new EventSource('/notifications/count').onmessage = event => {
    const count = event.data === '0' ? '' : `(${event.data})`;
    document.getElementById('waiting').textContent = count;
    document.title = (count && `${count} `) + title;
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
for (const link of document.querySelectorAll('body > nav:first-child > a')) {
    if (link.pathname === '/' ? location.pathname === '/' : location.pathname.startsWith(link.pathname)) link.setAttribute('aria-current', 'page');
}
