// The Script page's Secrets card: "Add Secret" adds an empty row, and a row's ✕ removes it once Ants' own modal
// confirms, naming the Secret. Nothing is saved until Save Secrets, which posts the card's rows alone, numbered from 0,
// apart from the Draft; a Secret no row names leaves secrets.yaml. Without this script the card shows its saved
// Secrets and one empty row, and Save Secrets posts the whole page.
const list = document.querySelector('[data-secrets]');
const saveSecrets = document.querySelector('[data-save-secrets]');
if (list && saveSecrets) {
    const state = document.querySelector('[data-secrets-state]');
    saveSecrets.addEventListener('click', async event => {
        event.preventDefault();
        const form = saveSecrets.form;
        const fields = new URLSearchParams();
        fields.set('_csrf', form.querySelector('[name=_csrf]').value);
        list.querySelectorAll('[name^="secrets["]').forEach(field => fields.append(field.name, field.value));
        state.textContent = 'Saving…';
        let answer;
        try {
            const response = await fetch(`${form.getAttribute('action')}/secrets`, {method: 'POST', body: fields, headers: {Accept: 'application/json'}});
            answer = response.headers.get('content-type')?.includes('json') ? await response.json()
                : {error: response.status === 403 || response.redirected ? "you're logged out: log in again in another tab, then save again." : `Ants answered ${response.status}.`};
        } catch {
            answer = {error: "Ants can't be reached."};
        }
        if (answer.error) {
            state.textContent = `Not saved: ${answer.error}`;
            return;
        }
        list.querySelectorAll(':scope > .row').forEach(row => {
            const name = row.querySelector('input[name$=".name"]').value.trim();
            if (!answer.secrets.includes(name)) return;
            row.querySelector('input[name$=".was"]').value = name;
            const value = row.querySelector('input[name$=".value"]');
            value.value = '';
            value.placeholder = '••••';
        });
        state.textContent = answer.saved;
    });
}
if (list) {
    const blank = list.lastElementChild.cloneNode(true);
    const renumber = () => list.querySelectorAll(':scope > .row').forEach((row, i) =>
        row.querySelectorAll('[name]').forEach(field => field.name = field.name.replace(/\[\d+]/, `[${i}]`)));
    const withRemove = row => {
        const remove = Object.assign(document.createElement('button'),
            {type: 'button', className: 'ghost danger x', title: 'Delete the Secret', textContent: '✕'});
        remove.setAttribute('aria-label', 'Delete the Secret');
        row.append(remove);
        return row;
    };
    list.querySelectorAll(':scope > .row').forEach(withRemove);

    const add = Object.assign(document.createElement('button'), {type: 'button', className: 'add-secret', textContent: '＋ Add Secret'});
    list.after(add);
    add.onclick = () => {
        const row = withRemove(blank.cloneNode(true));
        list.append(row);
        renumber();
        row.querySelector('input:not([type=hidden])').focus();
    };

    list.addEventListener('click', async event => {
        const row = event.target.closest('.x')?.closest('.row');
        if (!row) return;
        const name = row.querySelector('input[name$=".name"]').value.trim();
        if (name && !await confirmed(`Delete the Secret ${name}?`, "Its value can't be shown again.")) return;
        row.remove();
        renumber();
    });
}

/** Asks [question] in a small modal over the page, with [detail] below it; true once Delete is pressed. */
function confirmed(question, detail) {
    const dialog = Object.assign(document.createElement('dialog'), {className: 'modal confirm'});
    dialog.innerHTML = '<form method="dialog" class="mh"><b>Delete the Secret</b><span class="sp"></span><span class="muted small">Esc to close</span>' +
        '<button class="ghost" title="Close" aria-label="Close">✕</button></form><div class="mb"><p></p>' +
        '<form method="dialog" class="go"><button value="cancel">Cancel</button><button class="danger" value="delete">Delete</button></form></div>';
    dialog.querySelector('.mb p').textContent = `${question} ${detail}`;
    document.body.append(dialog);
    dialog.showModal();
    dialog.querySelector('button[value=cancel]').focus();
    return new Promise(resolve => dialog.addEventListener('close', () => {
        resolve(dialog.returnValue === 'delete');
        dialog.remove();
    }));
}
