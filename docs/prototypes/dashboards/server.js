// Prototype: many dashboards whose widgets Processes post data to.
const http = require('http'), fs = require('fs'), path = require('path');
const PORT = process.env.PORT || 8765, STORE = path.join(__dirname, 'dashboards.json');

// Each widget type has a fixed data contract, checked on every post.
const TYPES = {
  number:   { empty: () => ({ value: 0 }),          check: d => typeof d.value === 'number',    needs: '{value:number, unit?, delta?}' },
  text:     { empty: () => ({ text: '' }),          check: d => typeof d.text === 'string',     needs: '{text:string}' },
  list:     { empty: () => ({ items: [] }),         check: d => Array.isArray(d.items) && d.items.every(i => typeof i.title === 'string'), needs: '{items:[{title, url?, note?}]}' },
  log:      { empty: () => ({ lines: [] }),         check: d => typeof d.line === 'string',     needs: '{line:string, level?:"info"|"warn"|"error"}' },
  status:   { empty: () => ({ items: [] }),         check: d => Array.isArray(d.items) && d.items.every(i => i.name && ['ok','warn','fail','running'].includes(i.state)), needs: '{items:[{name, state:"ok"|"warn"|"fail"|"running"}]}' },
  progress: { empty: () => ({ done: 0, total: 1 }), check: d => typeof d.done === 'number' && typeof d.total === 'number', needs: '{done:number, total:number}' },
  vega:     { empty: () => ({ spec: null }),        check: d => d.spec && typeof d.spec === 'object' && !JSON.stringify(d.spec).includes('"url"'), needs: '{spec:<Vega-Lite JSON with its data in data.values; no URLs>}' },
  chart:    { empty: () => ({ points: [] }),        check: d => [].concat(d.point || d.points || []).length > 0 && [].concat(d.point || d.points).every(p => p.x != null && typeof p.y === 'number'), needs: '{point:{x, y:number, series?}} or {points:[…]}; same series and x replaces' },
};

let store = fs.existsSync(STORE) ? JSON.parse(fs.readFileSync(STORE, 'utf8')) : { dashboards: [] };
let saveTimer; const save = () => { clearTimeout(saveTimer); saveTimer = setTimeout(() => fs.writeFileSync(STORE, JSON.stringify(store, null, 1)), 300); };
// A chart's kind only changes how it is drawn; every kind takes the same points, so switching never breaks a poster.
const KINDS = ['line', 'area', 'bar', 'stacked', 'scatter', 'pie', 'donut'];
const slug = s => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'dashboard';
const find = id => store.dashboards.find(d => d.id === id);
const summary = d => ({ id: d.id, name: d.name, widgets: d.widgets.length, drafts: d.widgets.filter(w => w.draft).length });

const clients = new Set();
const push = (ev, obj) => { for (const c of clients) c.write(`event: ${ev}\ndata: ${JSON.stringify(obj)}\n\n`); };

function newWidget(dash, d) {
  const bottom = Math.max(0, ...dash.widgets.map(w => w.y + w.h));
  return { id: d.id, type: d.type, title: d.title || d.id, x: d.x ?? 0, y: d.y ?? bottom, w: d.w || 4, h: d.h || 3,
           draft: !!d.draft, by: d.by || 'you', data: TYPES[d.type].empty(), ...(d.type === 'chart' && { kind: KINDS.includes(d.kind) ? d.kind : 'line' }) };
}
function applyPost(w, d) {
  if (w.type === 'log') { w.data.lines.push({ line: d.line, level: d.level || 'info', at: Date.now() }); w.data.lines = w.data.lines.slice(-200); }
  else if (w.type === 'chart') {
    for (const p of [].concat(d.point || d.points)) {
      const i = w.data.points.findIndex(q => q.x === p.x && (q.series || '') === (p.series || ''));
      i >= 0 ? (w.data.points[i] = p) : w.data.points.push(p);
    }
    w.data.points = w.data.points.slice(-500);
  } else w.data = d;
}

