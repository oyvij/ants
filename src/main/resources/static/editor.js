// The markdown fields (textarea[data-md]) as the prototype's editor: a toolbar over the text area that writes markdown
// around the selection or in front of its line. A page that adds fields later, such as the Run page's drawer, calls
// mountEditors(root) again.
// ponytail: no Preview or Expand yet, which want a markdown renderer in the browser.
window.mountEditors = (root = document) => {
    for (const area of root.querySelectorAll('textarea[data-md]:not([data-done])')) {
        area.dataset.done = '1';
        const surround = (before, after = before) => {
            const { selectionStart: from, selectionEnd: to } = area;
            area.setRangeText(before + area.value.slice(from, to) + after, from, to);
            area.setSelectionRange(from + before.length, to + before.length);
            area.focus();
        };
        const prefix = text => {
            const line = area.value.lastIndexOf('\n', area.selectionStart - 1) + 1;
            area.setRangeText(text, line, line, 'end');
            area.focus();
        };
        const bar = Object.assign(document.createElement('div'), { className: 'bar' });
        for (const [label, title, act] of [
            ['<b>B</b>', 'Bold', () => surround('**')], ['<i>I</i>', 'Italic', () => surround('*')], ['H', 'Heading', () => prefix('## ')],
            ['•', 'List', () => prefix('- ')], ['1.', 'Numbered list', () => prefix('1. ')], ['&lt;/&gt;', 'Code', () => surround('`')],
            ['🔗', 'Link', () => surround('[', '](url)')],
        ]) bar.append(Object.assign(document.createElement('button'), { type: 'button', innerHTML: label, title, onclick: act }));
        const editor = Object.assign(document.createElement('div'), { className: 'ed' });
        area.replaceWith(editor);
        editor.append(bar, area);
    }
};
mountEditors();
