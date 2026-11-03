// Mermaid diagrams in every rendered markdown: each ```mermaid block, which both renderers emit as
// <pre><code class="language-mermaid">, shows as its diagram, now or once a page script adds it (the drawer, a live
// region's patch). A block inside #live is still streaming, so it stays code until it moves into the transcript.
// Mermaid is Ants' own copy under /vendor/mermaid/, imported once, and only by a page that has a block; it runs at its
// strict security level, so a diagram's source can't inject HTML or script. The diagram is drawn in the page's colours,
// ants.css's light-dark() pairs resolved when it is drawn, and drawn again when the system switches light and dark.
// The code block stays, hidden, as the source to draw from; a block Mermaid can't draw keeps it shown, with Mermaid's
// error beside it. A wide diagram scrolls inside its box, as a wide table does.
const selector = 'pre:not([data-mermaid]) > code.language-mermaid';
let mermaid, drawn = 0;

async function renderAll() {
    const blocks = [...document.querySelectorAll(selector)].filter(code => !code.closest('#live'));
    if (!blocks.length) return;
    blocks.forEach(code => code.parentElement.dataset.mermaid = 'pending');
    mermaid ??= import('/vendor/mermaid/mermaid.esm.min.mjs').then(module => {
        matchMedia('(prefers-color-scheme: dark)').addEventListener('change', redraw);
        return module.default;
    });
    const library = await mermaid;
    configure(library);
    for (const code of blocks) await draw(library, code.parentElement);
}

async function redraw() {
    const library = await mermaid;
    configure(library);
    for (const pre of document.querySelectorAll('pre[data-mermaid=drawn]')) await draw(library, pre);
}

async function draw(library, pre) {
    const box = pre.previousElementSibling?.classList.contains('mermaid') ? pre.previousElementSibling : document.createElement('div');
    const error = pre.nextElementSibling?.classList.contains('mermaid-error') ? pre.nextElementSibling : null;
    try {
        const {svg} = await library.render(`mermaid-${++drawn}`, pre.textContent);
        box.className = 'mermaid';
        box.innerHTML = svg;
        fitNaturalSize(box.querySelector('svg'));
        if (!box.isConnected) pre.before(box);
        pre.hidden = true;
        error?.remove();
        pre.dataset.mermaid = 'drawn';
    } catch (failure) {
        box.remove();
        pre.hidden = false;
        const shown = error ?? Object.assign(document.createElement('div'), {className: 'mermaid-error'});
        shown.textContent = failure?.message ?? String(failure);
        if (!shown.isConnected) pre.after(shown);
        pre.dataset.mermaid = 'failed';
    }
}

function fitNaturalSize(svg) {
    const {width, height} = svg.viewBox.baseVal;
    if (!width) return;
    svg.setAttribute('width', width);
    svg.setAttribute('height', height);
    svg.style.maxWidth = 'none';
}

function configure(library) {
    const probe = document.body.appendChild(document.createElement('span'));
    const colour = name => {
        probe.style.color = `var(${name})`;
        return getComputedStyle(probe).color;
    };
    const scheme = document.querySelector('meta[name=color-scheme]')?.content ?? 'light';
    const darkMode = scheme === 'dark' || scheme.includes('dark') && scheme.includes('light') && matchMedia('(prefers-color-scheme: dark)').matches;
    const themeVariables = {
        darkMode, fontFamily: getComputedStyle(document.documentElement).fontFamily,
        background: colour('--panel'), textColor: colour('--text'), lineColor: colour('--muted'),
        primaryColor: colour('--accSoft'), primaryTextColor: colour('--text'), primaryBorderColor: colour('--acc'),
        secondaryColor: colour('--panel2'), secondaryTextColor: colour('--text'), secondaryBorderColor: colour('--line'),
        tertiaryColor: colour('--line2'), tertiaryTextColor: colour('--text'), tertiaryBorderColor: colour('--line'),
        mainBkg: colour('--accSoft'), nodeBorder: colour('--acc'), nodeTextColor: colour('--text'),
        clusterBkg: colour('--panel2'), clusterBorder: colour('--line'), titleColor: colour('--text'),
        edgeLabelBackground: colour('--panel'), noteBkgColor: colour('--waitSoft'), noteTextColor: colour('--text'),
        noteBorderColor: colour('--wait'), actorBkg: colour('--accSoft'), actorBorder: colour('--acc'),
        actorTextColor: colour('--text'), signalColor: colour('--text'), signalTextColor: colour('--text'),
        labelBoxBkgColor: colour('--panel2'), labelBoxBorderColor: colour('--line'), labelTextColor: colour('--text'),
    };
    probe.remove();
    library.initialize({startOnLoad: false, securityLevel: 'strict', theme: 'base', themeVariables});
}

renderAll();
new MutationObserver(renderAll).observe(document.documentElement, {childList: true, subtree: true});
