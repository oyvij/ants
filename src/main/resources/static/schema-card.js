// The Script page's schema card: its tabs, and rows added, removed and nested, each named for where it sits, as the form posts it.
const NESTING = ['object', 'array:object'];

/** A value for each row of the schema card's top level, for a Test run's sample inputs to start from. */
export function sample(card) {
    return values(card.querySelector('#input-schema > .schema-level'));
}

function values(level) {
    return Object.fromEntries(rows(level).flatMap(li => {
        const name = field(li, 'name').value.trim();
        if (!name) return [];
        const type = field(li, 'type').value;
        if (type === 'object') return [[name, values(fields(li))]];
        if (type === 'array:object') return [[name, [values(fields(li))]]];
        return [[name, type.startsWith('array:') ? [] : {string: '', number: 0, boolean: false}[type]]];
    }));
}

const rows = level => [...level.querySelector(':scope > ol').children];
const field = (li, name) => li.querySelector(`:scope > .row [data-field=${name}]`);
const fields = li => li.querySelector(':scope > .schema-fields > .schema-level');

function renumber(level, prefix) {
    level.dataset.prefix = prefix;
    rows(level).forEach((li, i) => {
        const at = `${prefix}[${i}]`;
        li.querySelectorAll(':scope > .row [data-field]').forEach(input => input.name = `${at}.${input.dataset.field}`);
        renumber(fields(li), `${at}.rows`);
    });
}

function mount(card) {
    const none = card.querySelector('[data-no-output]');
    const changed = () => { // the levels are looked up each time, since the Script helper swaps whole levels in
        card.querySelectorAll('[role=tabpanel] > .schema-level').forEach(top => renumber(top, top.dataset.prefix));
        if (none) none.hidden = rows(card.querySelector('#output-schema > .schema-level')).length > 0;
        card.dispatchEvent(new Event('change', {bubbles: true}));
    };
    card.addEventListener('schema-replaced', changed);
    card.querySelectorAll('[role=tab]').forEach(tab => tab.addEventListener('click', () => {
        card.querySelectorAll('[role=tab]').forEach(other => {
            const on = other === tab;
            other.setAttribute('aria-selected', on);
            other.classList.toggle('on', on);
            document.getElementById(other.getAttribute('aria-controls')).hidden = !on;
        });
    }));
    card.addEventListener('click', event => {
        const add = event.target.closest('[data-add-row]'), remove = event.target.closest('[data-remove-row]');
        if (add) {
            const li = card.querySelector('template#schema-row').content.firstElementChild.cloneNode(true);
            add.closest('.schema-level').querySelector(':scope > ol').append(li);
            changed();
            field(li, 'name').focus();
        } else if (remove) {
            remove.closest('.schema-row').remove();
            changed();
        }
    });
    card.addEventListener('change', event => {
        if (event.target.dataset?.field !== 'type') return;
        event.target.closest('.schema-row').querySelector(':scope > .schema-fields').hidden = !NESTING.includes(event.target.value);
    });
    card.addEventListener('input', event => {
        if (event.target.dataset?.field !== 'name') return;
        const li = event.target.closest('.schema-row');
        li.querySelector(':scope > .row [data-remove-row]').setAttribute('aria-label', `Remove ${event.target.value.trim() || 'this row'}`);
    });
}

document.querySelectorAll('#schema').forEach(mount);
