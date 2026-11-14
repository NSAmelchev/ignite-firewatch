// The section of the plugins: Ignite together with an extension of another repository, the master of both against
// their last release. app.js loads this module only on a stand whose configuration names plugins.
import {
  api, esc, fmt, badge, jobLink, toast, guarded, views, refresh, confirmLaunch, deltaLine, jobTitle, jobRefsShort,
  scenarioDetail, unfoldRow,
} from './app.js';

const TABS = [['history', 'History'], ['compare', 'Compare'], ['scenarios', 'Scenarios'], ['settings', 'Settings']];
const query = () => new URLSearchParams(location.hash.split('?')[1] || '');
const pluginUrl = (name, extra = {}) => `#/plugin?${new URLSearchParams({ name, ...extra })}`;
const github = (url) => { const m = /github\.com[/:]([^/]+\/[^/.]+)/.exec(url || ''); return m ? `https://github.com/${m[1]}` : null; };
const repoName = (url) => (url || '').replace(/\.git$/, '').replace(/^.*github\.com[/:]/, '');
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
      : esc(p.pairsError || 'the pairs are unknown')} <a href="${pluginUrl(p.name, { tab: 'settings' })}">Settings</a></div>`;
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
// the row of a scenario on the overview: where master stands against the release now
function scenarioRow(p, r) {
  const last = r.last;
  return `<tr><td><b>${esc(r.scenario)}</b>${activeChip(r.active)}${failedLine(r.failed)}</td>
    <td>${last ? `${deltaLine(last)} ${jobLink(last)}` : '<span class="muted">not measured yet</span>'}</td>
    <td>${last ? `${fmt.ago(last.finishedAt)}<div class="muted small-text">${jobRefsShort(last)}</div>` : ''}</td>
    <td>${spark(r.history)}</td>
    <td class="row-action"><a class="small-link" href="${pluginUrl(p.name, { tab: 'history' })}">history</a>
      ${last ? `<a class="small-link" href="${pluginUrl(p.name, { tab: 'compare', job: last.id })}">compare</a>` : ''}</td></tr>`;
}
function card(p) {
  return `<div class="card plugin-card">
    <div class="plugin-head"><h2><a href="${pluginUrl(p.name)}">${esc(p.title)}</a></h2>
      <span class="muted">${esc(repoName(p.repo))} · ${esc(p.module)}</span>
      <span class="plugin-actions">${p.pairs ? `<button class="small" data-run="${esc(p.name)}">Run now</button>` : ''}
        <a class="small-link" href="${pluginUrl(p.name, { tab: 'settings' })}">Settings</a></span></div>
    ${pairsBlock(p)}
    <table class="plugin-rows"><thead><tr><th>Scenario</th><th>Master vs release</th><th>Measured</th><th title="Δ ops/s against the release, run by run">History</th><th></th></tr></thead>
    <tbody>${p.rows.map((r) => scenarioRow(p, r)).join('')}</tbody></table>
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
  live: true,
  async render() {
    const list = await api.get('/api/plugins');
    this.pages = await Promise.all(list.plugins.map((p) => api.get(`/api/plugins/${encodeURIComponent(p.name)}`)));
    return `<h1>Plugins <span class="muted">Ignite together with an extension of another repository: master of both against their last release</span></h1>
      ${list.errors.length ? `<div class="card error">Not loaded: ${list.errors.map(esc).join('<br>')}</div>` : ''}
      ${this.pages.map(card).join('') || '<div class="card muted">No plugin is on: the configuration names none.</div>'}
      <div class="muted small-text">Both sides of a pair move, so a result says how the two masters do together, not which commit moved them: nothing is bisected, Compare on the page of a plugin narrows it down.</div>`;
  },
  bind(root) {
    bindRun(root, this.pages);
  },
};

// History: every run against the release, newest first, with how far each side moved since the run before
function historyTab(p) {
  const rows = p.rows.map((r) => `<h2>${esc(r.scenario)} ${activeChip(r.active)}</h2>${failedLine(r.failed)}
    ${r.history.length ? `<table><thead><tr><th>Run</th><th>When</th><th>Master pair</th><th>Ignite moved</th><th>${esc(p.title)} moved</th><th>Master vs release</th><th></th></tr></thead><tbody>
    ${r.history.map((h, i) => {
    const j = h.job, prev = r.history[i + 1] && r.history[i + 1].job;
    return `<tr><td>${jobLink(j)}</td><td>${esc(fmt.date(j.finishedAt))}</td>
      <td>${commitLink(p.igniteRepo, j.shaB)} + ${commitLink(p.repo, j.extShaB)}</td>
      <td>${prev ? moved(p.igniteRepo, prev.shaB, j.shaB, h.igniteMoved) : ''}</td><td>${prev ? moved(p.repo, prev.extShaB, j.extShaB, h.extMoved) : ''}</td>
      <td>${deltaLine(j)}</td>
      <td class="row-action">${prev ? `<a class="small-link" href="${pluginUrl(p.name, { tab: 'compare', from: prev.id, to: j.id })}" title="the master pair of the run before against this one, one side at a time">compare with the run before</a>` : ''}</td></tr>`;
  }).join('')}</tbody></table>` : '<div class="muted">No run against the release yet.</div>'}`).join('');
  const compares = p.compares.length ? `<h2>Compares <span class="muted">pairs queued from Compare</span></h2>
    <table><tbody>${p.compares.map((j) => `<tr><td>${jobLink(j)}</td><td>${jobTitle(j)}</td><td>${jobRefsShort(j)}</td><td>${badge(j.status)} ${j.status === 'done' ? deltaLine(j) : ''}</td></tr>`).join('')}</tbody></table>` : '';
  return rows + compares;
}

