// A Dashboard's charts, drawn with Vega-Lite (bundled in /vendor/vega/): a chart's points as its kind says, or a custom
// chart's own spec. Each takes its colours from Ants' theme, shows tooltips, fits its Widget and is drawn again when the
// Widget is resized or the theme changes. A chart loads nothing from outside: its loader refuses every fetch.

/** Vega's loader, refusing every URL, so a spec can't fetch data or images from anywhere. */
const noFetch = Object.assign(vega.loader(), {
    sanitize: uri => Promise.reject(new Error(`A Dashboard chart loads nothing from outside, so not ${uri}`)),
});

/** Ants' colour [name], a CSS variable such as `--acc`, as the page shows it now, in Light or Dark mode. */
const probe = document.createElement('span');
probe.hidden = true;
document.body.append(probe);
function colour(name) {
    probe.style.color = `var(${name})`;
    return getComputedStyle(probe).color;
}

/** Vega-Lite's config: Ants' colours, type and lines. */
function theme() {
    const muted = colour('--muted'), line = colour('--line'), grid = colour('--line2'), text = colour('--text2');
    return {
        background: null, font: getComputedStyle(document.body).fontFamily, view: { stroke: null },
        range: { category: ['--acc', '--run', '--ok', '--wait', '--vio', '--bad', '--ctx', '--ask', '--rec', '--ans'].map(colour) },
        axis: { labelColor: muted, titleColor: muted, gridColor: grid, domainColor: line, tickColor: line, labelFontSize: 10, title: null },
        legend: { labelColor: text, orient: 'top', title: null },
        arc: { stroke: colour('--panel') }, line: { strokeWidth: 2 },
    };
}

const DATE = /^\d{4}-\d{2}(-\d{2})?([T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$/;

/**
 * The Vega-Lite spec drawing [points] as [kind]. x is a number, a date or text; bars treat x as categories, with short
 * labels for dates; pie and donut sum y per x.
 */
function chartSpec(kind, points, width, height) {
    const values = points.map(p => ({ ...p, series: p.series ?? 'value' }));
    const many = new Set(values.map(v => v.series)).size > 1;
    const xType = values.every(v => typeof v.x === 'number') ? 'quantitative' : values.every(v => DATE.test(v.x)) ? 'temporal' : 'nominal';
    const x = { field: 'x', type: xType }, y = { field: 'y', type: 'quantitative' };
    const color = { field: 'series', type: 'nominal', legend: many ? {} : null };
    const tooltip = [...(many ? [{ field: 'series' }] : []), { field: 'x', type: xType, ...(xType === 'temporal' && { format: '%b %d %Y %H:%M' }) }, { field: 'y' }];
    const bx = { field: 'x', type: 'ordinal', axis: { labelAngle: 0, labelOverlap: true, ...(xType === 'temporal' && { labelExpr: "timeFormat(toDate(datum.value), '%b %d')" }) } };
    const bTooltip = [...(many ? [{ field: 'series' }] : []), { field: 'x', type: 'ordinal' }, { field: 'y' }];
    const slices = { theta: { field: 'y', aggregate: 'sum', type: 'quantitative' }, color: { field: 'x', type: 'nominal', legend: { orient: 'right' } },
        tooltip: [{ field: 'x', type: 'nominal' }, { field: 'y', aggregate: 'sum', type: 'quantitative' }] };
    const drawn = {
        line: { mark: { type: 'line', point: values.length < 40 }, encoding: { x, y, color, tooltip } },
        area: { mark: { type: 'area', opacity: .75, line: true }, encoding: { x, y, color, tooltip } },
        bar: { mark: { type: 'bar', cornerRadiusEnd: 2 }, encoding: { x: bx, xOffset: { field: 'series' }, y, color, tooltip: bTooltip } },
        stacked: { mark: { type: 'bar', cornerRadiusEnd: 2 }, encoding: { x: bx, y, color, tooltip: bTooltip } },
        scatter: { mark: { type: 'point', filled: true, size: 70, opacity: .8 }, encoding: { x, y, color, tooltip } },
        pie: { mark: { type: 'arc' }, encoding: slices },
        donut: { mark: { type: 'arc', innerRadius: Math.max(10, Math.min(width, height) / 5) }, encoding: slices },
    }[kind] ?? {};
    return { data: { values }, ...drawn };
}

/** Draws the chart [element] at its size, unless it is drawn at that size already and not [again]. */
async function draw(element, again = false) {
    const width = element.clientWidth, height = element.clientHeight, size = `${width}x${height}`;
    if (!width || !height || (!again && element.dataset.drawn === size)) return;
    element.dataset.drawn = size;
    const spec = element.dataset.spec ? JSON.parse(element.dataset.spec) : chartSpec(element.dataset.kind, JSON.parse(element.dataset.points), width, height);
    const full = { ...spec, width, height, autosize: { type: 'fit', contains: 'padding' }, config: { ...theme(), ...(spec.config ?? {}) } };
    try {
        const drawing = await vegaEmbed(element, full, { mode: 'vega-lite', actions: false, renderer: 'svg', loader: noFetch, tooltip: { theme: 'custom' } });
        element.view?.finalize();
        element.view = drawing.view;
    } catch (error) {
        element.textContent = `Could not draw: ${error.message}`;
        element.classList.add('none');
    }
}

/** Draws each chart once its Widget has a size, and again whenever the Widget is resized. */
const sized = new ResizeObserver(entries => {
    for (const { target } of entries) {
        if (target.isConnected) draw(target);
        else { sized.unobserve(target); target.view?.finalize(); }
    }
});

/** Draws the charts in [root], a Widget's body or the whole page, now and as they are resized. */
export function drawCharts(root) {
    root.querySelectorAll('.chart').forEach(chart => sized.observe(chart));
}

// A theme switch draws every chart again in the new colours.
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => document.querySelectorAll('.chart').forEach(chart => draw(chart, true)));
