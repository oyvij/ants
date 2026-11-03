// Mermaid diagrams in every rendered markdown: each ```mermaid block, which both renderers emit as
// <pre><code class="language-mermaid">, shows as its diagram, now or once a page script adds it (the drawer, a live
// region's patch). A block inside #live is still streaming, so it stays code until it moves into the transcript.
// Mermaid is Ants' own copy under /vendor/mermaid/, imported once, and only by a page that has a block; it runs at its
// strict security level, so a diagram's source can't inject HTML or script. The code block stays, hidden, as the
// source to draw from. A block Mermaid can't parse or draw keeps its code shown, with Mermaid's error beside it, styled
// as an error: never a blank and never Mermaid's own error graphic. It is marked failed, so it isn't tried again.
// A wide diagram scrolls inside its box, as a wide table does.
// A diagram is drawn in the page's colours: Mermaid's base theme, fed ants.css's text, panel, line and accent colours.
// Those are light-dark() pairs the page's <meta name="color-scheme"> picks between, so they are resolved each time a
// diagram is drawn; on a page that follows the system's theme, a switch between light and dark draws every diagram
// again from its kept source.
const selector = 'pre:not([data-mermaid]) > code.language-mermaid';
let loading, drawing = Promise.resolve(), drawnDark, renders = 0;

function renderAll() {
    const blocks = [...document.querySelectorAll(selector)].filter(code => !code.closest('#live')).map(code => code.parentElement);
    if (!blocks.length) return;
    blocks.forEach(pre => pre.dataset.mermaid = 'pending');
    loading ??= import('/vendor/mermaid/mermaid.esm.min.mjs').then(({default: mermaid}) => {
        matchMedia('(prefers-color-scheme: dark)').addEventListener('change', redrawIfSchemeChanged);
        return mermaid;
    });
    drawAll(blocks);
}

function redrawIfSchemeChanged() {
    if (pageColours().dark !== drawnDark) drawAll([...document.querySelectorAll('pre[data-mermaid=drawn]')]);
}

function drawAll(blocks) {
    drawing = drawing.then(async () => {
        const mermaid = await loading;
        applyTheme(mermaid);
        for (const pre of blocks) await draw(mermaid, pre);
    });
}

async function draw(mermaid, pre) {
    const shown = pre.previousElementSibling?.classList.contains('mermaid') ? pre.previousElementSibling : null;
    try {
        const {svg} = await mermaid.render(`mermaid-${++renders}`, pre.textContent);
        const box = shown ?? Object.assign(document.createElement('div'), {className: 'mermaid'});
        box.innerHTML = svg;
        fitNaturalSize(box.querySelector('svg'));
        if (!shown) pre.before(box);
        pre.hidden = true;
        pre.dataset.mermaid = 'drawn';
    } catch (failure) {
        shown?.remove();
        pre.hidden = false;
        pre.after(Object.assign(document.createElement('div'), {className: 'mermaid-error', textContent: failure?.message ?? String(failure)}));
        pre.dataset.mermaid = 'failed';
    }
}

function fitNaturalSize(svg) {
    const viewBox = svg?.viewBox.baseVal;
    if (!viewBox?.width) return;
    svg.setAttribute('width', viewBox.width);
    svg.setAttribute('height', viewBox.height);
    svg.style.maxWidth = 'none';
}

function pageColours() {
    const probe = document.body.appendChild(document.createElement('i'));
    const resolve = value => {
        probe.style.color = value;
        return getComputedStyle(probe).color;
    };
    const names = ['text', 'panel', 'panel2', 'line', 'line2', 'muted', 'acc', 'accSoft', 'wait', 'waitSoft'];
    const colours = Object.fromEntries(names.map(name => [name, resolve(`var(--${name})`)]));
    colours.dark = resolve('light-dark(#000, #fff)') === 'rgb(255, 255, 255)';
    probe.remove();
    return colours;
}

function applyTheme(mermaid) {
    const {dark, text, panel, panel2, line, line2, muted, acc, accSoft, wait, waitSoft} = pageColours();
    drawnDark = dark;
    mermaid.initialize({
        startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true, theme: 'base',
        themeVariables: {
            darkMode: dark, fontFamily: getComputedStyle(document.body).fontFamily,
            background: panel, textColor: text, lineColor: muted,
            primaryColor: accSoft, primaryTextColor: text, primaryBorderColor: acc,
            secondaryColor: panel2, secondaryTextColor: text, secondaryBorderColor: line,
            tertiaryColor: line2, tertiaryTextColor: text, tertiaryBorderColor: line,
            mainBkg: accSoft, nodeBorder: acc, nodeTextColor: text,
            clusterBkg: panel2, clusterBorder: line, titleColor: text, edgeLabelBackground: panel,
            noteBkgColor: waitSoft, noteTextColor: text, noteBorderColor: wait,
            actorBkg: accSoft, actorBorder: acc, actorTextColor: text, actorLineColor: muted,
            signalColor: text, signalTextColor: text, loopTextColor: text,
            labelBoxBkgColor: panel2, labelBoxBorderColor: line, labelTextColor: text,
        },
    });
}

renderAll();
new MutationObserver(renderAll).observe(document.documentElement, {childList: true, subtree: true});
