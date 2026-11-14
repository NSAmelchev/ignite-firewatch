// The section of the plugins: Ignite together with an extension of another repository, the master of both against
// their last release. app.js loads this module only on a stand whose configuration names plugins.
import {
  api, esc, fmt, badge, jobLink, toast, guarded, views, refresh, confirmLaunch, deltaLine, deltaClass, jobRefsShort,
  scenarioDetail, unfoldRow, modal,
} from './app.js';

const query = () => new URLSearchParams(location.hash.split('?')[1] || '');
const pluginUrl = (name, extra = {}) => `#/plugin?${new URLSearchParams({ name, ...extra })}`;
const github = (url) => { const m = /github\.com[/:]([^/]+\/[^/.]+)/.exec(url || ''); return m ? `https://github.com/${m[1]}` : null; };
const repoName = (url) => (url || '').replace(/\.git$/, '').replace(/^.*github\.com[/:]/, '');
const short = (sha) => (sha || '').slice(0, 10);
const commitLink = (repo, sha) => {
  const g = github(repo);
  return g ? `<a class="mono" href="${g}/commit/${esc(sha)}" target="_blank" title="${esc(sha)}">${fmt.sha(sha)}</a>`
    : `<span class="mono" title="${esc(sha)}">${fmt.sha(sha)}</span>`;
};
// how far a side moved from one run to the next, with the diff on GitHub when there is one
const moved = (repo, from, to, n) => {
  if (n == null) return '<span class="muted">?</span>';
  if (!n) return '<span class="muted">no change</span>';
  const g = github(repo), t = `${n} commit${n === 1 ? '' : 's'}`;
  return g ? `<a href="${g}/compare/${esc(from)}...${esc(to)}" target="_blank">${t}</a>` : t;
};
// a ref with its commit; the ref alone when it is the sha
const refSha = (repo, ref, sha) => `${ref && !sha.startsWith(ref) ? `${esc(ref)} ` : ''}${commitLink(repo, sha)}`;
const pairHtml = (p, pair) => `Ignite ${refSha(p.igniteRepo, pair.igniteRef, pair.igniteSha)}
  <span class="muted">+</span> ${esc(p.title)} ${refSha(p.repo, pair.extRef, pair.extSha)}`;
