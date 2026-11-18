// The section of the plugins: Ignite together with an extension of another repository, the master of both against
// their last release. app.js loads this module only on a stand whose configuration names plugins.
import {
  api, esc, fmt, badge, jobLink, toast, guarded, views, refresh, confirmLaunch, deltaClass, modal, plural, unfoldRow,
  METRIC_COLS, metricHead, calmLine, priorityField, priorityOf, frontPriority, scenarioPicker, bindScenarioPicker,
  familiesByWord, estimateRows, planShort,
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
  const g = github(repo), t = plural(n, 'commit');
  return g ? `<a href="${g}/compare/${esc(from)}...${esc(to)}" target="_blank">${t}</a>` : t;
};
// a ref with its commit; the ref alone when it is the sha
const refSha = (repo, ref, sha) => `${ref && !sha.startsWith(ref) ? `${esc(ref)} ` : ''}${commitLink(repo, sha)}`;
const pairHtml = (p, pair) => `Ignite ${refSha(p.igniteRepo, pair.igniteRef, pair.igniteSha)}
  <span class="muted">+</span> ${esc(p.title)} ${refSha(p.repo, pair.extRef, pair.extSha)}`;
const MOVED = new Set(['regression', 'improvement']);
const cols = () => METRIC_COLS.slice(0, 4); // the columns of the dashboard
const metricOf = (j, k) => j && j.comparison && j.comparison.metrics && j.comparison.metrics[k];
const chip = (j, k) => {
  const m = metricOf(j, k);
  return m && m.deltaPct != null ? `<span class="dlt ${deltaClass(k, m.deltaPct, m.thresholdPct, m.verdict)}">${fmt.pct(m.deltaPct)}</span>` : '';
};
const whenOf = (r) => (r.last ? fmt.ago(r.last.finishedAt) : '');
// a row with nothing to look at folds, as on the dashboard: measured, nothing queued or failed since, no Δ marked
const calm = (r) => r.last && !r.active.length && !r.failed && !MOVED.has(r.last.verdict)
  && cols().every(([k]) => { const m = metricOf(r.last, k); return !m || m.deltaPct == null || !deltaClass(k, m.deltaPct, m.thresholdPct, m.verdict); });
// what the dashboard of a plugin would look at: a Δ, a failure
const unsettled = (p) => p.rows.filter((r) => r.failed || (r.last && MOVED.has(r.last.verdict))).map((r) => r.scenario);

