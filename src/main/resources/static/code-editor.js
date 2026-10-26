import {sample as inputsSample} from '/schema-card.js';

const selector = 'textarea[data-code-editor]:not([data-mounted])';
let codemirror;

async function mountAll() {
    if (!document.querySelector(selector)) return;
    codemirror ??= Promise.all(['@codemirror/view', '@codemirror/state', '@codemirror/commands', '@codemirror/language',
        '@codemirror/lang-javascript', '@codemirror/lint'].map(module => import(module)));
    const [view, state, commands, language, javascript, lint] = await codemirror;
    document.querySelectorAll(selector).forEach(textarea => mount(textarea, {...view, ...state, ...commands, ...language, ...javascript, ...lint}));
}

function element(tag, className, text) {
    const node = Object.assign(document.createElement(tag), {className});
    if (text !== undefined) node.textContent = text;
    return node;
}

function mount(textarea, cm) {
    textarea.dataset.mounted = '';
    let changed = () => {};
    const schema = textarea.form?.querySelector('#schema');
    // A schema card level swapped for another, kept in the code's history so that Ctrl-Z takes it back as it does a code
    // replace. Each swap holds the count of the user's own schema edits when it was made: once the user edits the rows
    // after it, undoing or redoing it would drop those edits, so it no longer touches the card.
    const swap = cm.StateEffect.define();
    let edits = 0, swapping = false;
    // Not the card's own change after a swap, which is on the card, nor the blur of a focused row as its level goes.
    const edited = event => { if (event.target !== schema && !swapping) edits++; };
    schema?.addEventListener('input', edited);
    schema?.addEventListener('change', edited);
    const box = element('div', 'code-editor');
    const ed = element('div', 'ed tall json');
    const editor = new cm.EditorView({
        doc: textarea.value,
        extensions: [
            cm.history(), cm.drawSelection(), cm.lineNumbers(), cm.indentOnInput(), cm.bracketMatching(),
            cm.keymap.of([...cm.defaultKeymap, ...cm.historyKeymap, cm.indentWithTab]),
            cm.placeholder(textarea.placeholder), cm.javascript(), cm.syntaxHighlighting(cm.defaultHighlightStyle, {fallback: true}),
            cm.invertedEffects.of(tr => tr.effects.filter(effect => effect.is(swap)).map(({value: {old, now, at}}) => swap.of({old: now, now: old, at}))),
            cm.EditorView.updateListener.of(update => {
                const swaps = update.transactions.flatMap(tr => tr.effects.filter(effect => effect.is(swap) && effect.value.at === edits));
                swapping = true;
                swaps.forEach(({value: {old, now}}) => old.replaceWith(now));
                swapping = false;
                if (swaps.length) schema.dispatchEvent(new Event('schema-replaced'));
                if (!update.docChanged) return;
                textarea.value = update.state.doc.toString();
                changed();
            }),
            ...textarea.dataset.check ? [cm.lintGutter()] : [],
        ],
    });
    ed.append(editor.dom);
    const sample = element('textarea', 'mono sample');
    Object.assign(sample, {rows: 3, placeholder: '{"text": "hello"}'});
    const run = element('button', 'primary', 'Test run');
    run.type = 'button';
    const result = element('div', 'test-run');
    const bar = element('div', 'row test-bar');
    const label = element('label', 'cap grow', 'Sample inputs, as JSON');
    label.append(sample);
    bar.append(label, run);
    box.append(ed, bar, result);
    textarea.hidden = true;
    textarea.after(box);
    if (schema) { // the sample follows the input schema until the user writes one of their own
        let own = false;
        const fill = () => {
            if (!own) sample.value = JSON.stringify(inputsSample(schema));
        };
        sample.addEventListener('input', () => own = sample.value.trim() !== '');
        schema.addEventListener('input', fill);
        schema.addEventListener('change', fill);
        fill();
    }
    run.addEventListener('click', async () => {
        run.disabled = true;
        result.replaceChildren(element('span', 'muted small', 'Running…'));
        try {
            const body = new URLSearchParams(new FormData(textarea.form));
            body.set(textarea.name, editor.state.doc.toString());
            body.set('sample', sample.value);
            const response = await fetch(textarea.dataset.codeEditor, {method: 'POST', body});
            show(result, await response.json());
        } catch (error) {
            result.replaceChildren(element('span', 'pill p-failed', `Test run failed: ${error.message}`));
        } finally {
            run.disabled = false;
        }
    });
    if (textarea.dataset.check) changed = checking(textarea, editor, cm, schema);
    if (textarea.dataset.helper) openHelper(textarea, editor, cm, swap, () => edits);
}

