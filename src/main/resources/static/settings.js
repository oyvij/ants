// The settings page. Its lists (.listed), such as the Vaults and the Agent folders: ＋ Add shows a new empty row,
// numbered after the last, and Remove empties its row and hides it, which Save reads as removed. Copy puts the login
// command on the clipboard. Edit opens a Source map's editor.
document.addEventListener('click', event => {
    const button = event.target.closest('[data-add], [data-remove], [data-copy], [data-edit]');
    if (!button) return;
    if ('edit' in button.dataset) {
        const dialog = document.getElementById(button.dataset.edit);
        dialog.showModal();
        dialog.querySelector('.cm-content')?.focus();
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
        row.querySelectorAll('[type=password]').forEach(value => value.placeholder = 'e.g. npm_a1b2c3'); // a new Secret has none yet
        last.after(row);
    }
    button.previousElementSibling.querySelector('input')?.focus();
});

const settingsForm = document.getElementById('settings');
if (settingsForm) {
    const bar = settingsForm.querySelector('.savebar');
    const state = () => new URLSearchParams([...new FormData(settingsForm)].filter(([name, value]) => name !== '_csrf' && value !== '')).toString();
    const baselineKey = 'settings-baseline';
    const baseline = ('posted' in settingsForm.dataset && sessionStorage.getItem(baselineKey)) || state();
    sessionStorage.removeItem(baselineKey);
    let submitting = false;
    const unsaved = () => !submitting && state() !== baseline;
    const showBar = () => bar.hidden = !unsaved();
    settingsForm.addEventListener('input', showBar);
    settingsForm.addEventListener('change', showBar);
    document.addEventListener('click', event => {
        if (event.target.closest('[data-add], [data-remove]')) showBar();
    });
    settingsForm.addEventListener('submit', event => {
        if (event.submitter?.hasAttribute('formaction')) return;
        submitting = true;
        sessionStorage.setItem(baselineKey, baseline);
    });
    window.addEventListener('pageshow', () => submitting = false);
    window.addEventListener('beforeunload', event => {
        if (unsaved()) event.preventDefault();
    });
    showBar();
}