// The row of a scenario: its master pair against the release pair, as a row of the dashboard
function releaseRow(p, r, usual, showCalm, width) {
  const j = r.last, f = r.failed, sc = p.scenarios.find((s) => s.name === r.scenario) || {}, folds = calm(r);
  const cell = (k) => {
    const m = metricOf(j, k);
    if (!m || m.deltaPct == null) return '<td class="num muted">–<b class="att-mark"></b></td>';
    return `<td class="num"><a class="rel-delta" href="#/jobs/${j.id}" title="threshold ±${fmt.num(m.thresholdPct, 1)}%${m.verdict && m.verdict !== 'no-change' ? ` · ${esc(m.verdict)}` : ''} · open the job">${chip(j, k)}<b class="att-mark"></b></a></td>`;
  };
  const when = !j ? '<span class="muted">not measured</span>' : whenOf(r) === usual ? '' : `<span class="muted">${whenOf(r)}</span>`;
  const act = r.active.length ? r.active.map((x) => `${jobLink(x)} ${badge(x.status)}`).join(' ')
    : p.pairs ? `<button class="small" data-compare-sc="${esc(r.scenario)}" title="the release pair against the master pair, one side at a time">Compare</button> <button class="small" data-run-sc="${esc(r.scenario)}">Run</button>` : '';
  const verdict = f ? `<a href="#/jobs/${f.id}">${badge('failed')}</a>` : j ? `<a href="#/jobs/${j.id}">${badge(j.verdict)}</a>` : '';
  const why = f ? `<tr class="att-detail"><td colspan="${width}"><span class="error small-text">${/^build of /.test(f.error || '') ? 'incompatible' : 'failed'} ${fmt.ago(f.finishedAt || f.createdAt)}: ${esc((f.error || '').slice(0, 240))} · ${jobLink(f)}</span></td></tr>` : '';
  return `<tr class="${folds ? 'rel-calm' : ''}" ${folds && !showCalm ? 'hidden' : ''}><td><a class="sc-link" href="#/scenarios?open=${encodeURIComponent(`${p.name}/${r.scenario}`)}" title="${esc(sc.description || '')} · open on Scenarios"><b>${esc(r.scenario)}</b></a></td>
    ${cols().map(([k]) => cell(k)).join('')}<td>${verdict}</td><td>${when}</td><td class="row-action">${act}</td></tr>${why}`;
}
// The card of a plugin, as the one of master on the dashboard: the master pair against the release pair, a row per
// scenario; on the right how it runs, when it runs next (`next`, an item of /api/schedule) and when the extension was fetched
function releaseCard(p, showCalm, next) {
  const width = cols().length + 4, s = p.settings;
  // most rows were measured together: the head says when, a row only when it differs
  const times = p.rows.map(whenOf).filter(Boolean);
  const usual = times.sort((x, y) => times.filter((t) => t === y).length - times.filter((t) => t === x).length)[0];
  const quiet = p.rows.filter(calm);
  const pairs = p.pairs ? `<dl class="kv plugin-pairs"><dt>Master</dt><dd>${pairHtml(p, p.pairs.master)}</dd><dt>Release</dt><dd>${pairHtml(p, p.pairs.release)}</dd></dl>`
    : `<div class="notice small-text">${s.release ? esc(p.pairsError || 'the pairs are unknown') : 'The release of the extension is not set: the plugin does not run by itself.'}
      <a href="${pluginUrl(p.name, { tab: 'settings' })}" data-settings>${s.release ? 'Settings' : 'Set the release'}</a></div>`;
  const how = !s.release ? 'does not run: no release set' : s.mode === 'keep'
    ? `runs when a master moves, at most ${s.keepDays === 1 ? 'once a day' : `every ${s.keepDays} days`}` : 'runs only by hand';
  const fetched = p.mirror.lastFetchAt ? `${esc(p.title)} fetched ${fmt.ago(p.mirror.lastFetchAt)}` : `${esc(p.title)} not fetched yet`;
  return `<div class="card release" data-plugin="${esc(p.name)}">
    <div class="release-head plugin-release-head">
      <div><span class="release-title">master vs ${esc(p.pairs ? p.pairs.release.extRef : 'release')}</span>${usual ? `<span class="muted">measured ${usual}</span>` : ''}${pairs}</div>
      <div class="release-side"><div class="release-tools"><button class="small" data-settings>Settings</button>${p.pairs ? '<button class="small" data-run-all>Run all</button>' : ''}</div>
        <div class="muted small-text">${how}${next ? `<br>${planShort(next)}` : ''}<br>${fetched}${p.mirror.lastFetchError ? `<br><span class="error">${esc(p.mirror.lastFetchError)}</span>` : ''}</div></div>
    </div>
    <table class="release-table"><thead><tr><th>Scenario</th>${cols().map(metricHead).join('')}<th>Verdict</th><th></th><th></th></tr></thead>
      <tbody>${p.rows.map((r) => releaseRow(p, r, usual, showCalm, width)).join('')}${quiet.length ? calmLine(`data-rel-calm="${esc(p.name)}"`, width, showCalm,
    quiet.map((r) => r.scenario)) : ''}</tbody></table>
  </div>`;
}
// when the schedule of a plugin runs it next (/api/schedule)
const nextOf = (plan, p) => (plan || []).find((x) => x.kind === 'plugin' && x.plugin === p.name);
// Run all, or Run of a row: the release pair against the master pair
async function runNow(p, names) {
  const all = names.length > 1;
  const jobs = await confirmLaunch({ title: all ? `Run ${p.title}: master vs release` : `Run ${names[0]}: master vs release`,
    what: `master pair vs release pair<div class="muted small-text">${pairHtml(p, p.pairs.master)}<br>vs ${pairHtml(p, p.pairs.release)}</div>`,
    ...(all ? { choose: p.scenarios.map((s) => ({ name: s.name, checked: true })) } : { scenarios: names }), jobs: names.length,
    plugin: p.name, pairs: [p.pairs.master, p.pairs.release].map((x) => `${x.igniteSha}:${x.extSha}`),
    submit: (v) => api.post(`/api/plugins/${encodeURIComponent(p.name)}/run`, { scenarios: v.scenarios, priority: v.priority }) });
  if (!jobs) return;
  toast(`${plural(jobs.length, 'job')} queued`);
  refresh();
}
// what a card does: its buttons, the fold of its quiet rows; `open` holds the cards whose quiet rows are shown
const bindCards = (root, list, open) => root.addEventListener('click', (e) => {
  const card = e.target.closest('[data-plugin]'), p = card && list.find((x) => x.name === card.dataset.plugin);
  if (!p) return;
  if (e.target.closest('[data-settings]')) { e.preventDefault(); guarded(() => openSettings(p)); return; }
  if (e.target.closest('[data-run-all]')) { guarded(() => runNow(p, p.scenarios.map((s) => s.name))); return; }
  const run = e.target.closest('[data-run-sc]');
  if (run) { guarded(() => runNow(p, [run.dataset.runSc])); return; }
  const cmp = e.target.closest('[data-compare-sc]');
  if (cmp) { guarded(() => compareDialog(p, { scenarios: [cmp.dataset.compareSc] })); return; }
  if (e.target.closest('[data-rel-calm]')) {
    const show = !open.has(p.name);
    if (show) open.add(p.name); else open.delete(p.name);
    card.querySelectorAll('tr.rel-calm').forEach((t) => { t.hidden = !show; });
    card.querySelector('[data-rel-calm] .chev').textContent = show ? '▾' : '▸';
  }
});

