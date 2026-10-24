// The markdown editor, on every textarea[data-markdown], now or once a page script adds it (the Run page's drawer):
// CodeMirror with the toolbar Bold, Italic, Heading, List, Numbered list, Code and Link (⌘B, ⌘I), Preview in a big
// modal, and ⤢ Expand, the same editor moved into the modal beside a live preview. The server renders both previews
// (POST /markdown), as it renders the Approve page and the Report. The textarea stays in the form, hidden, and carries
// what is posted, so without this script it is the field; a page script that sets its value fires `change` on it,
// and the editor takes the new value. A page with no markdown field loads no CodeMirror. A pasted image is stored in
// Ants and referenced from the text (Images.kt), which gives it to the Agent.
const selector = 'textarea[data-markdown]:not([data-mounted])';
let codemirror, style;

async function mountAll() {
    if (!document.querySelector(selector)) return;
    codemirror ??= Promise.all(['@codemirror/view', '@codemirror/state', '@codemirror/commands', '@codemirror/language',
        '@codemirror/lang-markdown', '@lezer/highlight'].map(module => import(module)));
    const [view, state, commands, language, markdown, highlight] = await codemirror;
    style ??= language.HighlightStyle.define([
        {tag: highlight.tags.heading1, fontSize: '1.45em', fontWeight: '700'}, {tag: highlight.tags.heading2, fontSize: '1.25em', fontWeight: '700'},
        {tag: highlight.tags.heading3, fontSize: '1.1em', fontWeight: '700'}, {tag: highlight.tags.strong, fontWeight: '700'},
        {tag: highlight.tags.emphasis, fontStyle: 'italic'}, {tag: highlight.tags.strikethrough, textDecoration: 'line-through'},
        {tag: highlight.tags.link, color: 'var(--acc)', textDecoration: 'underline'},
        {tag: highlight.tags.monospace, fontFamily: 'var(--mono)', fontSize: '.92em', backgroundColor: 'var(--line2)'},
        {tag: [highlight.tags.processingInstruction, highlight.tags.meta], color: 'var(--muted)'},
        {tag: highlight.tags.quote, color: 'var(--muted)', fontStyle: 'italic'}, {tag: highlight.tags.list, color: 'var(--acc)'},
    ]);
    document.querySelectorAll(selector).forEach(textarea => mount(textarea, {...view, ...state, ...commands, ...language, ...markdown, ...highlight}));
}

function mount(textarea, cm) {
    textarea.dataset.mounted = '';
    const surround = (view, before, after = before) => {
        if (view.state.readOnly) return true;
        const {from, to} = view.state.selection.main;
        view.dispatch({changes: {from, to, insert: before + view.state.sliceDoc(from, to) + after}, selection: {anchor: from + before.length, head: to + before.length}});
        view.focus();
        return true;
    };
    const prefix = (view, text) => {
        if (view.state.readOnly) return;
        view.dispatch({changes: {from: view.state.doc.lineAt(view.state.selection.main.from).from, insert: text}});
        view.focus();
    };
    const {ed, bar, status} = editorBox(textarea);
    const editor = new cm.EditorView({
        doc: textarea.value,
        extensions: [
            // A chat box's (data-enter-sends) Enter sends its form; Shift+Enter goes on to the keymaps, a new line.
            textarea.hasAttribute('data-enter-sends') ? cm.Prec.highest(cm.EditorView.domEventHandlers({keydown(event) {
                if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return false;
                event.preventDefault();
                textarea.form.requestSubmit();
                return true;
            }})) : [],
            cm.keymap.of([{key: 'Mod-b', run: view => surround(view, '**')}, {key: 'Mod-i', run: view => surround(view, '*')}]),
            cm.history(), cm.drawSelection(), cm.EditorView.lineWrapping, cm.keymap.of([...cm.defaultKeymap, ...cm.historyKeymap]),
            cm.placeholder(textarea.placeholder), cm.EditorState.readOnly.of(textarea.readOnly), cm.markdown(), cm.syntaxHighlighting(style),
            // A copied image or a screenshot to the clipboard goes to Ants and is referenced at the caret; text pastes as text.
            cm.EditorView.domEventHandlers({paste(event, view) {
                const files = [...event.clipboardData?.files ?? []];
                if (!files.length || view.state.readOnly) return false;
                event.preventDefault();
                pasteImages(view, files, status);
                return true;
            }}),
            cm.EditorView.updateListener.of(update => {
                if (!update.docChanged) return;
                textarea.value = update.state.doc.toString();
                textarea.dispatchEvent(new Event('input', {bubbles: true}));
                ed.classList.remove('invalid');
                status.textContent = '';
            }),
        ],
    });
    const button = (html, title, onclick) => {
        const b = Object.assign(document.createElement('button'), {type: 'button', innerHTML: html, title, onclick});
        bar.append(b);
    };
    button('<b>B</b>', 'Bold (⌘B)', () => surround(editor, '**'));
    button('<i>I</i>', 'Italic (⌘I)', () => surround(editor, '*'));
    button('H', 'Heading', () => prefix(editor, '## '));
    button('•', 'List', () => prefix(editor, '- '));
    button('1.', 'Numbered list', () => prefix(editor, '1. '));
    button('&lt;/&gt;', 'Code', () => surround(editor, '`'));
    button('🔗', 'Link', () => surround(editor, '[', '](url)'));
    bar.append(Object.assign(document.createElement('span'), {className: 'sp'}), status);
    if (textarea.readOnly) status.textContent = 'Read-only · Copy to edit';
    button('Preview', 'Preview in a big window', () => {
        const body = modal('Preview', 'mb md');
        render(body, editor.state.doc.toString());
    });
    button('⤢ Expand', 'Write in a big window with a live preview', () => {
        const label = [...textarea.labels?.[0]?.childNodes ?? []].find(node => node.nodeType === Node.TEXT_NODE && node.textContent.trim());
        const split = modal(label?.textContent.trim() || 'Edit', 'split');
        const left = Object.assign(document.createElement('div'), {className: 'left'}), preview = Object.assign(document.createElement('div'), {className: 'md'});
        split.append(left, preview);
        left.append(editor.dom);
        editor.focus();
        let shown = null;
        const timer = setInterval(() => {
            const text = editor.state.doc.toString();
            if (text !== shown) render(preview, shown = text);
        }, 400);
        split.closest('dialog').addEventListener('close', () => {
            clearInterval(timer);
            bar.after(editor.dom);
            editor.focus();
        });
    });
    // A click in the box that misses the text still puts the cursor in it, at the end.
    editor.dom.addEventListener('mousedown', event => {
        if (event.target.closest('.cm-content') || event.button !== 0) return;
        event.preventDefault();
        editor.focus();
        editor.dispatch({selection: {anchor: editor.state.doc.length}});
    });
    // A page script set the value, such as the Answer page's Reset to the recommendation.
    textarea.addEventListener('change', () => {
        if (textarea.value !== editor.state.doc.toString()) editor.dispatch({changes: {from: 0, to: editor.state.doc.length, insert: textarea.value}});
    });
    // `required` still stops the form; the browser can't point at the hidden textarea, so the editor says why.
    textarea.addEventListener('invalid', event => {
        event.preventDefault();
        ed.classList.add('invalid');
        status.textContent = textarea.validationMessage;
        editor.focus();
    });
    textarea.hidden = true;
    textarea.after(ed);
    ed.append(editor.dom);
}

