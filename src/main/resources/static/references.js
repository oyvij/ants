const openModal = dialog => {
    dialog.showModal();
    dialog.querySelector('.cm-content, input[name=path]')?.focus();
};

document.addEventListener('click', event => {
    const target = event.target.closest('.references [data-open], .references [data-copy]');
    if (!target) return;
    if ('copy' in target.dataset) {
        navigator.clipboard?.writeText(target.dataset.copy).catch(() => {});
        target.textContent = 'Copied';
        return;
    }
    event.preventDefault();
    openModal(document.getElementById(target.dataset.open));
});

const saveDraft = () => document.getElementById('skill')?.draft?.save();

async function post(form) {
    const alert = form.querySelector('[role=alert]');
    alert.hidden = true;
    await saveDraft();
    try {
        const response = await fetch(form.action, {method: 'POST', body: new FormData(form)});
        if (response.ok && new URL(response.url).pathname !== '/login') return response;
        const reason = new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('[role=alert]')?.textContent;
        alert.textContent = reason || `Not saved: Ants answered ${response.status}.`;
    } catch (e) {
        alert.textContent = "Not saved: Ants can't be reached.";
    }
    alert.hidden = false;
}

for (const form of document.querySelectorAll('form[data-reference-save]')) {
    const dialog = form.closest('dialog'), text = form.elements.text;
    const path = dialog.querySelector('.mh label').textContent;
    const unsaved = () => text.value !== text.defaultValue;
    const askFirst = event => { if (unsaved() && !confirm(`Drop your changes to ${path}?`)) event.preventDefault(); };
    dialog.addEventListener('cancel', askFirst);
    dialog.querySelector('button[title=Close]').addEventListener('click', askFirst);
    dialog.addEventListener('close', () => {
        if (!unsaved()) return;
        text.value = text.defaultValue;
        text.dispatchEvent(new Event('change'));
    });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (await post(form)) location.reload();
    });
}

const opening = new URL(location.href);
const opened = opening.searchParams.get('reference');
if (opened !== null) {
    opening.searchParams.delete('reference');
    history.replaceState(null, '', opening);
    const dialog = [...document.querySelectorAll('dialog.reference')].find(it => it.querySelector('.mh label').textContent === opened);
    if (dialog) openModal(dialog);
}

document.querySelector('form[data-new-reference]')?.addEventListener('submit', async event => {
    const form = event.target;
    event.preventDefault();
    const response = await post(form);
    if (!response) return;
    const references = new URL(form.action).pathname.replace(/new-reference$/, 'references/');
    const next = new URL(location.href);
    next.searchParams.set('reference', decodeURIComponent(new URL(response.url).pathname.slice(references.length)));
    location.assign(next);
});

for (const form of document.querySelectorAll('form[data-reference-change]')) {
    form.addEventListener('submit', async event => {
        event.preventDefault();
        await saveDraft();
        form.submit();
    });
}