views.plugins = {
  poll: 15000,
  calmOpen: new Set(),
  live() { return this.single ? views.plugin.live() : true; },
  async render() {
    const list = await api.get('/api/plugins');
    this.single = list.plugins.length === 1 && !list.errors.length; // one plugin: its page is the section
    if (this.single) return views.plugin.render({ name: list.plugins[0].name });
    [this.pages, this.plan] = await Promise.all([Promise.all(list.plugins.map((p) => api.get(`/api/plugins/${encodeURIComponent(p.name)}?comparisons=1&runs=1`))),
      api.get('/api/schedule').catch(() => [])]);
    return `<h1>Plugins <span class="muted">Ignite together with an extension of another repository: master of both against their last release</span></h1>
      ${list.errors.length ? `<div class="card error">Not loaded: ${list.errors.map(esc).join('<br>')}</div>` : ''}
      ${this.pages.map((p) => `<h2><a href="${pluginUrl(p.name)}">${esc(p.title)}</a> <span class="muted">${esc(p.description || '')}</span></h2>${releaseCard(p, this.calmOpen.has(p.name), nextOf(this.plan, p))}`).join('')
      || '<div class="card muted">No plugin is on: the configuration names none.</div>'}
      <div class="muted small-text">Both sides of a pair move, so a result says how the two masters do together, not which commit moved them: nothing is bisected, Compare on the page of a plugin narrows it down.</div>`;
  },
  bind(root) {
    if (this.single) views.plugin.bind(root);
    else bindCards(root, this.pages, this.calmOpen);
  },
};

