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

async function saveDraftThenPost(form) {
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
        if (await saveDraftThenPost(form)) location.reload();
    });
}

const here = new URL(location.href);
const created = here.searchParams.get('reference');
if (created !== null) {
    here.searchParams.delete('reference');
    history.replaceState(null, '', here);
    const dialog = [...document.querySelectorAll('dialog.reference')].find(it => it.querySelector('.mh label').textContent === created);
    if (dialog) openModal(dialog);
}

document.querySelector('form[data-new-reference]')?.addEventListener('submit', async event => {
    const form = event.target;
    event.preventDefault();
    const response = await saveDraftThenPost(form);
    if (!response) return;
    const next = new URL(location.href);
    next.searchParams.set('reference', new URL(response.url).searchParams.get('reference'));
    location.assign(next);
});

for (const form of document.querySelectorAll('form[data-reference-change]')) {
    form.addEventListener('submit', async event => {
        event.preventDefault();
        await saveDraft();
        form.submit();
    });
}

const skillText = document.getElementById('skill-md');
const missingMention = document.getElementById('missing-mention');
if (skillText && !skillText.readOnly && missingMention) {
    let latest = 0, pending;
    const reread = async () => {
        const asked = ++latest;
        const body = new URLSearchParams({_csrf: new FormData(skillText.form).get('_csrf'), skill: skillText.value});
        const response = await fetch(`${location.pathname}/mentions`, {method: 'POST', body}).catch(() => null);
        if (!response?.ok) return;
        const {unmentioned, missing} = await response.json();
        if (asked !== latest) return;
        for (const mark of document.querySelectorAll('.references [data-unmentioned]'))
            mark.hidden = !unmentioned.includes(mark.closest('.row').querySelector('[data-copy]').dataset.copy);
        document.querySelectorAll('.references .row.missing').forEach(row => row.remove());
        for (const path of missing) {
            const row = missingMention.content.firstElementChild.cloneNode(true);
            row.querySelector('code').textContent = path;
            missingMention.before(row);
        }
    };
    skillText.addEventListener('input', () => {
        clearTimeout(pending);
        pending = setTimeout(reread, 150);
    });
}
