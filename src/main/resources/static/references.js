document.addEventListener('click', event => {
    const target = event.target.closest('.references [data-open], .references [data-copy]');
    if (!target) return;
    if ('copy' in target.dataset) {
        navigator.clipboard?.writeText(target.dataset.copy).catch(() => {});
        target.textContent = 'Copied';
        return;
    }
    event.preventDefault();
    const dialog = document.getElementById(target.dataset.open);
    dialog.showModal();
    dialog.querySelector('.cm-content')?.focus();
});

for (const form of document.querySelectorAll('form[data-reference-save]')) {
    const dialog = form.closest('dialog'), text = form.elements.text, alert = form.querySelector('[role=alert]');
    const path = dialog.querySelector('.mh label').textContent;
    const keeps = () => text.value === text.defaultValue || confirm(`Drop your changes to ${path}?`);
    dialog.addEventListener('cancel', event => { if (!keeps()) event.preventDefault(); });
    dialog.querySelector('button[title=Close]').addEventListener('click', event => { if (!keeps()) event.preventDefault(); });
    dialog.addEventListener('close', () => {
        if (text.value === text.defaultValue) return;
        text.value = text.defaultValue;
        text.dispatchEvent(new Event('change'));
    });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        alert.hidden = true;
        await document.getElementById('skill')?.draft?.save();
        try {
            const response = await fetch(form.action, {method: 'POST', body: new FormData(form)});
            if (response.ok && new URL(response.url).pathname !== '/login') return location.reload();
            const reason = new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('[role=alert]')?.textContent;
            alert.textContent = reason || `Not saved: Ants answered ${response.status}.`;
        } catch (e) {
            alert.textContent = "Not saved: Ants can't be reached.";
        }
        alert.hidden = false;
    });
}