/**
 * Asks the validator about the page's unsaved code and schemas a moment after each edit, and shows its findings: on
 * the code's lines with CodeMirror's lint marks, and on the schema rows. Calls the returned function on each edit.
 */
function checking(textarea, editor, cm, schema) {
    let timer, asked = 0;
    const check = async () => {
        const mine = ++asked;
        const body = new URLSearchParams(new FormData(textarea.form));
        body.set(textarea.name, editor.state.doc.toString());
        let findings;
        try {
            ({findings} = await (await fetch(textarea.dataset.check, {method: 'POST', body})).json());
        } catch {
            return;
        }
        if (mine !== asked) return; // a later edit's answer is on its way
        const doc = editor.state.doc;
        editor.dispatch(cm.setDiagnostics(editor.state, findings.filter(f => f.line).map(f => {
            const line = doc.line(Math.min(f.line, doc.lines));
            return {from: line.from, to: line.to, severity: f.severity, message: f.message};
        })));
        textarea.form.querySelectorAll('[data-finding]').forEach(mark => mark.remove());
        for (const {row, severity, message} of findings.filter(f => f.row)) {
            const li = textarea.form.querySelector(`[name="${CSS.escape(row)}.name"]`)?.closest('.schema-row');
            const mark = element('div', `finding f-${severity}`, message);
            mark.dataset.finding = '';
            li?.querySelector(':scope > .row').after(mark);
        }
    };
    const soon = () => {
        clearTimeout(timer);
        timer = setTimeout(check, 400);
    };
    schema?.addEventListener('input', soon);
    schema?.addEventListener('change', soon);
    check();
    return soon;
}

