// Mermaid diagrams in every rendered markdown: each ```mermaid block, which both renderers emit as
// <pre><code class="language-mermaid">, shows as its diagram, now or once a page script adds it (the drawer, a live
// region's patch). A block inside #live is still streaming, so it stays code until it moves into the transcript.
// Mermaid is Ants' own copy under /vendor/mermaid/, imported once, and only by a page that has a block; it runs at its
// strict security level, so a diagram's source can't inject HTML or script. The code block stays, hidden, as the
// source to draw from. A block Mermaid can't parse or draw keeps its code shown, with Mermaid's error beside it, styled
// as an error: never a blank and never Mermaid's own error graphic. It is marked failed, so it isn't tried again.
// A wide diagram scrolls inside its box, as a wide table does.
const selector = 'pre:not([data-mermaid]) > code.language-mermaid';
let loading, renders = 0;

async function renderAll() {
    const blocks = [...document.querySelectorAll(selector)].filter(code => !code.closest('#live')).map(code => code.parentElement);
    if (!blocks.length) return;
    blocks.forEach(pre => pre.dataset.mermaid = 'pending');
    loading ??= import('/vendor/mermaid/mermaid.esm.min.mjs').then(({default: mermaid}) => {
        mermaid.initialize({startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true});
        return mermaid;
    });
    const mermaid = await loading;
    for (const pre of blocks) await draw(mermaid, pre);
}

async function draw(mermaid, pre) {
    try {
        const {svg} = await mermaid.render(`mermaid-${++renders}`, pre.textContent);
        const box = Object.assign(document.createElement('div'), {className: 'mermaid', innerHTML: svg});
        fitNaturalSize(box.querySelector('svg'));
        pre.before(box);
        pre.hidden = true;
        pre.dataset.mermaid = 'drawn';
    } catch (failure) {
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

renderAll();
new MutationObserver(renderAll).observe(document.documentElement, {childList: true, subtree: true});
