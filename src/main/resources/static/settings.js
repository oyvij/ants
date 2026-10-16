// The settings page. Its lists (.listed), such as the Vaults and the Agent folders: ＋ Add shows a new empty row,
// numbered after the last, and Remove empties its row and hides it, which Save reads as removed. Copy puts the login
// command on the clipboard. Edit opens a Source map's editor.
document.addEventListener('click', event => {
    const button = event.target.closest('[data-add], [data-remove], [data-copy], [data-edit]');
    if (!button) return;
    if ('edit' in button.dataset) {
        document.getElementById(button.dataset.edit).showModal();
        return;
    }
    if ('copy' in button.dataset) {
        navigator.clipboard?.writeText(button.previousElementSibling.textContent).catch(() => {});
        button.textContent = 'Copied';
        return;
    }
    const empty = row => {
        row.querySelectorAll('input:not([type=radio]), select').forEach(field => field.value = '');
        row.querySelectorAll('[type=radio]').forEach(radio => radio.checked = false);
        return row;
    };
    if ('remove' in button.dataset) {
        empty(button.closest('.row')).hidden = true;
        return;
    }
    const rows = button.closest('.listed').querySelectorAll(':scope > .row'), last = rows[rows.length - 1];
    if (last.hidden) {
        last.hidden = false;
    } else {
        const row = empty(last.cloneNode(true));
        row.querySelectorAll('[name]').forEach(field => field.name = field.name.replace(/\[(\d+)]/, (_, i) => `[${+i + 1}]`));
        row.querySelectorAll('[type=radio]').forEach(radio => radio.value = +radio.value + 1);
        row.querySelectorAll('[type=password]').forEach(value => value.placeholder = 'Value'); // a new Secret has none yet
        last.after(row);
    }
    button.previousElementSibling.querySelector('input')?.focus();
});