// A step of a request of Compare: its Δ ops/s and verdict, or where its job is
function stepCell(j) {
  if (!j) return '<span class="muted">did not move</span>';
  if (j.status !== 'done') return `${badge(j.status)} ${jobLink(j)}${j.error ? ` <span class="muted" title="${esc(j.error)}">${esc(j.error.slice(0, 60))}</span>` : ''}`;
  return `${chip(j, 'throughput')} ${badge(j.verdict)} ${jobLink(j)}`;
}
// The requests of Compare, newest first: a row per request, unfolded into the step of each side per scenario, so the
// side that moved stands out
function comparisonsCard(p, open) {
  const rows = p.comparisons.map((c) => {
    const key = String(c.jobs[0]), steps = c.scenarios.flatMap((s) => [s.ignite, s.ext, s.both]).filter(Boolean);
    const done = steps.filter((j) => j.status === 'done');
    const ig = c.scenarios.some((s) => s.ignite), ex = c.scenarios.some((s) => s.ext), both = c.scenarios.some((s) => s.both);
    const verdicts = [['regression', 'regression'], ['improvement', 'improvement'], ['no-change', 'no change']]
      .map(([v, w]) => [done.filter((j) => j.verdict === v).length, v, w]).filter(([n]) => n)
      .map(([n, v, w]) => `<span class="badge ${v}">${n} ${w}</span>`).join(' ');
    const still = both ? '' : !ex ? `<span class="muted">${esc(p.title)} did not move</span>` : !ig ? '<span class="muted">Ignite did not move</span>' : '';
    const result = c.status === 'queued' || c.status === 'running' ? `${badge(c.status)} <span class="muted">${done.length} of ${plural(steps.length, 'step')}</span>`
      : `${c.status === 'failed' ? badge('failed') : ''} ${verdicts} ${still}`;
    const a = c.refsA || {}, b = c.refsB || {};
    const heads = [ig && `<th>Step 1: Ignite ${esc(a.ignite)} → ${esc(b.ignite)} <span class="muted">· ${esc(p.title)} ${esc(a.ext)} on both sides</span></th>`,
      ex && `<th>Step ${ig ? 2 : 1}: ${esc(p.title)} ${esc(a.ext)} → ${esc(b.ext)} <span class="muted">· Ignite ${esc(b.ignite)} on both sides</span></th>`,
      both && '<th>Both sides at once</th>'].filter(Boolean);
    const detail = `<table><thead><tr><th>Scenario</th>${heads.join('')}</tr></thead><tbody>${c.scenarios.map((s) => `<tr><td>${esc(s.scenario)}</td>
      ${ig ? `<td>${stepCell(s.ignite)}</td>` : ''}${ex ? `<td>${stepCell(s.ext)}</td>` : ''}${both ? `<td>${stepCell(s.both)}</td>` : ''}</tr>`).join('')}</tbody></table>`;
    const isOpen = open.has(key);
    return `<tr class="click-row" data-cmp="${key}"><td><span class="chev">${isOpen ? '▾' : '▸'}</span> ${fmt.ago(c.createdAt)}</td><td>${esc(c.a)}</td><td>${esc(c.b)}</td>
      <td class="num">${c.scenarios.length}</td><td>${result}</td><td class="muted">${esc((steps[0] && steps[0].requestedBy) || '')}</td>
      <td class="row-action"><button class="small" data-again="${key}" title="these pairs and scenarios in the dialog">Again</button></td></tr>
      <tr class="row-detail" ${isOpen ? '' : 'hidden'}><td colspan="7">${detail}</td></tr>`;
  }).join('');
  return `<div class="card">
    <div class="release-head"><div><span class="release-title">Comparisons</span><span class="muted">two pairs, one side at a time: Ignite first, then ${esc(p.title)}</span></div>
      <div class="release-tools"><button class="small primary" data-new-cmp>New comparison</button></div></div>
    ${rows ? `<table class="commits runs-table plugin-cmp"><thead><tr><th>Queued</th><th>A · baseline</th><th>B · target</th><th class="num">Scenarios</th><th>Result</th><th>By</th><th></th></tr></thead><tbody>${rows}</tbody></table>
      ${p.comparisonsMore ? `<div class="muted small-text more-line">${p.comparisons.length} shown · <a href="#" data-more-cmp>show 10 more</a></div>` : ''}`
    : '<div class="muted">No comparison yet: New comparison puts two pairs side by side and says which side moved.</div>'}
  </div>`;
}
// History: the runs against the release of every scenario, newest first, with how far each side moved since the run
// before of the same scenario
function historyCard(p) {
  if (!p.runs.length) return '';
  const rows = p.runs.map((h) => {
    const j = h.job, prev = h.prev;
    return `<tr><td>${jobLink(j)}</td><td>${esc(j.scenario)}</td><td>${fmt.ago(j.finishedAt)}</td>
      <td>${commitLink(p.igniteRepo, j.shaB)} + ${commitLink(p.repo, j.extShaB)}</td>
      <td>${prev ? moved(p.igniteRepo, prev.shaB, j.shaB, h.igniteMoved) : ''}</td><td>${prev ? moved(p.repo, prev.extShaB, j.extShaB, h.extMoved) : ''}</td>
      <td class="num">${chip(j, 'throughput')}</td><td>${badge(j.verdict)}</td>
      <td class="row-action">${prev ? `<button class="small" data-since="${j.id}" title="the master pair of the run before against this one, one side at a time">Compare with the run before</button>` : ''}</td></tr>`;
  }).join('');
  return `<div class="card">
    <div class="release-head"><div><span class="release-title">History</span><span class="muted">runs of master against the release, newest first</span></div></div>
    <table class="commits runs-table plugin-runs"><thead><tr><th>Job</th><th>Scenario</th><th>Finished</th><th>Master pair</th><th>Ignite moved</th><th>${esc(p.title)} moved</th><th class="num">ops/s</th><th>Verdict</th><th></th></tr></thead>
      <tbody>${rows}</tbody></table>
    ${p.runsMore ? `<div class="muted small-text more-line">${p.runs.length} shown · <a href="#" data-more-runs>show 20 more</a></div>` : ''}
  </div>`;
}

