// A Dashboard's page: its title is edited in place and saved as typed, showing Saving… then Saved, and the menu and
// the tab show the title kept, which an emptied one makes "Untitled dashboard". Enter ends the edit. A new Dashboard
// (`?new`) opens with its title selected, ready to type over.
const title = document.getElementById('title'), saved = document.getElementById('saved');
const id = title.dataset.id, csrf = document.querySelector('input[name=_csrf]').value;
const link = document.querySelector(`#dashboards-list a[href="/dashboards/${id}"] .clip`);
let timer = null, sending = Promise.resolve();

function save() {
    clearTimeout(timer);
    timer = null;
    const typed = title.textContent;
    sending = sending
        .then(() => fetch(`/dashboards/${id}/title`, { method: 'POST', body: new URLSearchParams({ _csrf: csrf, title: typed }) }))
        .then(response => response.ok ? response.json() : Promise.reject(new Error(response.statusText)))
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