/**
 * The editor box this editor puts after its hidden [textarea]:
 * div.ed, tall or readonly as the textarea is, with its bar and the bar's status, for the caller to fill.
 */
function editorBox(textarea) {
    const ed = Object.assign(document.createElement('div'), {className: 'ed'});
    ed.classList.toggle('tall', textarea.hasAttribute('data-tall'));
    ed.classList.toggle('readonly', textarea.readOnly);
    const bar = Object.assign(document.createElement('div'), {className: 'bar'});
    const status = Object.assign(document.createElement('span'), {className: 'st'});
    ed.append(bar);
    return {ed, bar, status};
}

/** Opens a modal over the page, as the shell's (Esc, the ✕ or a click outside closes it), and returns its body. */
function modal(title, bodyClass) {
    const dialog = Object.assign(document.createElement('dialog'), {className: 'modal'});
    dialog.innerHTML = '<form method="dialog" class="mh"><b></b><span class="sp"></span><span class="muted small">Esc to close</span>' +
        '<button class="ghost" title="Close" aria-label="Close">✕</button></form><div></div>';
    dialog.querySelector('b').textContent = title;
    const body = dialog.lastElementChild;
    body.className = bodyClass;
    dialog.addEventListener('close', () => dialog.remove());
    document.body.append(dialog);
    dialog.showModal();
    return body;
}

const csrf = () => document.querySelector('input[name=_csrf]')?.value ?? '';

/** Fills [element] with [text] rendered by the server. */
async function render(element, text) {
    const response = await fetch('/markdown', {method: 'POST', body: new URLSearchParams({_csrf: csrf(), text})});
    element.innerHTML = await response.text();
}

/**
 * Stores each of [files] in Ants (POST /images) and puts a reference to it, `![image](/images/<sha256>.<ext>)`, at
 * [view]'s caret; one that isn't an image, or is too big, isn't stored, and [status] says why.
 */
async function pasteImages(view, files, status) {
    for (const file of files) {
        status.textContent = 'Uploading the image…';
        const response = await fetch('/images', {method: 'POST', headers: {'X-CSRF-TOKEN': csrf()}, body: file});
        const text = await response.text();
        if (!response.ok) {
            status.textContent = text;
            return;
        }
        const insert = `![image](${text})`, {from, to} = view.state.selection.main;
        view.dispatch({changes: {from, to, insert}, selection: {anchor: from + insert.length}});
        status.textContent = '';
    }
}

mountAll();
new MutationObserver(mountAll).observe(document.documentElement, {childList: true, subtree: true});
