// Fills a "Chart gallery" dashboard with one chart of each kind, from real data in the Ants repo.
const { execFileSync: run } = require('child_process');
const REPO = require('os').homedir() + '/ants', BASE = process.argv[2] || 'http://localhost:8765', D = `${BASE}/api/dashboards/gallery`;
const call = (m, url, b) => fetch(url, { method: m, body: JSON.stringify(b) }).then(async r => { const j = await r.json(); if (!r.ok) throw new Error(j.error); return j; });
const git = (...a) => run('git', ['-C', REPO, ...a], { maxBuffer: 1 << 26 }).toString().trim().split('\n').filter(Boolean);
const week = d => { const t = new Date(d); t.setUTCDate(t.getUTCDate() - (t.getUTCDay() + 6) % 7); return t.toISOString().slice(0, 10); };

(async () => {
  await call('POST', `${BASE}/api/dashboards`, { id: 'gallery', name: 'Chart gallery' });
  const W = [
    ['commits', 'chart', 'line',    'Commits per day · 30 days',            0, 0, 6, 4],
    ['churn',   'chart', 'area',    'Lines added and removed per week',     6, 0, 6, 4],
    ['issues',  'chart', 'bar',     'Issues opened and closed per week',    0, 4, 6, 4],
    ['areas',   'chart', 'stacked', 'Files changed per week by area',       6, 4, 6, 4],
    ['hot',     'chart', 'scatter', 'Kotlin files · size vs times changed', 0, 8, 6, 4],
    ['langs',   'chart', 'pie',     'Lines of code by language',            6, 8, 3, 4],
    ['disk',    'chart', 'donut',   'Disk on /',                            9, 8, 3, 4],
    ['clock',   'vega',  null,      'When commits happen · custom Vega-Lite', 0, 12, 12, 4],
  ];
  for (const [id, type, kind, title, x, y, w, h] of W) await call('POST', `${D}/widgets`, { id, type, kind, title, x, y, w, h });
  const post = (id, data) => call('POST', `${D}/widgets/${id}`, data);

  const days = git('log', '--since=30.days', '--format=%ad|%s', '--date=short');
  const pts = [];
  for (let i = 29; i >= 0; i--) { const day = new Date(Date.now() - i * 864e5).toISOString().slice(0, 10), on = days.filter(l => l.startsWith(day));
    pts.push({ x: day, y: on.length, series: 'all commits' }, { x: day, y: on.filter(l => /\(#\d+\)$/.test(l)).length, series: 'merged PRs' }); }
  await post('commits', { points: pts });

  const churn = {}; let wk;
  for (const l of git('log', '--since=12.weeks', '--numstat', '--format=@%ad', '--date=short')) {
    if (l[0] === '@') { wk = week(l.slice(1)); continue; }
    const [a, r] = l.split('\t'); if (a === '-') continue; churn[wk] ??= { a: 0, r: 0 }; churn[wk].a += +a; churn[wk].r += +r;
  }
  await post('churn', { points: Object.entries(churn).flatMap(([x, c]) => [{ x, y: c.a, series: 'added' }, { x, y: c.r, series: 'removed' }]) });

  const iss = JSON.parse(run('gh', ['issue', 'list', '-R', 'oyvij/ants', '--state', 'all', '--limit', '1000', '--json', 'createdAt,closedAt']).toString());
  const since = week(new Date(Date.now() - 7 * 7 * 864e5)), weeks = {};
  for (const i of iss) for (const [k, at] of [['opened', i.createdAt], ['closed', i.closedAt]]) if (at && week(at) >= since) { const x = week(at); weeks[x] ??= { opened: 0, closed: 0 }; weeks[x][k]++; }
  await post('issues', { points: Object.entries(weeks).flatMap(([x, c]) => [{ x, y: c.opened, series: 'opened' }, { x, y: c.closed, series: 'closed' }]) });

  const areas = {};
  for (const l of git('log', '--since=8.weeks', '--name-only', '--format=@%ad', '--date=short')) {
    if (l[0] === '@') { wk = week(l.slice(1)); continue; }
    const a = l.startsWith('src/main/kotlin') ? 'Kotlin' : l.startsWith('src/test') ? 'tests' : l.startsWith('src/main/jte') ? 'pages' : l.startsWith('src/main/resources') ? 'static & resources' : l.startsWith('docs') ? 'docs' : 'other';
    const k = wk + '|' + a; areas[k] = (areas[k] || 0) + 1;
  }
  await post('areas', { points: Object.entries(areas).map(([k, y]) => { const [x, series] = k.split('|'); return { x, y, series }; }) });

  const changes = {}; for (const f of git('log', '--format=', '--name-only', '--', 'src/main/kotlin')) changes[f] = (changes[f] || 0) + 1;
  const files = git('ls-files', 'src/main/kotlin');
  const sizes = Object.fromEntries(run('wc', ['-l', ...files], { cwd: REPO }).toString().trim().split('\n').map(l => l.trim().split(/\s+/)).filter(([, f]) => f !== 'total').map(([n, f]) => [f, +n]));
  await post('hot', { points: files.map(f => ({ x: sizes[f], y: changes[f] || 0, series: f.replace(/.*\//, '') })).map(p => ({ ...p, series: 'Kotlin file', file: p.series })) });

  const byExt = {}; const all = git('ls-files', 'src', 'docs', 'scripts');
  const wc = run('xargs', ['wc', '-l'], { cwd: REPO, input: all.join('\n'), maxBuffer: 1 << 26 }).toString().trim().split('\n');
  for (const l of wc) { const [n, f] = l.trim().split(/\s+/); if (f === 'total' || !f) continue; const e = (f.match(/\.(\w+)$/) || [, 'other'])[1]; byExt[e] = (byExt[e] || 0) + +n; }
  const top = Object.entries(byExt).sort((a, b) => b[1] - a[1]), rest = top.slice(6).reduce((s, [, n]) => s + n, 0);
  await post('langs', { points: [...top.slice(0, 6), ...(rest ? [['other', rest]] : [])].map(([x, y]) => ({ x, y })) });

  const [, size, used, avail] = run('df', ['-BG', '--output=source,size,used,avail', '/']).toString().split('\n')[1].trim().split(/\s+/).map(s => parseInt(s));
  await post('disk', { points: [{ x: 'used GB', y: used }, { x: 'free GB', y: avail }] });

  // An agent would write this: a heatmap, which no built-in kind covers.
  const hours = git('log', '--since=90.days', '--format=%ad', '--date=format:%a %H');
  const cells = {}; for (const h of hours) cells[h] = (cells[h] || 0) + 1;
  await post('clock', { spec: {
    data: { values: Object.entries(cells).map(([k, n]) => ({ day: k.slice(0, 3), hour: +k.slice(4), commits: n })) },
    mark: { type: 'rect', cornerRadius: 2 },
    encoding: { x: { field: 'hour', type: 'ordinal', scale: { domain: [...Array(24).keys()] } },
                y: { field: 'day', type: 'ordinal', sort: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] },
                color: { field: 'commits', type: 'quantitative', scale: { scheme: 'oranges' }, legend: { orient: 'right', title: null } },
                tooltip: [{ field: 'day' }, { field: 'hour' }, { field: 'commits' }] } } });
  console.log('gallery filled');
})().catch(e => { console.error(e.message); process.exit(1); });
