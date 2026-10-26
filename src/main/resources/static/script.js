// The Script page's Secrets card: "Add Secret" adds an empty row, and a row's ✕ removes it once Ants' own modal
// confirms, naming the Secret. Nothing is saved until the page is: Save posts the rows left, numbered from 0, and a
// Secret no row names leaves secrets.yaml. Without this script the card shows its saved Secrets and one empty row.
const list = document.querySelector('[data-secrets]');
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
