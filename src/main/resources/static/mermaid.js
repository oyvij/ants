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
// diagram is drawn; when the system switches light and dark, every drawn diagram is drawn again from its kept source.
const selector = 'pre:not([data-mermaid]) > code.language-mermaid';
const systemDark = matchMedia('(prefers-color-scheme: dark)');
let loading, renders = 0;

async function renderAll() {
    const blocks = [...document.querySelectorAll(selector)].filter(code => !code.closest('#live')).map(code => code.parentElement);
    if (!blocks.length) return;
    blocks.forEach(pre => pre.dataset.mermaid = 'pending');
    loading ??= import('/vendor/mermaid/mermaid.esm.min.mjs').then(({default: mermaid}) => {
        systemDark.addEventListener('change', redrawAll);
        return mermaid;
    });
    const mermaid = await loading;
    configure(mermaid);
    for (const pre of blocks) await draw(mermaid, pre);
}

async function redrawAll() {
    const mermaid = await loading;
    configure(mermaid);
    for (const pre of document.querySelectorAll('pre[data-mermaid=drawn]')) await draw(mermaid, pre);
}

async function draw(mermaid, pre) {
    const drawn = pre.previousElementSibling?.classList.contains('mermaid') ? pre.previousElementSibling : null;
    try {
        const {svg} = await mermaid.render(`mermaid-${++renders}`, pre.textContent);
        const box = drawn ?? Object.assign(document.createElement('div'), {className: 'mermaid'});
        box.innerHTML = svg;
        fitNaturalSize(box.querySelector('svg'));
        if (!drawn) pre.before(box);
        pre.hidden = true;
        pre.dataset.mermaid = 'drawn';
    } catch (failure) {
        drawn?.remove();
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

function configure(mermaid) {
    const probe = document.body.appendChild(document.createElement('i'));
    const colour = name => {
        probe.style.color = `var(${name})`;
        return getComputedStyle(probe).color;
    };
    const scheme = document.querySelector('meta[name=color-scheme]')?.content ?? 'light';
    const darkMode = scheme === 'dark' || scheme === 'light dark' && systemDark.matches;
    const themeVariables = {
        darkMode, fontFamily: getComputedStyle(document.body).fontFamily,
        background: colour('--panel'), textColor: colour('--text'), lineColor: colour('--muted'),
        primaryColor: colour('--accSoft'), primaryTextColor: colour('--text'), primaryBorderColor: colour('--acc'),
        secondaryColor: colour('--panel2'), secondaryTextColor: colour('--text'), secondaryBorderColor: colour('--line'),
        tertiaryColor: colour('--line2'), tertiaryTextColor: colour('--text'), tertiaryBorderColor: colour('--line'),
        mainBkg: colour('--accSoft'), nodeBorder: colour('--acc'), nodeTextColor: colour('--text'),
        clusterBkg: colour('--panel2'), clusterBorder: colour('--line'), titleColor: colour('--text'),
        edgeLabelBackground: colour('--panel'), noteBkgColor: colour('--waitSoft'), noteTextColor: colour('--text'),
        noteBorderColor: colour('--wait'), actorBkg: colour('--accSoft'), actorBorder: colour('--acc'),
        actorTextColor: colour('--text'), actorLineColor: colour('--muted'), signalColor: colour('--text'),
        signalTextColor: colour('--text'), labelBoxBkgColor: colour('--panel2'), labelBoxBorderColor: colour('--line'),
        labelTextColor: colour('--text'), loopTextColor: colour('--text'),
    };
    probe.remove();
    mermaid.initialize({startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true, theme: 'base', themeVariables});
}

renderAll();
new MutationObserver(renderAll).observe(document.documentElement, {childList: true, subtree: true});