// The steps a request queues: Ignite first with the extension of A, then the extension with the Ignite of B. The refs
// are compared as typed: two names of one commit are told apart only by the stand, which leaves that step out
function stepsOf(p, a, b) {
  const out = [];
  if (a.ignite !== b.ignite) out.push(`Ignite ${esc(a.ignite)} → ${esc(b.ignite)} <span class="muted">· ${esc(p.title)} ${esc(a.ext)} on both sides</span>`);
  if (a.ext !== b.ext) out.push(`${esc(p.title)} ${esc(a.ext)} → ${esc(b.ext)} <span class="muted">· Ignite ${esc(b.ignite)} on both sides</span>`);
  return out;
}
// New comparison: two pairs and the scenarios; `preset` fills the pairs ({ignite, ext} each) and the scenarios
async function compareDialog(p, preset = {}) {
  const rel = p.pairs ? p.pairs.release : {}, mas = p.pairs ? p.pairs.master : {};
  const a = preset.a || { ignite: rel.igniteRef || '', ext: rel.extRef || '' }, b = preset.b || { ignite: mas.igniteRef || '', ext: mas.extRef || '' };
  const loose = unsettled(p);
  const picked = new Set(preset.scenarios || (loose.length ? loose : p.scenarios.map((s) => s.name)));
  const front = await frontPriority(1);
  const field = (side, k, label, value, list, ph) => `<label>${label}<input name="${side}.${k}" value="${esc(value)}" list="${list}" placeholder="${esc(ph)}" autocomplete="off" spellcheck="false"></label>`;
  const pair = (side, v) => `<div class="pair-in">${field(side, 'ignite', 'Ignite', v.ignite, 'pc-ignite', 'a tag, a branch, a sha, pr/N')}${field(side, 'ext', esc(p.title), v.ext, 'pc-ext', `a tag, a branch or a sha of ${repoName(p.repo)}`)}</div>`;
  const options = (list) => [...new Set(list.filter(Boolean))].map((x) => `<option value="${esc(x)}">`).join('');
  const body = `<div class="plugin-cmp-form">
    <datalist id="pc-ignite">${options(['master', rel.igniteRef, a.ignite])}</datalist><datalist id="pc-ext">${options([p.branch, ...(p.tags || [])])}</datalist>
    <dl class="kv"><dt>A · baseline</dt><dd>${pair('a', a)}</dd><dt>B · target</dt><dd>${pair('b', b)}</dd>
      <dt>Steps</dt><dd data-steps></dd>
      <dt>Scenarios</dt><dd>${scenarioPicker(p.scenarios, picked, { title: '', own: familiesByWord(p.scenarios), extra: [{ name: 'Not settled', scenarios: p.scenarios.filter((s) => loose.includes(s.name)) }] })}</dd>
      <dt>Priority</dt><dd>${priorityField(1, front)}</dd><dt>Jobs</dt><dd data-jobs></dd></dl><dl class="kv est"></dl></div>`;
  const read = (w) => {
    const v = (n) => w.querySelector(`[name="${n}"]`).value.trim();
    return { a: { ignite: v('a.ignite'), ext: v('a.ext') }, b: { ignite: v('b.ignite'), ext: v('b.ext') } };
  };
  const jobs = await modal(`New comparison of ${esc(p.title)}`, body, 'Queue', {
    asWho: true,
    open: (w) => {
      const yes = w.querySelector('[data-yes]');
      // how long: the pairs the steps build (the middle one when both sides move), after a pause in the typing
      let timer = null, asked = 0;
      const estimate = (pa, pb, n) => {
        clearTimeout(timer);
        timer = setTimeout(async () => {
          const me = ++asked, pairs = [...new Set([pa, { ignite: pb.ignite, ext: pa.ext }, pb].map((x) => `${x.ignite}:${x.ext}`))];
          const rows = n ? await estimateRows({ scenario: [...picked].join(','), jobs: n, plugin: p.name, pairs, priority: priorityOf(w) }) : '';
          if (me === asked) w.querySelector('.est').innerHTML = rows;
        }, 300);
      };
      const sync = () => {
        const { a: pa, b: pb } = read(w), filled = pa.ignite && pa.ext && pb.ignite && pb.ext, steps = filled ? stepsOf(p, pa, pb) : [];
        w.querySelector('[data-steps]').innerHTML = !filled ? '<span class="muted">both refs of both pairs</span>'
          : steps.length ? `${steps.map((s, i) => `<div>${i + 1}. ${s}</div>`).join('')}<div class="muted small-text">a side that does not move gets no step</div>`
          : '<span class="error">the two pairs are the same</span>';
        const n = picked.size * steps.length;
        w.querySelector('[data-jobs]').innerHTML = n ? `${n} <span class="muted">(${plural(picked.size, 'scenario')} × ${plural(steps.length, 'step')}, ABBA each)</span>` : '<span class="muted">–</span>';
        yes.disabled = !n;
        yes.textContent = n ? `Queue ${plural(n, 'job')}` : 'Queue';
        estimate(pa, pb, n);
      };
      w.addEventListener('input', sync);
      w.addEventListener('change', (e) => { if (e.target.name === 'prio') sync(); });
      bindScenarioPicker(w, picked, { estimate: false, then: sync });
      sync();
    },
    value: (w) => ({ ...read(w), priority: priorityOf(w) }),
    // the refs of both repositories are fetched first: the dialog waits for it
    submit: (v) => api.post(`/api/plugins/${encodeURIComponent(p.name)}/compare`,
      { a: v.a, b: v.b, scenarios: p.scenarios.map((s) => s.name).filter((n) => picked.has(n)), priority: v.priority }),
  });
  if (!jobs) return;
  toast(`${plural(jobs.length, 'job')} queued`);
  refresh(true);
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
  const key = (k) => `plugin.${p.name}.${k}`;
  const v = await modal(`Settings of ${esc(p.title)}`, settingsBody(p), 'Save', {
    value: (w) => Object.fromEntries(new FormData(w.querySelector('form'))),
    submit: (f) => api.put('/api/settings', {
      [key('release')]: (f.release || '').trim(), [key('ignite')]: (f.ignite || '').trim(), [key('mode')]: f.mode, [key('keepDays')]: f.keepDays,
    }),
  });
  if (!v) return;
  toast('saved');
  refresh(true);
}

