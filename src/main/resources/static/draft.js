// Autosave, for every editor whose item keeps a Draft (Drafts.kt). About 2 s after the last change, and on leaving the
// page, it posts the page to its autosave endpoint, whatever state the page is in, with the version of the Draft the
// page last loaded or saved, and retries until the server answers. The header (drafthead.kte) says "Saving…", what
// the server said ("Draft saved 14:02") or "Not saved: <why>", and its marker shows what the server says the item is
// now. A save that meets a newer Draft stops autosaving and offers to load that Draft. A form with `data-autosave` is
// autosaved by itself; a page that isn't a form, such as the modeler, calls `autosave` with its own `fields` and tells
// it of each change with `changed()`. A form or button with `data-leaves-draft` (Publish, Discard Draft, Delete)
// leaves the page without the leaving save, since what it does replaces the Draft.

const retryAfter = [2000, 4000, 8000, 15000, 30000];

/**
 * Autosaves to [endpoint] (`<the item's page>/draft`) what [fields] gives, an object or URLSearchParams (or a promise
 * of one), starting from the Draft [version] the page loaded. [csrf] is the page's CSRF token.
 */
export function autosave({endpoint, version, fields, csrf, head = document.querySelector('.draft-head'), delay = 2000}) {
    const state = head?.querySelector('[data-save-state]'), marker = head?.querySelector('.draft-mark'), loadNewer = head?.querySelector('[data-load-newer]');
    // Which page this is, and each save's number, so Ants tells this page's later save from another tab's (Drafts.kt).
    const tab = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
    const away = new Set(); // the leaving saves on their way
    let timer, saving = null, last = null, failures = 0, stopped = false, leaving = false, sent = 0, answered = 0;
    const say = text => {
        if (!state) return;
        state.textContent = text;
        state.toggleAttribute('data-failed', text.startsWith('Not saved'));
    };

    async function body() {
        const params = new URLSearchParams(await fields());
        params.delete('_csrf');
        return params;
    }

    // After the login expired and the user logged in again, the page's token is stale: the page itself, fetched anew, carries the current one.
    async function freshCsrf() {
        const page = await fetch(location.href);
        if (page.redirected && new URL(page.url).pathname === '/login') return;
        csrf = new DOMParser().parseFromString(await page.text(), 'text/html').querySelector('input[name=_csrf]')?.value ?? csrf;
    }

    // One save at a time, so each carries the version the one before it got back.
    function save(options = {}) {
        clearTimeout(timer);
        if (stopped) return Promise.resolve();
        if (saving) return saving.then(() => save(options));
        saving = send(options).finally(() => { saving = null; });
        return saving;
    }

    async function send({keepalive = false}) {
        const params = await body();
        const text = params.toString();
        if (text === last) return;
        const seq = ++sent;
        params.set('version', version);
        params.set('tab', tab);
        params.set('seq', seq);
        params.set('_csrf', csrf);
        say('Saving…');
        try {
            const response = await fetch(endpoint, {method: 'POST', body: params, keepalive, headers: {Accept: 'application/json'}});
            if (response.status === 409) return conflict(await response.json());
            const signedOut = response.status === 403 || response.redirected && new URL(response.url).pathname === '/login';
            if (signedOut) return retry("you're logged out. Log in again in another tab; this page keeps trying.", true);
            if (!response.ok) return retry(`Ants answered ${response.status}${response.statusText ? ' ' + response.statusText : ''}.`);
            saved(await response.json(), text, seq);
        } catch (e) {
            retry("Ants can't be reached.");
        }
    }

    function saved(answer, text, seq) {
        if (seq < answered) return; // a later save of this page was answered first
        answered = seq;
        failures = 0;
        last = text;
        version = answer.version;
        say(answer.saved);
        if (marker) {
            marker.className = `pill draft-mark ${answer.marker}`;
            marker.textContent = answer.label;
        }
        const path = endpoint.replace(/\/draft$/, '');
        if (answer.path !== path) moved(path, answer.path);
    }

    // A Draft-only item's page follows its name: the address, the autosave and every form of the page move with it.
    function moved(from, to) {
        endpoint = `${to}/draft`;
        history.replaceState(history.state, '', to + location.search);
        for (const form of document.querySelectorAll('form[action]')) {
            const action = form.getAttribute('action');
            if (action === from || action.startsWith(`${from}/`)) form.setAttribute('action', to + action.slice(from.length));
        }
    }

    function conflict(answer) {
        stopped = true;
        say(`Not saved: ${answer.conflict}`);
        if (loadNewer) loadNewer.hidden = false;
    }

    function retry(why, signedOut = false) {
        say(`Not saved: ${why}`);
        const wait = retryAfter[Math.min(failures++, retryAfter.length - 1)];
        timer = setTimeout(async () => {
            if (signedOut) await freshCsrf().catch(() => {});
            save();
        }, wait);
    }

    function changed() {
        if (stopped || leaving) return;
        clearTimeout(timer);
        timer = setTimeout(save, delay);
    }

    // A page being left can't wait for a save still on its way, so this one goes at once beside it, and Ants keeps the
    // later of the two, both being this page's.
    function leave() {
        if (leaving || stopped) return;
        clearTimeout(timer);
        const going = send({keepalive: true}).finally(() => away.delete(going));
        away.add(going);
    }

    loadNewer?.addEventListener('click', () => location.reload());
    addEventListener('pagehide', leave);
    addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') leave(); });
    // A save still on its way would land after Publish or Discard and bring the Draft back: the form waits for it.
    document.addEventListener('submit', event => {
        const form = event.target;
        if (event.defaultPrevented || !form.closest('[data-leaves-draft]')) return;
        leaving = true;
        clearTimeout(timer);
        const pending = [saving, ...away].filter(Boolean);
        if (!pending.length) return;
        event.preventDefault();
        Promise.allSettled(pending).then(() => form.submit());
    });
    // What the page loaded is what the Draft (or the published item) already holds.
    body().then(params => { last ??= params.toString(); });
    return {changed, save};
}

for (const form of document.querySelectorAll('form[data-autosave]')) {
    const draft = autosave({endpoint: form.dataset.autosave, version: form.dataset.version, fields: () => new FormData(form), csrf: new FormData(form).get('_csrf')});
    form.draft = draft; // so the modeler's modal can save the page before it picks the item
    form.addEventListener('input', draft.changed);
    form.addEventListener('change', draft.changed);
    // A Skill chip's ×, a Limit… rule or an added tool changes the form with no input event.
    new MutationObserver(draft.changed).observe(form, {childList: true, subtree: true});
}
