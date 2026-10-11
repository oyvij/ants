// The Notification list count, live in the nav and the tab title, and the Usage limit pill, live in the nav.
const title = document.title;
new EventSource('/notifications/count').onmessage = event => {
    const count = event.data === '0' ? '' : `(${event.data})`;
    document.getElementById('waiting').textContent = count;
    document.title = (count && `${count} `) + title;
};
const paused = document.getElementById('paused');
new EventSource(paused.dataset.src).onmessage = event => paused.innerHTML = event.data;
