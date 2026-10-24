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
    sections.push(payload === null || payload === undefined ? element('span', 'muted small', 'None written to ANTS_OUTPUT') : pretty(payload, 'payload'));
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