// Compare: two pairs, refs of both repositories; split goes one side at a time, Ignite first
function compareTab(p, prefill) {
  const pf = prefill || {};
  const field = (side, k, label, value, ph) => `<label>${label}<input name="${side}.${k}" value="${esc(value || '')}" placeholder="${esc(ph)}" autocomplete="off" spellcheck="false"></label>`;
  const rel = p.pairs ? p.pairs.release : {}, mas = p.pairs ? p.pairs.master : {};
  return `<form class="card plugin-compare">
    <div class="pair-form"><div><h3>A <span class="muted">baseline</span></h3>
      ${field('a', 'ignite', 'Ignite', pf.a ? pf.a.ignite : rel.igniteRef, 'a tag, a branch or a sha of Ignite')}
      ${field('a', 'ext', esc(p.title), pf.a ? pf.a.ext : rel.extRef, `a tag, a branch or a sha of ${repoName(p.repo)}`)}</div>
    <div><h3>B <span class="muted">target</span></h3>
      ${field('b', 'ignite', 'Ignite', pf.b ? pf.b.ignite : mas.igniteRef, 'a tag, a branch or a sha of Ignite')}
      ${field('b', 'ext', esc(p.title), pf.b ? pf.b.ext : mas.extRef, `a tag, a branch or a sha of ${repoName(p.repo)}`)}</div></div>
    <label class="check"><input type="checkbox" name="split" ${pf.split === false ? '' : 'checked'}> One side at a time: Ignite first (the ${esc(p.title)} of A on both sides), then ${esc(p.title)} (the Ignite of B on both sides)</label>
    <div class="actions"><button class="primary">Queue</button> <span class="muted small-text">An ABBA per scenario${pf.split === false ? '' : ' and step'}; nothing is bisected.</span></div>
  </form>`;
}

// Settings: the release the master pair is compared with, how often, and the repository
function settingsTab(p) {
  const s = p.settings;
  return `<form class="card plugin-settings">
    <label for="pl-release">Release of ${esc(p.title)}</label>
    <input id="pl-release" name="release" value="${esc(s.release)}" placeholder="not set: the plugin runs only by hand" autocomplete="off" spellcheck="false">
    <div class="hint">What master is compared with: a tag or a branch of ${esc(repoName(p.repo))}.</div>
    <label for="pl-ignite">Ignite of the release</label>
    <input id="pl-ignite" name="ignite" value="${esc(s.ignite)}" placeholder="from the pom of the release${p.pairs && !s.ignite ? `: ${esc(p.pairs.release.igniteRef)}` : ''}" autocomplete="off" spellcheck="false">
    <div class="hint">Empty: the version of Ignite the pom of the release builds against. A tag, a branch or a sha of Ignite otherwise.</div>
    <div class="mode-row"><label class="mode-opt"><input type="radio" name="mode" value="off" ${s.mode === 'keep' ? '' : 'checked'}><span>Only by hand</span></label>
      <label class="mode-opt"><input type="radio" name="mode" value="keep" ${s.mode === 'keep' ? 'checked' : ''}><span>Keep fresh</span></label>
      <label class="keep-days">when master moved, every <input name="keepDays" type="number" min="1" value="${esc(s.keepDays)}"> days</label></div>
    <label>Repository <span class="muted">from the ${p.repoFromConfig ? 'configuration' : 'jar'}</span></label>
    <div class="mono repo-fixed">${esc(p.repo)} · ${esc(p.branch)}</div>
    <div class="hint">Another repository or branch (a fork, a mirror of the same extension): <code>plugin.${esc(p.name)}.repo</code> and <code>plugin.${esc(p.name)}.branch</code> in <code>config/ignite-firewatch.properties</code>; a restart applies them.</div>
    <div class="actions"><button class="primary">Save</button> <span class="muted small-text">jar ${esc(p.jar)} · module ${esc(p.module)}</span></div>
  </form>`;
}