const body = req => new Promise(r => { let s = ''; req.on('data', c => s += c); req.on('end', () => { try { r(JSON.parse(s || '{}')); } catch { r(null); } }); });
const json = (res, code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj, null, 2)); };

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x'), p = url.pathname, M = req.method; let m;
  if (M === 'GET' && (p === '/' || p === '/dashboards' || p.startsWith('/dashboards/'))) { res.writeHead(200, { 'content-type': 'text/html', 'cache-control': 'no-store' }); return fs.createReadStream(path.join(__dirname, 'index.html')).pipe(res); }
  if (M === 'GET' && p === '/api/events') {
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
    res.write(': hi\n\n'); clients.add(res); req.on('close', () => clients.delete(res)); return;
  }
  // What a helper or Agent would read from list_widgets.
  if (M === 'GET' && p === '/api/types') return json(res, 200, Object.fromEntries(Object.entries(TYPES).map(([k, t]) => [k, t.needs])));
  if (M === 'GET' && p === '/api/kinds') return json(res, 200, KINDS);
  if (M === 'GET' && p === '/api/widgets') return json(res, 200, store.dashboards.flatMap(d => d.widgets.map(w => ({ address: `${d.id}/${w.id}`, type: w.type, title: w.title, draft: w.draft, kind: w.kind, accepts: TYPES[w.type].needs }))));

  if (M === 'GET' && p === '/api/dashboards') return json(res, 200, store.dashboards.map(summary));
  if (M === 'POST' && p === '/api/dashboards') {
    const d = await body(req) || {};
    if (d.id && find(d.id)) return json(res, 200, find(d.id)); // same id is safe to repeat
    // The id is fixed at creation so posts and links survive renames; the title is free to change.
    const id = d.id ? slug(d.id) : Math.random().toString(36).slice(2, 8);
    const dash = { id, name: d.name || 'Untitled dashboard', widgets: [] }; store.dashboards.push(dash); save(); push('dashboards', {}); return json(res, 200, dash);
  }
  if ((m = p.match(/^\/api\/dashboards\/([\w-]+)$/))) {
    const dash = find(m[1]); if (!dash) return json(res, 404, { error: `no dashboard '${m[1]}'` });
    if (M === 'GET') return json(res, 200, dash);
    if (M === 'PATCH') { const d = await body(req); if (typeof d?.name === 'string') dash.name = d.name.trim() || 'Untitled dashboard'; save(); push('dashboards', {}); return json(res, 200, dash); }
    if (M === 'DELETE') { store.dashboards = store.dashboards.filter(d => d !== dash); save(); push('dashboards', { deleted: dash.id }); return json(res, 200, { ok: true }); }
  }
  if ((m = p.match(/^\/api\/dashboards\/([\w-]+)\/(widgets|layout)$/)) && M === 'POST') {
    const dash = find(m[1]); if (!dash) return json(res, 404, { error: `no dashboard '${m[1]}'` });
    const d = await body(req);
    if (m[2] === 'layout') { for (const l of d || []) { const w = dash.widgets.find(w => w.id === l.id); if (w) Object.assign(w, { x: l.x, y: l.y, w: l.w, h: l.h }); } save(); return json(res, 200, { ok: true }); }
    if (!d || !TYPES[d.type]) return json(res, 400, { error: 'needs {type, id?, title?} with type one of ' + Object.keys(TYPES).join(', ') });
    // Without an id Ants makes one that never changes and is never reused, so a deleted widget's posts can't land on a new one.
    if (!d.id) do d.id = Math.random().toString(36).slice(2, 8); while (store.dashboards.some(x => x.widgets.some(w => w.id === d.id)));
    const had = dash.widgets.find(w => w.id === d.id); if (had) return json(res, 200, had); // same id is safe to repeat
    const w = newWidget(dash, d); dash.widgets.push(w); save(); push('added', { dashboard: dash.id, widget: w }); push('dashboards', {}); return json(res, 200, w);
  }
  if ((m = p.match(/^\/api\/dashboards\/([\w-]+)\/widgets\/([\w-]+)(\/publish)?$/))) {
    const dash = find(m[1]); if (!dash) return json(res, 404, { error: `no dashboard '${m[1]}'` });
    const w = dash.widgets.find(w => w.id === m[2]); if (!w) return json(res, 404, { error: `dashboard '${dash.id}' has no widget '${m[2]}'; its widgets are ${dash.widgets.map(w => w.id).join(', ') || 'none'}` });
    if (M === 'DELETE') { dash.widgets = dash.widgets.filter(x => x !== w); save(); push('removed', { dashboard: dash.id, id: w.id }); push('dashboards', {}); return json(res, 200, { ok: true }); }
    if (M === 'PATCH') { const d = await body(req); if (typeof d?.title === 'string') w.title = d.title.trim() || w.type; if (w.type === 'chart' && KINDS.includes(d?.kind)) w.kind = d.kind; save(); push('widget', { dashboard: dash.id, widget: w }); return json(res, 200, w); }
    if (M === 'POST' && m[3]) { w.draft = false; save(); push('widget', { dashboard: dash.id, widget: w }); push('dashboards', {}); return json(res, 200, w); }
    if (M === 'POST') {
      const d = await body(req); if (!d) return json(res, 400, { error: 'body is not JSON' });
      if (!TYPES[w.type].check(d)) return json(res, 422, { error: `widget '${dash.id}/${w.id}' is a ${w.type} widget and needs ${TYPES[w.type].needs}` });
      applyPost(w, d); save(); push('widget', { dashboard: dash.id, widget: w }); return json(res, 200, { ok: true });
    }
  }
  json(res, 404, { error: 'not found' });
}).listen(PORT, '0.0.0.0', () => console.log(`dashboard prototype on :${PORT}`));