views.plugin = {
  poll: 15000,
  calmOpen: new Set(),
  live() { return this.active; }, // while its jobs run; what is unfolded and how much is shown stays across re-renders
  async render(params) {
    const name = (params && params.name) || query().get('name');
    if (name !== this.name) { this.name = name; this.cmpOpen = new Set(); this.nCmp = 10; this.nRuns = 10; }
    const [list, p, plan] = await Promise.all([api.get('/api/plugins'),
      api.get(`/api/plugins/${encodeURIComponent(name)}?comparisons=${this.nCmp}&runs=${this.nRuns}`), api.get('/api/schedule').catch(() => [])]);
    this.p = p;
    this.active = p.rows.some((r) => r.active.length) || p.comparisons.some((c) => c.status === 'queued' || c.status === 'running');
    return `<h1>${list.plugins.length > 1 ? '<a href="#/plugins">Plugins</a> / ' : ''}${esc(p.title)} <span class="muted">${esc(p.description || '')}</span></h1>
      ${releaseCard(p, this.calmOpen.has(p.name), nextOf(plan, p))}
      ${comparisonsCard(p, this.cmpOpen)}
      ${historyCard(p)}`;
  },
  bind(root) {
    const p = this.p;
    bindCards(root, [p], this.calmOpen);
    root.addEventListener('click', (e) => {
      if (e.target.closest('[data-new-cmp]')) { guarded(() => compareDialog(p)); return; }
      const again = e.target.closest('[data-again]');
      if (again) {
        const c = p.comparisons.find((x) => String(x.jobs[0]) === again.dataset.again);
        guarded(() => compareDialog(p, { a: c.refsA, b: c.refsB, scenarios: c.scenarios.map((s) => s.scenario) }));
        return;
      }
      const since = e.target.closest('[data-since]');
      if (since) {
        const h = p.runs.find((x) => String(x.job.id) === since.dataset.since), j = h.job;
        guarded(() => compareDialog(p, { a: { ignite: short(h.prev.shaB), ext: short(h.prev.extShaB) }, b: { ignite: short(j.shaB), ext: short(j.extShaB) }, scenarios: [j.scenario] }));
        return;
      }
      if (e.target.closest('[data-more-cmp]')) { e.preventDefault(); this.nCmp += 10; refresh(); return; }
      if (e.target.closest('[data-more-runs]')) { e.preventDefault(); this.nRuns += 20; refresh(); return; }
      const r = e.target.closest('tr[data-cmp]');
      if (!r || e.target.closest('a, button')) return;
      const open = !this.cmpOpen.has(r.dataset.cmp);
      if (open) this.cmpOpen.add(r.dataset.cmp); else this.cmpOpen.delete(r.dataset.cmp);
      unfoldRow(r, open);
    });
    // an older link: Compare opens the dialog (with the pairs of a run), Settings its dialog, once per link
    const q = query();
    if ((q.get('tab') || q.get('job')) && this.handled !== location.hash) {
      this.handled = location.hash;
      if (q.get('tab') === 'settings') guarded(() => openSettings(p));
      else if (q.get('job')) {
        guarded(async () => {
          const j = await api.get(`/api/jobs/${q.get('job')}`);
          await compareDialog(p, { a: { ignite: short(j.shaA), ext: short(j.extShaA) }, b: { ignite: short(j.shaB), ext: short(j.extShaB) }, scenarios: [j.scenario] });
        });
      } else if (q.get('tab') === 'compare') guarded(() => compareDialog(p));
    }
  },
};