async function openHelper(textarea, editor, cm, swap, edits) {
    if (document.readyState === 'loading') await new Promise(loaded => addEventListener('DOMContentLoaded', loaded, {once: true}));
    const panel = document.getElementById(textarea.dataset.helper);
    const csrf = new FormData(textarea.form).get('_csrf');
    const kept = 'ants.helper.' + location.pathname;
    let token = sessionStorage.getItem(kept) ?? '';
    const post = (path, fields) => fetch(path, {method: 'POST', body: new URLSearchParams({_csrf: csrf, token, ...fields})});
    ({token} = await (await post('/script-helper', {})).json());
    sessionStorage.setItem(kept, token);
    const relayed = {
        buffer: () => editor.state.doc.toString(),
        replace_buffer: ({code}) => {
            editor.dispatch({changes: {from: 0, to: editor.state.doc.length, insert: code}, annotations: cm.isolateHistory.of('full')}); // its own undo step, never joined to the user's typing
            return null;
        },
        replace_schemas: levels => { // each schema given arrives as its card level, rendered: swapped in together, as one undo step
            const effects = Object.entries({inputs: 'input-schema', output: 'output-schema'}).filter(([name]) => levels[name] !== undefined).map(([name, panel]) => {
                const rendered = document.createElement('template');
                rendered.innerHTML = levels[name];
                return swap.of({old: textarea.form.querySelector(`#${panel} > .schema-level`), now: rendered.content.firstElementChild, at: edits()});
            });
            editor.dispatch({effects, annotations: cm.isolateHistory.of('full')});
            return null;
        },
        link_secret: ({label}) => { // ticks a Secret from Settings, adding its box when Settings got it after the page loaded
            const linked = textarea.form.querySelector('[data-linked]');
            let box = [...linked.querySelectorAll('input[name=linked]')].find(input => input.value === label);
            if (!box) {
                box = Object.assign(document.createElement('input'), {type: 'checkbox', name: 'linked', value: label});
                linked.querySelector('[data-no-linked]')?.remove();
                const row = element('label', 'nowrap', ' ' + label);
                row.prepend(box);
                linked.append(row);
            }
            box.checked = true;
            box.dispatchEvent(new Event('change', {bubbles: true}));
            return null;
        },
        form:() => [...new FormData(textarea.form)].filter(([name]) => name !== '_csrf'),
    };
    stream.on('builder', async data => {
        const {id, tool, arguments: args} = JSON.parse(data);
        const fields = {call: id};
        try {
            fields.answer = JSON.stringify(await relayed[tool](args));
        } catch (error) {
            fields.error = error.message;
        }
        post('/script-helper/answer', fields);
    });
    const form = panel.querySelector('form'), message = panel.querySelector('textarea'), error = panel.querySelector('[role=alert]');
    const stop = panel.querySelector('.stop'), send = panel.querySelector('.send'), live = panel.querySelector('.live'), dialog = panel.querySelector('.dialog');
    stream.on('dialog', html => {
        const bottom = dialog.scrollHeight - dialog.scrollTop - dialog.clientHeight < 40;
        stream.patch(live, html);
        const busy = live.querySelector('#sh-state')?.dataset.busy === 'true';
        stop.hidden = !busy;
        send.hidden = busy;
        if (bottom) dialog.scrollTop = dialog.scrollHeight;
    });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (send.hidden || !message.value.trim()) return;
        const text = message.value;
        message.value = '';
        message.dispatchEvent(new Event('change'));
        const answer = await (await post('/script-helper/send', {message: text})).json();
        error.textContent = answer.error || '';
        if (answer.error) {
            message.value = text;
            message.dispatchEvent(new Event('change'));
        }
    });
    stop.addEventListener('click', () => post('/script-helper/stop', {}));
    stream.follow('builder=' + token);
    panel.dataset.token = token;
}

function show(result, {exitCode, output, payload, mismatches = []}) {
    const fits = mismatches.length === 0;
    const badge = element('span', `pill ${exitCode === 0 && fits ? 'p-done' : 'p-failed'}`,
        exitCode !== 0 ? `Failed · exit ${exitCode}` : fits ? 'Succeeded · exit 0' : 'Failed · exit 0, the payload breaks the output schema');
    const sections = [badge, element('h4', '', 'Output')];
    let parsed;
    try {
        parsed = JSON.parse(output);
    } catch {
        parsed = undefined;
    }
    sections.push(parsed === undefined ? element('pre', 'output', output || '(none)') : pretty(parsed, 'output'));
    sections.push(element('h4', '', 'Payload'));
    sections.push(payload === null || payload === undefined ? element('span', 'muted small', 'None handed back with ants.output') : pretty(payload, 'payload'));
    if (!fits) {
        const list = element('ul', 'warn mismatches');
        list.setAttribute('aria-label', 'How the payload breaks the output schema');
        list.append(...mismatches.map(mismatch => element('li', '', mismatch)));
        sections.push(list);
    }
    result.replaceChildren(...sections);
}

function pretty(value, className) {
    const pre = element('pre', `${className} json`);
    const text = JSON.stringify(value, null, 2);
    const token = /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g;
    let last = 0;
    for (const match of text.matchAll(token)) {
        pre.append(text.slice(last, match.index));
        const [whole, string, colon, literal] = match;
        const kind = string !== undefined ? (colon ? 'j-key' : 'j-str') : literal !== undefined ? 'j-lit' : 'j-num';
        pre.append(element('span', kind, string !== undefined ? string : whole));
        if (colon) pre.append(colon);
        last = match.index + whole.length;
    }
    pre.append(text.slice(last));
    return pre;
}

mountAll();
new MutationObserver(mountAll).observe(document.documentElement, {childList: true, subtree: true});
