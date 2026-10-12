// The Notification list count, live in the nav, the tab title and the app badge (where the browser has one; 0 clears
// it), and the Usage limit pill, live in the nav.
const title = document.title;
new EventSource('/notifications/count').onmessage = event => {
    const count = event.data === '0' ? '' : `(${event.data})`;
    document.getElementById('waiting').textContent = count;
    document.title = (count && `${count} `) + title;
    navigator.setAppBadge?.(Number(event.data)).catch(() => {}); // Safari refuses without notification permission
};
const paused = document.getElementById('paused');
new EventSource(paused.dataset.src).onmessage = event => paused.innerHTML = event.data;