// the two pairs, or why there are none yet
function pairsBlock(p) {
  if (!p.pairs) {
    const unset = !p.settings.release;
    return `<div class="card notice small-text">${unset ? 'The release of the extension is not set: the plugin does not run by itself.'
      : esc(p.pairsError || 'the pairs are unknown')} <a href="${pluginUrl(p.name, { tab: 'settings' })}" data-settings>${unset ? 'Set the release' : 'Settings'}</a></div>`;
  }
  return `<dl class="kv plugin-pairs"><dt>Release</dt><dd>${pairHtml(p, p.pairs.release)}</dd>
    <dt>Master</dt><dd>${pairHtml(p, p.pairs.master)}</dd></dl>
    <div class="muted small-text">${p.mirror.lastFetchAt ? `${esc(repoName(p.repo))} fetched ${fmt.ago(p.mirror.lastFetchAt)}` : 'not fetched yet'}${p.mirror.lastFetchError ? ` · <span class="error">${esc(p.mirror.lastFetchError)}</span>` : ''}
      · runs ${p.settings.mode === 'keep' ? `when master moved, at most every ${p.settings.keepDays === 1 ? 'day' : `${p.settings.keepDays} days`}` : 'only by hand'}</div>`;
}
// Δ throughput against the release over the runs, oldest on the left: a jump between two runs is where to look
function spark(history) {
  const pts = history.slice().reverse().map((h) => {
    const m = h.job.comparison && h.job.comparison.metrics && h.job.comparison.metrics.throughput;
    return m && m.deltaPct != null ? { d: m.deltaPct, t: m.thresholdPct, id: h.job.id, at: h.job.finishedAt } : null;
  }).filter(Boolean);
  if (pts.length < 2) return pts.length ? '' : '<span class="muted">–</span>';
  const w = 120, h = 24, lim = Math.max(2, ...pts.map((p) => Math.abs(p.d)));
  const x = (i) => 2 + (i * (w - 4)) / (pts.length - 1), y = (d) => h / 2 - (d / lim) * (h / 2 - 2);
  return `<svg class="plugin-spark" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="img" aria-label="Δ ops/s against the release, run by run">
    <line x1="0" x2="${w}" y1="${h / 2}" y2="${h / 2}" class="spark-zero"/>
    <polyline points="${pts.map((p, i) => `${x(i).toFixed(1)},${y(p.d).toFixed(1)}`).join(' ')}" class="spark-line"/>
    ${pts.map((p, i) => `<circle cx="${x(i).toFixed(1)}" cy="${y(p.d).toFixed(1)}" r="2.2" class="${Math.abs(p.d) > p.t ? (p.d < 0 ? 'spark-bad' : 'spark-good') : 'spark-dot'}"><title>${fmt.pct(p.d)} · job #${p.id} · ${esc(fmt.date(p.at))}</title></circle>`).join('')}
  </svg>`;
}
// a pair that does not build or run since the last result
const failedLine = (f) => (!f ? '' : `<div class="error small-text">${/^build of /.test(f.error || '') ? 'incompatible: ' : 'failed: '}${esc((f.error || '').slice(0, 160))} ${jobLink(f)}</div>`);
const activeChip = (list) => list.map((j) => ` <a href="#/jobs/${j.id}">${badge(j.status)}</a>`).join('');
// the pairs of a run for Compare: its release pair against its master pair, or the master pair of `from` against its own
const pairsOf = (j, from) => ({
  a: from ? { ignite: short(from.shaB), ext: short(from.extShaB) } : { ignite: short(j.shaA), ext: short(j.extShaA) },
  b: { ignite: short(j.shaB), ext: short(j.extShaB) },
});

// The row of a scenario: where master stands against the release now; `open` unfolds its history and description
function scenarioRow(p, r, open) {
  const last = r.last;
  return `<tr${open == null ? '' : ` class="click-row" data-scenario="${esc(r.scenario)}"`}>
    <td>${open == null ? '' : `<span class="chev">${open ? '▾' : '▸'}</span> `}<b>${esc(r.scenario)}</b>${activeChip(r.active)}${failedLine(r.failed)}</td>
    <td>${last ? `${deltaLine(last)} ${jobLink(last)}` : '<span class="muted">not measured yet</span>'}</td>
    <td>${last ? `${fmt.ago(last.finishedAt)}<div class="muted small-text">${jobRefsShort(last)}</div>` : ''}</td>
    <td>${spark(r.history)}</td>
    <td class="row-action">${open == null ? `<a class="small-link" href="${pluginUrl(p.name)}">history</a>`
    : last ? `<a class="small-link" href="#plugin-compare" data-compare="${last.id}" title="its release pair against its master pair, one side at a time">compare</a>` : ''}</td></tr>`;
}
// History of a scenario: every run against the release, newest first, with how far each side moved since the run before
function historyTable(p, r) {
  if (!r.history.length) return '<div class="muted">No run against the release yet.</div>';
  return `<table><thead><tr><th>Run</th><th>When</th><th>Master pair</th><th>Ignite moved</th><th>${esc(p.title)} moved</th><th>Master vs release</th><th></th></tr></thead><tbody>
    ${r.history.map((h, i) => {
    const j = h.job, prev = r.history[i + 1] && r.history[i + 1].job;
    return `<tr><td>${jobLink(j)}</td><td>${esc(fmt.date(j.finishedAt))}</td>
      <td>${commitLink(p.igniteRepo, j.shaB)} + ${commitLink(p.repo, j.extShaB)}</td>
      <td>${prev ? moved(p.igniteRepo, prev.shaB, j.shaB, h.igniteMoved) : ''}</td><td>${prev ? moved(p.repo, prev.extShaB, j.extShaB, h.extMoved) : ''}</td>
      <td>${deltaLine(j)}</td>
      <td class="row-action">${prev ? `<a class="small-link" href="#plugin-compare" data-compare="${j.id}" data-from="${prev.id}" title="the master pair of the run before against this one, one side at a time">compare with the run before</a>` : ''}</td></tr>`;
  }).join('')}</tbody></table>`;
}
// the card of a plugin on the list of several: its pairs and where master stands
function card(p) {
  return `<div class="card plugin-card">
    <div class="plugin-head"><h2><a href="${pluginUrl(p.name)}">${esc(p.title)}</a></h2>
      <span class="muted">${esc(repoName(p.repo))} · ${esc(p.module)}</span>
      <span class="plugin-actions">${p.pairs ? `<button class="small" data-run="${esc(p.name)}">Run now</button>` : ''}</span></div>
    ${pairsBlock(p)}
    <table class="plugin-rows"><thead><tr><th>Scenario</th><th>Master vs release</th><th>Measured</th><th title="Δ ops/s against the release, run by run">History</th><th></th></tr></thead>
    <tbody>${p.rows.map((r) => scenarioRow(p, r, null)).join('')}</tbody></table>
  </div>`;
}
// Run now: the release pair against the master pair, for the chosen scenarios
async function runNow(p) {
  const v = await confirmLaunch({ title: `Run ${p.title} now`, what: `master pair vs release pair<div class="muted small-text">${pairHtml(p, p.pairs.master)}<br>vs ${pairHtml(p, p.pairs.release)}</div>`,
    choose: p.scenarios.map((s) => ({ name: s.name, checked: true })), type: 'abba' });
  if (!v) return;
  const jobs = await api.post(`/api/plugins/${encodeURIComponent(p.name)}/run`, { scenarios: v.scenarios, priority: v.priority });
  toast(`${jobs.length} job${jobs.length === 1 ? '' : 's'} queued`);
  refresh();
}
const bindRun = (root, list) => root.addEventListener('click', (e) => {
  const b = e.target.closest('button[data-run]');
  if (b) guarded(() => runNow(list.find((p) => p.name === b.dataset.run)));
});

views.plugins = {
  poll: 15000,
  live() { return this.single ? views.plugin.live() : true; },
  async render() {
    const list = await api.get('/api/plugins');
    this.single = list.plugins.length === 1 && !list.errors.length; // one plugin: its page is the section
    if (this.single) return views.plugin.render({ name: list.plugins[0].name });
    this.pages = await Promise.all(list.plugins.map((p) => api.get(`/api/plugins/${encodeURIComponent(p.name)}`)));
    return `<h1>Plugins <span class="muted">Ignite together with an extension of another repository: master of both against their last release</span></h1>
      ${list.errors.length ? `<div class="card error">Not loaded: ${list.errors.map(esc).join('<br>')}</div>` : ''}
      ${this.pages.map(card).join('') || '<div class="card muted">No plugin is on: the configuration names none.</div>'}
      <div class="muted small-text">Both sides of a pair move, so a result says how the two masters do together, not which commit moved them: nothing is bisected, Compare on the page of a plugin narrows it down.</div>`;
  },
  bind(root) {
    if (this.single) views.plugin.bind(root);
    else bindRun(root, this.pages);
  },
};

// The Δ ops/s of a step of Compare, or where the job is
function stepResult(j) {
  if (j.status !== 'done') return `${badge(j.status)} ${jobLink(j)}`;
  const m = j.comparison && j.comparison.metrics && j.comparison.metrics.throughput;
  return m && m.deltaPct != null ? `<span class="dlt ${deltaClass('throughput', m.deltaPct, m.thresholdPct, m.verdict)}">${fmt.pct(m.deltaPct)}</span> ${jobLink(j)}`
    : jobLink(j);
}
// one request of Compare: its pairs, per scenario the Δ of each side, so the side that moved stands out
function comparisonItem(p, c) {
  const steps = c.scenarios.flatMap((s) => [s.ignite, s.ext, s.both]).filter(Boolean);
  const done = steps.filter((j) => j.status === 'done').length;
  const verdicts = [['regression', 'regression'], ['improvement', 'improvement'], ['no-change', 'no change']]
    .map(([v, w]) => [steps.filter((j) => j.verdict === v).length, v, w]).filter(([n]) => n)
    .map(([n, v, w]) => `<span class="badge ${v}">${n} ${w}</span>`).join(' ');
  const side = (name, j) => (j ? `${name} ${stepResult(j)}` : `<span class="muted">${name} did not move</span>`);
  return `<div class="cmp-item"><div><b>${esc(c.a)} → ${esc(c.b)}</b></div>
    <div class="cmp-meta">${c.status === 'done' ? verdicts || badge('done') : `${badge(c.status)} ${done} of ${steps.length}`} <span class="muted">${fmt.ago(c.createdAt)}</span></div>
    ${c.scenarios.map((s) => `<div class="small-text cmp-steps">${esc(s.scenario)}: ${s.both ? `both ${stepResult(s.both)}`
    : `${side('Ignite', s.ignite)} · ${side(esc(p.title), s.ext)}`}</div>`).join('')}</div>`;
}
// Compare: two pairs, refs of both repositories; it goes one side at a time, Ignite first
function compareForm(p, form, picked) {
  const field = (side, k, label, ph) => `<label>${label}<input name="${side}.${k}" value="${esc(form[side][k] || '')}" placeholder="${esc(ph)}" autocomplete="off" spellcheck="false" required></label>`;
  const ig = 'a tag, a branch or a sha of Ignite', ex = `a tag, a branch or a sha of ${repoName(p.repo)}`;
  return `<form class="plugin-compare">
    <div class="pair-form"><div><h3>A <span class="muted">baseline</span></h3>${field('a', 'ignite', 'Ignite', ig)}${field('a', 'ext', esc(p.title), ex)}</div>
      <div><h3>B <span class="muted">target</span></h3>${field('b', 'ignite', 'Ignite', ig)}${field('b', 'ext', esc(p.title), ex)}</div></div>
    <label>Scenarios <span data-picked>${picked.size} chosen</span></label>
    <div class="sc-groups"><div>${p.scenarios.map((s) => `<label class="sc-item" title="${esc(s.description || '')}"><input type="checkbox" data-sc="${esc(s.name)}" ${picked.has(s.name) ? 'checked' : ''}> ${esc(s.name)}</label>`).join('')}</div></div>
    <div class="muted small-text">Step 1: the Ignite of A → of B, the ${esc(p.title)} of A on both sides. Step 2: the ${esc(p.title)} of A → of B, the Ignite of B on both sides. A step that changes nothing is left out; nothing is bisected.</div>
    <div class="actions"><button class="primary">Queue ABBA: B vs A</button></div>
  </form>`;
}
// Settings: the release the master pair is compared with, how often; the repository is the configuration's
function settingsBody(p) {
  const s = p.settings;
  return `<form class="plugin-settings" onsubmit="return false">
    <label for="pl-release">Release of ${esc(p.title)}</label>
    <input id="pl-release" name="release" value="${esc(s.release)}" list="pl-tags" placeholder="not set: the plugin runs only by hand" autocomplete="off" spellcheck="false">
    <datalist id="pl-tags">${(p.tags || []).map((t) => `<option value="${esc(t)}">`).join('')}</datalist>
    <div class="hint">What master is compared with: a tag or a branch of ${esc(repoName(p.repo))}.${p.tags && p.tags.length ? ` Recent: ${p.tags.slice(0, 3).map(esc).join(', ')}.` : ''}</div>
    <label for="pl-ignite">Ignite of the release</label>
    <input id="pl-ignite" name="ignite" value="${esc(s.ignite)}" placeholder="from the pom of the release${p.pairs && !s.ignite ? `: ${esc(p.pairs.release.igniteRef)}` : ''}" autocomplete="off" spellcheck="false">
    <div class="hint">Empty: the version of Ignite the pom of the release builds against. A tag, a branch or a sha of Ignite otherwise.</div>
    <div class="mode-row"><label class="mode-opt"><input type="radio" name="mode" value="off" ${s.mode === 'keep' ? '' : 'checked'}><span>Only by hand</span></label>
      <label class="mode-opt"><input type="radio" name="mode" value="keep" ${s.mode === 'keep' ? 'checked' : ''}><span>Keep fresh</span></label>
      <label class="keep-days">when master moved, every <input name="keepDays" type="number" min="1" value="${esc(s.keepDays)}"> days</label></div>
    <dl class="kv plugin-source"><dt>Repository</dt><dd class="mono">${esc(p.repo)} · ${esc(p.branch)}</dd>
      <dt>Module</dt><dd class="mono">${esc(p.module)}</dd><dt>Jar</dt><dd class="mono">${esc(p.jar)}</dd></dl>
    <div class="hint">The repository comes from the ${p.repoFromConfig ? 'configuration' : 'jar'}: <code>plugin.${esc(p.name)}.repo</code> and <code>plugin.${esc(p.name)}.branch</code> in <code>config/ignite-firewatch.properties</code> set another (a fork, a mirror), a restart applies them.</div>
  </form>`;
}
async function openSettings(p) {
  const v = await modal(`Settings of ${esc(p.title)}`, settingsBody(p), 'Save', {
    value: (w) => Object.fromEntries(new FormData(w.querySelector('form'))),
  });
  if (!v) return;
  const key = (k) => `plugin.${p.name}.${k}`;
  await api.put('/api/settings', {
    [key('release')]: (v.release || '').trim(), [key('ignite')]: (v.ignite || '').trim(), [key('mode')]: v.mode, [key('keepDays')]: v.keepDays,
  });
  toast('saved');
  refresh(true);
}

views.plugin = {
  poll: 15000,
  live() { return this.active; }, // while its jobs run; what the form holds stays across re-renders
  async render(params) {
    const q = query(), name = (params && params.name) || q.get('name');
    if (name !== this.name) { this.name = name; this.form = null; this.picked = null; this.open = new Set(); this.described = new Map(); }
    const [list, p] = await Promise.all([api.get('/api/plugins'), api.get(`/api/plugins/${encodeURIComponent(name)}`)]);
    this.p = p;
    if (!this.form) {
      const rel = p.pairs ? p.pairs.release : {}, mas = p.pairs ? p.pairs.master : {};
      this.form = { a: { ignite: rel.igniteRef || '', ext: rel.extRef || '' }, b: { ignite: mas.igniteRef || '', ext: mas.extRef || '' } };
    }
    if (!this.picked) this.picked = new Set(p.scenarios.map((s) => s.name));
    if (q.get('job') && q.get('job') !== this.prefilled) { // an older link to Compare a run
      this.prefilled = q.get('job');
      const j = await api.get(`/api/jobs/${q.get('job')}`).catch(() => null);
      if (j) this.form = pairsOf(j);
    }
    await Promise.all([...this.open].filter((n) => !this.described.has(n)).map((n) => api.get(`/api/plugins/${encodeURIComponent(name)}/scenarios/${encodeURIComponent(n)}`)
      .then((d) => this.described.set(n, d)).catch((e) => this.described.set(n, { error: e.message }))));
    this.active = p.rows.some((r) => r.active.length) || p.comparisons.some((c) => c.status === 'queued' || c.status === 'running');
    const about = (n) => { const d = this.described.get(n); return !d ? '' : d.error ? `<div class="error">${esc(d.error)}</div>` : scenarioDetail(d); };
    const rows = p.rows.map((r) => {
      const open = this.open.has(r.scenario);
      return `${scenarioRow(p, r, open)}<tr class="row-detail" ${open ? '' : 'hidden'}><td colspan="5">${historyTable(p, r)}${about(r.scenario)}</td></tr>`;
    }).join('');
    return `<h1>${list.plugins.length > 1 ? '<a href="#/plugins">Plugins</a> / ' : ''}${esc(p.title)} <span class="muted">${esc(p.description || '')}</span></h1>
      <div class="plugin-head"><span class="muted">${esc(repoName(p.repo))} · ${esc(p.module)}</span>
        <span class="plugin-actions">${p.pairs ? `<button class="small primary" data-run="${esc(p.name)}">Run now</button>` : ''}<button class="small" data-settings>Settings</button></span></div>
      ${pairsBlock(p)}
      <div class="card plugin-card"><table class="plugin-rows"><thead><tr><th>Scenario</th><th>Master vs release</th><th>Measured</th><th title="Δ ops/s against the release, run by run">History</th><th></th></tr></thead>
        <tbody>${rows}</tbody></table></div>
      <h2 id="plugin-compare">Compare <span class="muted">two pairs, one side at a time: Ignite first, then ${esc(p.title)}</span></h2>
      <div class="row">
        <div class="card" style="flex:3 1 520px">${compareForm(p, this.form, this.picked)}</div>
        <div class="card" style="flex:2 1 340px"><h2 style="margin-top:0">Comparisons</h2>
          ${p.comparisons.map((c) => comparisonItem(p, c)).join('') || '<div class="muted">no comparisons yet</div>'}</div>
      </div>`;
  },
  bind(root) {
    const p = this.p;
    bindRun(root, [p]);
    root.addEventListener('click', (e) => {
      if (e.target.closest('[data-settings]')) { e.preventDefault(); guarded(() => openSettings(p)); return; }
      const c = e.target.closest('[data-compare]');
      if (c) {
        e.preventDefault();
        guarded(async () => {
          const [j, from] = await Promise.all([api.get(`/api/jobs/${c.dataset.compare}`), c.dataset.from ? api.get(`/api/jobs/${c.dataset.from}`) : null]);
          this.form = pairsOf(j, from);
          this.picked = new Set([j.scenario]);
          await refresh(true);
          document.getElementById('plugin-compare').scrollIntoView({ behavior: 'smooth' });
        });
        return;
      }
      const r = e.target.closest('tr[data-scenario]');
      if (!r || e.target.closest('a, button')) return;
      const n = r.dataset.scenario, open = !this.open.has(n);
      if (open) this.open.add(n); else this.open.delete(n);
      if (open && !this.described.has(n)) refresh(true); // its description comes with the next render
      else unfoldRow(r, open);
    });
    const cmp = root.querySelector('form.plugin-compare');
    const read = () => { const f = new FormData(cmp), pair = (s) => ({ ignite: f.get(`${s}.ignite`).trim(), ext: f.get(`${s}.ext`).trim() }); return { a: pair('a'), b: pair('b') }; };
    cmp.addEventListener('input', () => { this.form = read(); });
    cmp.addEventListener('change', (e) => {
      const n = e.target.dataset.sc;
      if (!n) return;
      if (e.target.checked) this.picked.add(n); else this.picked.delete(n);
      cmp.querySelector('[data-picked]').textContent = `${this.picked.size} chosen`;
    });
    cmp.addEventListener('submit', (e) => guarded(async () => {
      e.preventDefault();
      const { a, b } = read(), names = p.scenarios.map((s) => s.name).filter((n) => this.picked.has(n));
      if (!names.length) { toast('choose at least one scenario', true); return; }
      const v = await confirmLaunch({ title: `Compare pairs of ${p.title}`, ok: 'Queue', scenarios: names, jobs: names.length * 2,
        what: `B ${esc(b.ignite)} + ${esc(b.ext)} vs A ${esc(a.ignite)} + ${esc(a.ext)}<div class="muted small-text">one side at a time: Ignite first, then ${esc(p.title)}</div>` });
      if (!v) return;
      const jobs = await api.post(`/api/plugins/${encodeURIComponent(p.name)}/compare`, { a, b, scenarios: names, priority: v.priority });
      toast(`${jobs.length} job${jobs.length === 1 ? '' : 's'} queued`);
      refresh(true);
    }));
    // an older link: Compare scrolls to it, Settings opens its dialog, once per link
    const tab = query().get('tab');
    if (tab && this.handled !== location.hash) {
      this.handled = location.hash;
      if (tab === 'compare') setTimeout(() => document.getElementById('plugin-compare').scrollIntoView(), 0);
      if (tab === 'settings') guarded(() => openSettings(p));
    }
  },
};
