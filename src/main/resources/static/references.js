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
