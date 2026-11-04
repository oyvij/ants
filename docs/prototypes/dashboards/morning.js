// Example Process "Morning briefing": scripts collect, one agent explains.
// Run: node morning.js [http://host:8765]
const { execFileSync, execFile } = require('child_process');
const os = require('os');
const BASE = process.argv[2] || 'http://localhost:8765';
const call = (method, path, data) => fetch(BASE + path, { method, body: JSON.stringify(data) }).then(r => r.json());
const D = '/api/dashboards/morning';
const post = (id, data) => call('POST', `${D}/widgets/${id}`, data);
const step = (line, level) => { console.log(line); return post('steps', { line, level }); };

// Services to probe, as SERVICES="Jellyfin:8096,Pi-hole:8080".
const SERVICES = Object.fromEntries((process.env.SERVICES || '').split(',').filter(Boolean).map(s => s.split(':').reverse()));

(async () => {
  await call('POST', '/api/dashboards', { id: 'morning', name: 'Morning briefing' });
  for (const w of [
    { id: 'news',     type: 'list',   title: 'Hacker News · top 10',         x: 0, y: 0, w: 5, h: 7 },
    { id: 'picks',    type: 'list',   title: 'Worth your time · agent',      x: 5, y: 0, w: 4, h: 4 },
    { id: 'services', type: 'status', title: 'Home lab',                     x: 9, y: 0, w: 3, h: 7 },
    { id: 'commits',  type: 'chart',  title: 'Ants commits per day · 30 days', x: 5, y: 4, w: 4, h: 3 },
    { id: 'issues',   type: 'number', title: 'Open Ants issues',             x: 0, y: 7, w: 2, h: 2 },
    { id: 'disk',     type: 'number', title: 'Disk used on /',               x: 2, y: 7, w: 2, h: 2 },
    { id: 'read',     type: 'text',   title: "Agent's read",                 x: 4, y: 7, w: 5, h: 2 },
    { id: 'steps',    type: 'log',    title: 'Process steps',                x: 9, y: 7, w: 3, h: 2 },
  ]) await call('POST', `${D}/widgets`, w); // same id is safe to repeat

  await step('Script Task: fetch Hacker News');
  const ids = (await (await fetch('https://hacker-news.firebaseio.com/v0/topstories.json')).json()).slice(0, 10);
  const stories = await Promise.all(ids.map(async id => (await fetch(`https://hacker-news.firebaseio.com/v0/item/${id}.json`)).json()));
  await post('news', { items: stories.map(s => ({ title: s.title, url: s.url || `https://news.ycombinator.com/item?id=${s.id}`, note: `${s.score} points · ${s.descendants || 0} comments` })) });

  await step('Script Task: probe home-lab services');
  const services = await Promise.all(Object.entries(SERVICES).map(async ([port, name]) => {
    try { const r = await fetch(`http://localhost:${port}/`, { signal: AbortSignal.timeout(3000) });
          return { name: `${name} :${port}`, state: r.status < 500 ? 'ok' : 'warn' }; }
    catch { return { name: `${name} :${port}`, state: 'fail' }; }
  }));
  await post('services', { items: services });

  await step('Script Task: read disk and repo');
  const diskPct = +execFileSync('df', ['--output=pcent', '/']).toString().split('\n')[1].trim().replace('%', '');
  await post('disk', { value: diskPct, unit: '%' });
  const days = execFileSync('git', ['-C', os.homedir() + '/ants', 'log', '--since=30.days', '--format=%ad', '--date=short']).toString().trim().split('\n');
  for (let i = 29; i >= 0; i--) {
    const day = new Date(Date.now() - i * 864e5).toISOString().slice(0, 10);
    await post('commits', { point: { x: day, y: days.filter(d => d === day).length, series: 'commits' } }); // same day replaces
  }
  const issues = JSON.parse(execFileSync('gh', ['issue', 'list', '-R', 'oyvij/ants', '--state', 'open', '--limit', '500', '--json', 'number']).toString()).length;
  await post('issues', { value: issues });

  await step('Agent Task: claude reads it all');
  const prompt = `You feed a dashboard. The reader builds Ants, a runner for BPMN processes whose tasks are AI agents, and runs a home lab.
Today's Hacker News top 10:\n${stories.map((s, i) => `${i}. ${s.title}`).join('\n')}
Home lab: ${services.map(s => s.name + '=' + s.state).join(', ')}. Disk used: ${diskPct}%.
Ants repo: ${days.length} commits in 30 days, ${issues} open issues.
Reply with JSON only, no fences: {"picks":[{"i":<story index>,"why":"<one short line on why this reader should care>"}] (pick 3), "read":"<two short sentences: the one thing to notice today across all of it>"}`;
  const out = await new Promise((res, rej) => execFile('claude', ['-p', prompt], { timeout: 240000 }, (e, so) => e ? rej(e) : res(so)));
  const a = JSON.parse(out.slice(out.indexOf('{'), out.lastIndexOf('}') + 1));
  await post('picks', { items: a.picks.map(p => ({ title: stories[p.i].title, url: stories[p.i].url || `https://news.ycombinator.com/item?id=${stories[p.i].id}`, note: p.why })) });
  await post('read', { text: a.read });
  await step('Done');
})().catch(e => step('Failed: ' + e.message, 'error'));
