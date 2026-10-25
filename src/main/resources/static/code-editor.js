import {sample as inputsSample} from '/schema-card.js';

const selector = 'textarea[data-code-editor]:not([data-mounted])';
let codemirror;

async function mountAll() {
    if (!document.querySelector(selector)) return;
    codemirror ??= Promise.all(['@codemirror/view', '@codemirror/state', '@codemirror/commands', '@codemirror/language',
        '@codemirror/lang-javascript'].map(module => import(module)));
    const [view, state, commands, language, javascript] = await codemirror;
    document.querySelectorAll(selector).forEach(textarea => mount(textarea, {...view, ...state, ...commands, ...language, ...javascript}));
}

function element(tag, className, text) {
    const node = Object.assign(document.createElement(tag), {className});
    if (text !== undefined) node.textContent = text;
    return node;
}

function mount(textarea, cm) {
    textarea.dataset.mounted = '';
    const box = element('div', 'code-editor');
    const ed = element('div', 'ed tall json');
    const editor = new cm.EditorView({
        doc: textarea.value,
        extensions: [
            cm.history(), cm.drawSelection(), cm.lineNumbers(), cm.indentOnInput(), cm.bracketMatching(),
            cm.keymap.of([...cm.defaultKeymap, ...cm.historyKeymap, cm.indentWithTab]),
            cm.placeholder(textarea.placeholder), cm.javascript(), cm.syntaxHighlighting(cm.defaultHighlightStyle, {fallback: true}),
            cm.EditorView.updateListener.of(update => {
                if (update.docChanged) textarea.value = update.state.doc.toString();
            }),
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
    const schema = textarea.form?.querySelector('#schema');
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
    if (textarea.dataset.helper) openHelper(textarea, editor, cm);
}

async function openHelper(textarea, editor, cm) {
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
        form: () => [...new FormData(textarea.form)].filter(([name]) => name !== '_csrf'),
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

function show(result, {exitCode, output, payload}) {
    const badge = element('span', `pill ${exitCode === 0 ? 'p-done' : 'p-failed'}`, exitCode === 0 ? 'Succeeded · exit 0' : `Failed · exit ${exitCode}`);
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