views.plugin = {
  poll: 15000,
  live() { return (this.tab || 'history') === 'history'; }, // a form being filled is not re-rendered under the hands
  async render() {
    const q = query();
    this.name = q.get('name');
    this.tab = TABS.some(([k]) => k === q.get('tab')) ? q.get('tab') : 'history';
    const p = this.p = await api.get(`/api/plugins/${encodeURIComponent(this.name)}`);
    let prefill = null;
    if (this.tab === 'compare' && (q.get('job') || q.get('to'))) {
      // a run to look into: its master pair against its release pair, or the master pair of the run before against its own
      const to = await api.get(`/api/jobs/${q.get('to') || q.get('job')}`).catch(() => null);
      const from = q.get('from') ? await api.get(`/api/jobs/${q.get('from')}`).catch(() => null) : null;
      if (to) prefill = from ? { a: { ignite: from.shaB, ext: from.extShaB }, b: { ignite: to.shaB, ext: to.extShaB } }
        : { a: { ignite: to.shaA, ext: to.extShaA }, b: { ignite: to.shaB, ext: to.extShaB } };
    }
    this.open = this.open || new Set();
    const body = this.tab === 'history' ? historyTab(p) : this.tab === 'compare' ? compareTab(p, prefill)
      : this.tab === 'settings' ? settingsTab(p)
      : `<table class="scenario-list"><tbody>${(await Promise.all(p.scenarios.map((s) => api.get(`/api/plugins/${encodeURIComponent(p.name)}/scenarios/${encodeURIComponent(s.name)}`)
        .then((d) => ({ s, d })).catch((e) => ({ s, d: { error: e.message } }))))).map(({ s, d }) => {
        const open = this.open.has(s.name);
        return d.error ? `<tr><td colspan="2" class="error">${esc(s.name)}: ${esc(d.error)}</td></tr>`
          : `<tr class="click-row" data-scenario="${esc(s.name)}"><td class="sc-name"><span class="chev">${open ? '▾' : '▸'}</span> <b>${esc(d.title || d.name)}</b></td><td class="muted">${esc(d.description || '')}</td></tr>
            <tr class="row-detail" ${open ? '' : 'hidden'}><td colspan="2">${scenarioDetail(d)}</td></tr>`;
      }).join('')}</tbody></table>`;
    return `<h1><a href="#/plugins">Plugins</a> / ${esc(p.title)} <span class="muted">${esc(p.description || '')}</span></h1>
      <div class="plugin-head"><span class="muted">${esc(repoName(p.repo))} · ${esc(p.module)}</span>
        <span class="plugin-actions">${p.pairs ? `<button class="small" data-run="${esc(p.name)}">Run now</button>` : ''}</span></div>
      ${pairsBlock(p)}
      <div class="seg plugin-tabs" role="tablist">${TABS.map(([k, n]) => `<a role="tab" class="seg-link ${k === this.tab ? 'on' : ''}" href="${pluginUrl(p.name, k === 'history' ? {} : { tab: k })}">${n}</a>`).join('')}</div>
      ${body}`;
  },
  bind(root) {
    const p = this.p;
    bindRun(root, [p]);
    root.addEventListener('click', (e) => {
      const r = e.target.closest('tr[data-scenario]');
      if (!r || e.target.closest('a')) return;
      const n = r.dataset.scenario, open = !this.open.has(n);
      if (open) this.open.add(n); else this.open.delete(n);
      unfoldRow(r, open);
    });
    const cmp = root.querySelector('form.plugin-compare');
    if (cmp) cmp.addEventListener('submit', (e) => guarded(async () => {
      e.preventDefault();
      const f = new FormData(cmp), pair = (s) => ({ ignite: f.get(`${s}.ignite`).trim(), ext: f.get(`${s}.ext`).trim() });
      const a = pair('a'), b = pair('b'), split = !!f.get('split');
      const v = await confirmLaunch({ title: `Compare pairs of ${p.title}`, ok: 'Queue',
        what: `B ${esc(b.ignite)} + ${esc(b.ext)} vs A ${esc(a.ignite)} + ${esc(a.ext)}${split ? '<div class="muted small-text">one side at a time: Ignite first, then the extension</div>' : ''}`,
        choose: p.scenarios.map((s) => ({ name: s.name, checked: true })), type: 'abba' });
      if (!v) return;
      const jobs = await api.post(`/api/plugins/${encodeURIComponent(p.name)}/compare`, { a, b, split, scenarios: v.scenarios, priority: v.priority });
      toast(`${jobs.length} job${jobs.length === 1 ? '' : 's'} queued`);
      location.hash = pluginUrl(p.name);
    }));
    const st = root.querySelector('form.plugin-settings');
    if (st) st.addEventListener('submit', (e) => guarded(async () => {
      e.preventDefault();
      const f = new FormData(st), key = (k) => `plugin.${p.name}.${k}`;
      await api.put('/api/settings', {
        [key('release')]: f.get('release').trim(), [key('ignite')]: f.get('ignite').trim(), [key('mode')]: f.get('mode'),
        [key('keepDays')]: f.get('keepDays'),
      });
      toast('saved');
      refresh(true);
    }));
  },
};
