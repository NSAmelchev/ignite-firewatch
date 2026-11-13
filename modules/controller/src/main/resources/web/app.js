// Firewatch UI: vanilla ES module, hash routing, polling. Charts are added by charts.js (dashboard, run details).
const $ = (sel, root = document) => root.querySelector(sel);
const app = $('#app');

// ------------------------------------------------------------------ api
async function call(method, path, body) {
  const res = await fetch(path, {
    method, headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
  if (!res.ok) throw new Error((data && data.error) || `${res.status} ${res.statusText}`);
  return data;
}
// display names of scenarios (@Bench title), filled from every /api/scenarios answer: ids stay the keys in links,
// data attributes and form values, only the text people read uses scName
const scTitles = new Map();
export const scName = (id) => scTitles.get(id) || id;
// the web page of the repository for links to commits and PRs, from the settings; null when the URL has none
let repoWeb = null;
// tickets in commit subjects, from the settings: "KEY=URL [PR URL]" per line. The ticket goes at the end of the URL, the
// number of a PR at the end of the PR URL of the space its commit belongs to, by default that of the repository of the stand
let ticketLinks = new Map(), ticketWord = null;
function readTicketLinks(text) {
  ticketLinks = new Map((text || '').split('\n').filter(Boolean).map((l) => {
    const [url, pr] = l.slice(l.indexOf('=') + 1).split(' ');
    return [l.slice(0, l.indexOf('=')), { url, pr: pr || '' }];
  }));
  ticketWord = ticketLinks.size ? new RegExp(`\\b(${[...ticketLinks.keys()].join('|')})-\\d+\\b`, 'g') : null;
}
const webOf = (url) => { const m = /^(?:https?:\/\/|ssh:\/\/git@|git@)([^:/]+)[:/](.+?)(?:\.git)?\/?$/.exec(url || ''); return m ? `https://${m[1]}/${m[2]}` : null; };
export const api = {
  get: (p) => call('GET', p).then((d) => {
    if (p === '/api/scenarios' && Array.isArray(d)) d.forEach((x) => scTitles.set(x.name, x.title || x.name));
    if (p === '/api/settings') { repoWeb = webOf(d['repo.url']); readTicketLinks(d['links.tickets']); }
    return d;
  }),
  post: (p, b) => call('POST', p, b || {}),
  put: (p, b) => call('PUT', p, b),
  del: (p) => call('DELETE', p),
};

// ------------------------------------------------------------------ helpers
export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const fmt = {
  num(v, d = 0) { return v == null || Number.isNaN(v) ? '–' : Number(v).toLocaleString('en-US', { maximumFractionDigits: d, minimumFractionDigits: d }); },
  pct(v, d = 1) { return v == null ? '–' : (v > 0 ? '+' : '') + Number(v).toFixed(d) + '%'; },
  sha(s) { return s ? s.slice(0, 10) : '–'; },
  // 30 Oct 15:32, with the year when it is not this one
  date(iso) {
    if (!iso) return '–';
    const d = new Date(iso);
    return d.toLocaleString('en-GB', { day: '2-digit', month: 'short', year: d.getFullYear() !== new Date().getFullYear() ? 'numeric' : undefined, hour: '2-digit', minute: '2-digit', hour12: false }).replace(',', '');
  },
  // a length of time: 45 s, 8 min, 1.5 h
  len(sec) { return sec < 90 ? `${Math.round(sec)} s` : sec < 5400 ? `${Math.round(sec / 60)} min` : `${(sec / 3600).toFixed(1)} h`; },
  ago(iso) {
    if (!iso) return '–';
    const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
    return s < 86400 ? `${fmt.len(s)} ago` : `${Math.round(s / 86400)} d ago`;
  },
  dur(startIso, endIso) {
    if (!startIso) return '–';
    return fmt.len(Math.max(0, ((endIso ? new Date(endIso) : new Date()).getTime() - new Date(startIso).getTime()) / 1000));
  },
  bytes(b) { if (b == null) return '–'; const u = ['B', 'KB', 'MB', 'GB']; let i = 0; while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; } return `${b.toFixed(i ? 1 : 0)} ${u[i]}`; },
};
// What the words of a performance stand mean, on hover: one sentence each, the same on every page
const HELP = {
  ABBA: 'Four phases in the order A, B, B, A, each in fresh JVMs: slow drift of the stand cancels out.',
  'A/A': 'The same build on both sides: whatever differs is the noise of the stand.',
  floor: 'How far A/A runs drift by chance: a smaller Δ means nothing.',
  threshold: 'A Δ counts only beyond it: the larger of the noise floor and the spread between repeats.',
  CI: 'The range where the true Δ lies with 95 % confidence, from per-second samples.',
  signal: 'Δ divided by the spread of its phases: the higher, the more likely a bisect reaches a commit.',
  segment: 'A range of commits measured as one: its end against its start.',
  behind: 'Commits on the branch newer than this run.',
  regression: 'Worse beyond the threshold.',
  improvement: 'Better beyond the threshold.',
  'no-change': 'Within the threshold: nothing measurable moved.',
  over: 'Beyond the threshold on some metric, but not enough for a verdict.',
  found: 'The search ended on a commit that moves the metric.',
  spread: 'The range moves, no single commit does: many small changes add up.',
  inconclusive: 'The change is real, but too close to the noise for this search: Deep search measures it properly.',
  lost: 'The change did not show up again: it was noise.',
  stopped: 'The search paused on a failed or cancelled step: Resume continues it.',
  unexplored: 'It moved and nobody has searched for the cause yet.',
  narrowed: 'A shorter segment moved the same way, so the cause is inside it. It still takes a bisect to find the commit, but one that needs less time than usual.',
  partial: 'The commits found explain only a part of what master shows.',
  explained: 'The commits found add up to what master shows: nothing else moved.',
  new: 'A run newer than the latest master vs release run: nothing explains it yet.',
  running: 'A bisect is looking for the commit right now.',
  searching: 'Everything is measured, its bisects are looking for the commits.',
  search: 'There is more to find: Bisect, Deep search or Resume takes it further.',
  nocommit: 'The search ended without a commit: small changes add up, or it was noise. Deep search can look closer.',
  after: 'A scenario takes one bisect at a time: this search can start when that bisect ends.',
  deep: 'Both halves on every level and more runs where the answer is close: finds a change near the noise, takes hours.',
  noise: 'Inside the threshold: nothing to see here.',
  refined: 'A shorter run ends at the same commit and replaced this one.',
  stillWorse: 'The direct run of master against the release still shows this change.',
  stillBetter: 'The direct run of master against the release still shows this change.',
  somewhere: 'A run of several commits moved: the change is one of them, a bisect finds which.',
  band: 'The commits an active bisect still suspects.',
  throughput: 'Operations per second over the measure window.',
  p99Us: 'Latency that 99 % of operations stay under.',
  serverCpuUsPerOp: 'Server CPU time per operation: it does not depend on how fast the load runs.',
  allocKbPerOp: 'Heap the server allocates per operation.',
  heapAfterGcMb: 'Heap still in use after GC.',
  serverStartMs: 'How long a node starts: noise of the stand, never a verdict.',
  preloadMs: 'How long the data loads: noise of the stand, never a verdict.',
};
// a commit subject with its ticket and PR as links; never inside another link
// a stand without network fetches from its own disk: git takes a path as well as a URL
const REPO_PATH_HINT = 'A stand without network takes a path on this machine: a mirror (<code>git clone --mirror</code>) or a bundle (<code>git bundle create ignite.bundle --all</code>); in Docker, under <code>/git</code>.';
const defaultPrUrl = () => (repoWeb ? `${repoWeb}/pull/` : '');
function subjectHtml(s) {
  let pr = defaultPrUrl();
  const text = ticketWord ? esc(s).replace(ticketWord, (t, key) => {
    const l = ticketLinks.get(key);
    pr = l.pr || pr;
    return `<a href="${esc(l.url)}${t}" target="_blank" rel="noopener">${t}</a>`;
  }) : esc(s);
  return text.replace(/(?<![&\w])#(\d+)\b/g, (m, n) => (pr ? `<a href="${esc(pr)}${n}" target="_blank" rel="noopener">#${n}</a>` : m));
}
const hint = (key) => (HELP[key] ? ` title="${esc(HELP[key])}"` : '');
// a word with its meaning on hover
const term = (key, text = key) => `<span class="term"${hint(key)}>${text}</span>`;
export const badge = (s) => s ? `<span class="badge ${esc(s)}"${hint(s)}>${esc(s)}</span>` : '';
export const jobLink = (j) => `<a href="#/jobs/${j.id}">#${j.id}</a>`;
const deltaCell = (r) => r && r.deltaPct != null
  ? `<span class="delta ${esc(r.verdict || '')}">${fmt.pct(r.deltaPct)}</span>` : '–';
// value with its delta next to it, highlighted by level against the threshold (see deltaLevel)
const HIGHER_BETTER = new Set(['throughput']);
// Δ level against the job's threshold (max of the A/A noise floor and the repeat spread):
// 'verdict' = significant change (bold, coloured), 'over' = beyond the threshold but not proven (soft background), '' = noise
const deltaLevel = (d, threshold, verdict) => {
  if (d == null) return '';
  if (verdict === 'regression' || verdict === 'improvement') return 'verdict';
  return threshold != null && Math.abs(d) > threshold ? 'over' : '';
};
// how a Δ reads on a map: the name of its swatch in the legend
const mapLevel = (metric, d, threshold, verdict) => {
  if (d == null) return 'none';
  const lv = deltaLevel(d, threshold, verdict), worse = HIGHER_BETTER.has(metric) ? d < 0 : d > 0;
  return lv === 'verdict' ? (worse ? 'bad' : 'good') : lv === 'over' ? 'over' : 'noise';
};
// how long a node starts and loads: noise of the stand, never an alarm
const STARTUP = new Set(['serverStartMs', 'preloadMs']);
// How a Δ is marked: filled only on a metric that decides the verdict of a run, or the one a page follows. The others
// moved too but stay quiet, so a filled cell never sits in a no-change row.
const deltaClass = (key, d, threshold, verdict, main = VERDICT_KEYS.includes(key)) => {
  const level = STARTUP.has(key) ? '' : deltaLevel(d, threshold, verdict), worse = HIGHER_BETTER.has(key) ? d < 0 : d > 0;
  return level === 'verdict' && main ? (worse ? 'v-worse' : 'v-better') : level ? (worse ? 'hl-worse' : 'hl-better') : '';
};
const valDelta = (key, v, digits, d, verdict, threshold, main) => {
  const val = fmt.num(v, digits);
  if (d == null) return val;
  const cls = deltaClass(key, d, threshold, verdict, main);
  const title = `Δ vs baseline${threshold != null ? `, threshold ±${threshold.toFixed(1)}%` : ''}${verdict ? `, verdict ${verdict}` : ''}`;
  return `${val} <span class="dlt ${cls}" title="${esc(title)}">${fmt.pct(d)}</span>`;
};
// the metric columns shared by Dashboard and Commits: [key, header, digits]
const METRIC_COLS = [['throughput', 'ops/s', 0], ['p99Us', 'p99 µs', 0], ['serverCpuUsPerOp', 'CPU µs/op', 2], ['allocKbPerOp', 'alloc KB/op', 2],
  ['serverStartMs', 'start ms', 0], ['preloadMs', 'preload ms', 0]];
const runsMark = (p) => (p.runs > 1 ? ` <span class="runs" title="pooled over jobs ${p.jobIds.map((i) => `#${i}`).join(', ')}">×${p.runs}</span>` : '');
// "one more run" = retry of the job: same pair, same scenario; its result is pooled with the previous runs
const moreRunBtn = (jobId) => `<button class="small" data-more="${jobId}" title="queue one more identical ABBA run of job #${jobId}; results of all runs are pooled">Run</button>`;
const splitBtns = (jobId, cls = 'small') => `<button class="${cls}" data-split="${jobId}" data-mode="halves" title="queue A..middle and middle..B">Split ½</button>
  <button class="${cls}" data-split="${jobId}" data-mode="commits" title="queue every commit of the segment against its parent">Each commit</button>`;
// what a job is a part of: the step of an automatic bisection or a scenario of a comparison
const parentTag = (j) => (j && j.bisectId ? ` <a class="badge bisect" href="#/bisects/${j.bisectId}" title="step ${j.bisectStep} of automatic bisect #${j.bisectId}">bisect #${j.bisectId} · step ${j.bisectStep}</a>`
  : j && j.comparisonId ? ` <a class="badge bisect" href="#/comparisons/${j.comparisonId}" title="a scenario of comparison #${j.comparisonId}">comparison #${j.comparisonId}</a>` : '');
// the button that starts a bisection from a segment with a verdict
const bisectBtn = (jobId, cls = 'small') => `<button class="${cls}" data-bisect="${jobId}" title="find the commit that moved the metric: one half per step, queued automatically until one commit is left">Bisect</button>`;
const MOVED = new Set(['regression', 'improvement']);
// what a bisection can follow: the four metrics of the tables, then heap after GC
const BISECT_KEYS = [...METRIC_COLS.slice(0, 4).map(([k]) => k), 'heapAfterGcMb'];
// how far a metric moved over the spread of its own phases: below WEAK_SIGNAL a bisection rarely reaches a commit
const WEAK_SIGNAL = 3;
// calibrations a scenario needs for floors of its own (JobService.noise)
const OWN_FLOORS = 3;
const signalOf = (x) => Math.abs(x.deltaPct) / Math.max(x.spreadAPct || 0, x.spreadBPct || 0, 0.05);
const signalText = (x) => { const s = signalOf(x); return `signal ${s < 10 ? s.toFixed(1) : Math.round(s)}×${s < WEAK_SIGNAL ? ', weak' : ''}`; };
// the metrics with a verdict, the cleanest signal first, heap after GC last
const rankedMetrics = (m) => BISECT_KEYS.filter((k) => m[k] && MOVED.has(m[k].verdict))
  .sort((a, b) => (a === 'heapAfterGcMb') - (b === 'heapAfterGcMb') || signalOf(m[b]) - signalOf(m[a]));
const movedMetrics = (j) => rankedMetrics((j.comparison && j.comparison.metrics) || {});
// the actions of one job, side by side: run again (pooled), split or bisect a segment, cancel or retry
function jobActions(j) {
  const n = j.segment ? j.segment.count : 1, act = j.status === 'queued' || j.status === 'running';
  return `<span class="job-actions">${
    act ? `<button class="act" data-cancel="${j.id}">Cancel</button>`
    : j.status === 'done' ? `<button class="act" data-more="${j.id}" title="results of all runs of the same pair are pooled">Run again</button>`
    : `<button class="act" data-retry="${j.id}">Retry</button>`}${
    j.status === 'done' && n > 1 ? ` ${splitBtns(j.id, 'act')}` : ''}${
    j.status === 'done' && (n > 1 || j.comparisonId) && movedMetrics(j).length ? ` ${bisectBtn(j.id, 'act')}` : ''}</span>`;
}
async function moreRun(jobId, retry = false) {
  const s = await api.get(`/api/jobs/${jobId}`);
  if (!(await confirmLaunch({ title: retry ? `Retry job #${jobId}` : 'Run again', job: s, scenario: s.scenario, type: s.type,
    shas: [s.shaA, s.shaB], priority: Math.max(s.priority, 1),
    note: retry ? 'a new job with the same refs and scenario' : 'one more identical run; its result is pooled with the previous runs' }))) return;
  const j = await api.post(`/api/jobs/${jobId}/retry`, {});
  toast(`job #${j.id} queued: ${retry ? 'retry' : 'one more run'} of #${jobId}`);
  refresh();
}
async function splitJob(jobId, mode) {
  const s = await api.get(`/api/jobs/${jobId}`);
  const cs = (s.segment && s.segment.commits) || [], n = cs.length;
  const halves = mode === 'halves', mid = cs[Math.floor(n / 2) - 1];
  if (!(await confirmLaunch({ title: halves ? 'Split the segment in halves' : 'Measure every commit of the segment', job: s, scenario: s.scenario,
    jobs: halves ? 2 : n, shas: halves ? [s.shaA, mid && mid.sha, s.shaB] : [s.shaA, ...cs.map((c) => c.sha)], priority: 1,
    note: halves ? `2 segments of ${Math.floor(n / 2)} and ${n - Math.floor(n / 2)} commits` : `${n} jobs, each commit against its parent (already queued ones are skipped)` }))) return;
  const js = await api.post(`/api/jobs/${jobId}/split`, { mode });
  toast(`${js.length} job(s) queued from the segment`);
  refresh();
}
const METRIC_NAMES = { throughput: 'throughput', p99Us: 'p99', serverCpuUsPerOp: 'CPU/op', allocKbPerOp: 'alloc/op', heapAfterGcMb: 'heap after GC' };
// where a run goes in the queue: the same two choices in every dialog
const priorityField = () => `<div class="seg-radio"><label><input type="radio" name="prio" value="2" checked><span>ahead of the queue</span></label>
  <label><input type="radio" name="prio" value="1"><span>as usual</span></label></div>`;
const priorityOf = (w) => Number(w.querySelector('[name=prio]:checked').value);
const metricOption = (k, x) => `${esc(METRIC_NAMES[k])} ${fmt.pct(x.deltaPct)} (${esc(x.verdict)}, ${signalText(x)})`;
const levelsOf = (n) => Math.ceil(Math.log2(Math.max(2, n)));
const DEEP_RUNS = 4;
async function startBisect(jobId, metric) {
  const j = await api.get(`/api/jobs/${jobId}`);
  const c = j.comparisonId ? await api.get(`/api/comparisons/${j.comparisonId}`) : null;
  const row = c && c.rows.find((r) => r.job.id === j.id); // repeats are pooled there
  const m = ((row || j).comparison || {}).metrics || {}, moved = rankedMetrics(m);
  if (!moved.length) { toast(`job #${jobId} shows no verdict to follow`, true); return; }
  if (c && c.commits < 2) { toast(`${c.label}: ${plural(c.commits, 'commit')} between the two sides, nothing to bisect`, true); return; }
  const pick = moved.includes(metric) ? metric : moved[0];
  const n = c ? c.commits : (j.segment && j.segment.count) || 2, lv = levelsOf(n), steps = Math.ceil(lv * 1.5);
  const q = new URLSearchParams({ scenario: j.scenario, type: 'abba', jobs: steps, shas: '', extraBuilds: steps, priority: 2 });
  const e = await api.get(`/api/estimate?${q}`).catch(() => null);
  const body = `<dl class="kv">
    <dt>Segment</dt><dd>${jobTitle(j, false)} <span class="muted">${jobRefsShort(j)}</span></dd>
    <dt>Follow</dt><dd><select name="metric">${moved.map((k) => `<option value="${k}" ${k === pick ? 'selected' : ''}>${metricOption(k, m[k])}</option>`).join('')}</select></dd>
    <dt>Priority</dt><dd>${priorityField()}</dd>
    <dt>Takes</dt><dd>≈ ${lv}–${2 * lv} steps per change${e ? `, ~${mins(e.totalSec)} for one` : ''} <span class="muted">(${n} commits, ${lv} levels: the left half,
      and the right one too when the left moved; halves measured before are reused)</span></dd>
  </dl>
  <div class="modal-note">Every step is an ordinary ABBA run, one at a time in the queue. Every half that moves on its own is searched, down to its
    commit; a range that moves while its halves do not ends as spread over small changes. Cancel removes its queued step.</div>`;
  const v = await modal(`Bisect ${n} commits`, body, 'Start', {
    value: (w) => ({ metric: w.querySelector('[name=metric]').value, priority: priorityOf(w) }),
  });
  if (!v) return;
  const b = await api.post('/api/bisects', { jobId: Number(jobId), metric: v.metric, priority: v.priority });
  toast(`bisect #${b.id} started`);
  if (c) refresh(); else location.hash = `#/bisects/${b.id}`;
}
// what a comparison can still bisect: a scenario with a moved metric and no bisection yet (a cancelled one does not count)
function bisectCandidates(c) {
  const taken = new Set(c.bisects.filter((b) => b.status !== 'cancelled').map((b) => b.scenario));
  return c.rows.filter((r) => r.job.status === 'done' && !taken.has(r.scenario) && rankedMetrics((r.comparison || {}).metrics || {}).length);
}
// Several bisections at once. rows: [{ scenario, checked, busy, options: [{ jobId, metric, x, commits }] }], an option is what a
// bisection of the scenario can follow, busy the bisect the scenario waits for; a step costs a run and a build, the number of
// steps follows the size of the range
async function pickBisects({ title, note, rows }) {
  const stepSec = new Map(await Promise.all(rows.map(async ({ scenario }) => {
    const q = new URLSearchParams({ scenario, type: 'abba', jobs: 1, shas: '', extraBuilds: 1, priority: 2 });
    const e = await api.get(`/api/estimate?${q}`).catch(() => null);
    return [scenario, e ? e.totalSec : 0];
  })));
  // a table rather than a line of text in a list: the numbers of a choice stand in columns, and the list stays one size
  const line = (r, i) => `<tr class="pick" data-pick="${i}">
    <td><label><input type="checkbox" data-row="${i}" ${r.checked ? 'checked' : ''} ${r.busy ? 'disabled' : ''}> ${esc(scName(r.scenario))}${r.busy ? ` · ${term('after', `after #${r.busy}`)}` : ''}</label></td>
    <td><select ${r.options.length < 2 || r.busy ? 'disabled' : ''}>${r.options.map((o, n) => `<option value="${n}">${esc(METRIC_NAMES[o.metric] || o.metric)}</option>`).join('')}</select></td>
    <td class="num" data-delta></td><td class="num" data-signal></td><td class="num" data-range></td></tr>`;
  const body = `<table class="bisect-pick"><thead><tr><th>Scenario</th><th>Metric</th><th class="num">Δ</th><th class="num"${hint('signal')}>Signal</th><th class="num">Commits</th></tr></thead>
    <tbody>${rows.map(line).join('')}</tbody></table>
    <dl class="kv"><dt>Priority</dt><dd>${priorityField()}</dd></dl>
    <div class="modal-note">${note} Steps of several bisections take turns in the queue. ${term('signal', 'Signal')} under ${WEAK_SIGNAL}× is weak: such a search rarely reaches a commit.</div>`;
  const optionOf = (tr) => rows[Number(tr.dataset.pick)].options[Number($('select', tr).value)];
  const picked = (w) => [...w.querySelectorAll('[data-row]:checked')].map((i) => ({ r: rows[Number(i.dataset.row)], o: optionOf(i.closest('tr')) }));
  const takes = (w) => {
    w.querySelectorAll('tr[data-pick]').forEach((tr) => {
      const { metric, x, commits } = optionOf(tr), s = signalOf(x);
      tr.classList.toggle('off', !$('[data-row]', tr).checked);
      $('[data-delta]', tr).innerHTML = `<span class="dlt ${deltaClass(metric, x.deltaPct, x.thresholdPct, x.verdict, true)}">${fmt.pct(x.deltaPct)}</span>`;
      $('[data-signal]', tr).innerHTML = `${s < WEAK_SIGNAL ? '<span class="weak">weak</span> ' : ''}${s < 10 ? s.toFixed(1) : Math.round(s)}×`;
      $('[data-range]', tr).textContent = fmt.num(commits);
    });
    const on = picked(w), sec = on.reduce((x, { r, o }) => x + Math.ceil(levelsOf(o.commits) * 1.5) * stepSec.get(r.scenario), 0);
    w.querySelector('[data-sum]').textContent = on.length ? `${plural(on.length, 'bisection')} · ~${mins(sec)}` : 'nothing picked';
    w.querySelector('[data-yes]').disabled = !on.length;
  };
  const v = await modal(title, body, 'Start', {
    open: (w) => { takes(w); w.addEventListener('change', () => takes(w)); },
    value: (w) => ({ priority: priorityOf(w), items: picked(w).map(({ o }) => ({ jobId: o.jobId, metric: o.metric })) }),
  });
  if (!v) return false;
  const failed = [];
  for (const it of v.items) {
    try { await api.post('/api/bisects', { ...it, priority: v.priority }); }
    catch (e) { failed.push(e.message); }
  }
  toast(failed.length ? `${v.items.length - failed.length} of ${v.items.length} started: ${failed.join('; ')}` : `${plural(v.items.length, 'bisection')} started`, failed.length > 0);
  return true;
}
// One bisection per deviation of a comparison: scenarios with a verdict are picked, the ones where only a metric moved are offered
async function startBisects(id) {
  const c = await api.get(`/api/comparisons/${id}`), rows = bisectCandidates(c);
  if (!rows.length) { toast('nothing left to bisect', true); return; }
  const started = await pickBisects({ title: `Bisect the deviations of ${esc(c.label)}`, note: `${c.commits} commits, the metric with the cleanest signal is chosen.`,
    rows: rows.map((r) => ({ scenario: r.scenario, checked: MOVED.has(r.verdict),
      options: rankedMetrics(r.comparison.metrics).map((k) => ({ jobId: r.job.id, metric: k, x: r.comparison.metrics[k], commits: c.commits })) })) });
  if (started) refresh();
}
// What the master card can still search: a scenario with a moved metric that nothing explains. A scenario with a bisection going on
// waits for it, at the end of the list. A clear signal is picked, a weak one only offered: noise is not worth the hours of the stand.
const OPEN_LINES = new Set(['unexplored', 'narrowed', 'partial', 'new']);
const canBisect = (l) => OPEN_LINES.has(l.status) && MOVED.has(l.verdict) && l.rangeCommits > 1;
function openBisects(a) {
  const opts = (r) => r.lines.filter(canBisect).sort((x, y) => signalOf(y) - signalOf(x))
    .map((l) => ({ jobId: l.jobId, metric: l.metric, x: l, commits: l.rangeCommits, note: plural(l.rangeCommits, 'commit') }));
  const all = a.rows.map((r) => ({ scenario: r.scenario, options: opts(r), busy: r.activeBisect })).filter((r) => r.options.length);
  return [...all.filter((r) => !r.busy).map((r) => ({ ...r, checked: signalOf(r.options[0].x) >= WEAK_SIGNAL })), ...all.filter((r) => r.busy)];
}
async function startOpenBisects(view) {
  const a = await api.get('/api/attention'), rows = openBisects(a);
  if (!rows.length) { toast('nothing left to bisect', true); return; }
  const started = await pickBisects({ title: `Bisect what is open on master vs ${esc(a.tag)}`, note: 'A narrowed metric starts from its short segment.', rows });
  if (started) { view.attention = null; refresh(); }
}
async function deleteBisect(id) {
  if (!(await modal(`Delete bisect #${id}?`, '<div>Only its record goes: its steps stay as runs of the branch and count in later searches. This cannot be undone.</div>', 'Delete', { danger: true }))) return;
  await api.del(`/api/bisects/${id}`);
  toast(`bisect #${id} deleted`);
  location.hash = '#/bisects';
}
async function cancelBisect(id) {
  const b = await api.get(`/api/bisects/${id}`);
  const w = b.waiting, running = w && w.status === 'running', own = b.state.steps.filter((x) => !x.reused).length;
  const body = `<div>No new steps will be queued${w && w.status === 'queued' ? `; the queued step ${jobLink(w)} leaves the queue` : ''}.</div>
    ${running ? `<div class="choose"><label><input type="radio" name="run" value="stop" checked> stop the running step ${jobLink(w)} now</label>
      <label><input type="radio" name="run" value="finish"> let it finish: its result stays on the map</label></div>` : ''}
    <div class="choose"><label><input type="checkbox" name="del"> also delete its finished steps (${own}): their runs and files, cannot be undone</label></div>
    <div class="modal-note">Kept steps stay on Timeline and the commit pages as ordinary runs, and a new bisect reuses them.</div>`;
  const v = await modal(`Cancel bisect #${id}?`, body, 'Cancel bisect', {
    danger: true,
    value: (x) => ({ stopRunning: !x.querySelector('[name=run][value=finish]:checked'), deleteFinished: x.querySelector('[name=del]').checked }),
  });
  if (!v) return;
  await api.post(`/api/bisects/${id}/cancel`, v);
  toast(`bisect #${id} cancelled`);
  refresh();
}
async function resumeBisect(id) {
  await api.post(`/api/bisects/${id}/resume`, {});
  toast(`bisect #${id} resumed`);
  refresh();
}
// the second, slow search of a bisection that ended without a commit: only on the user's word
const DEEP_STATES = new Set(['inconclusive', 'spread', 'lost']);
const canGoDeep = (b) => DEEP_STATES.has(b.status) && !b.state.deep;
const deepButton = (id) => `<button class="small" data-bisect-deep="${id}"${hint('deep')}>Deep search</button>`;
// What a line of the master card can do next; a scenario takes one bisect at a time, so a busy one says what it waits for
function attentionAction(l, busy) {
  const deep = DEEP_STATES.has(l.status) && !l.deep;
  if (!deep && !canBisect(l)) return '';
  if (busy) return `<a class="muted" href="#/bisects/${busy}"${hint('after')}>after #${busy}</a>`;
  return deep ? deepButton(l.bisectId) : `<button class="small" data-bisect="${l.jobId}" data-metric="${esc(l.metric)}">Bisect</button>`;
}
async function deepBisect(id) {
  const b = await api.get(`/api/bisects/${id}`);
  const n = (b.positions || {})[b.shaB] || 2, lv = levelsOf(n), steps = Math.ceil(lv * 2.5) + 4;
  const q = new URLSearchParams({ scenario: b.scenario, type: 'abba', jobs: steps, shas: '', extraBuilds: lv, priority: 2 });
  const e = await api.get(`/api/estimate?${q}`).catch(() => null);
  const body = `<dl class="kv">
    <dt>Metric</dt><dd>${metricName(b)} <span class="muted">${esc(scName(b.scenario))}</span></dd>
    <dt>Segment</dt><dd>${plural(n, 'commit')} <span class="muted">${fmt.sha(b.shaA)}..${fmt.sha(b.shaB)}</span>${b.state.root ? ` · ${fmt.pct(b.state.root.deltaPct)}` : ''}</dd>
    <dt>Priority</dt><dd>${priorityField()}</dd>
    <dt>Takes</dt><dd>≈ ${steps} steps${e ? `, ~${mins(e.totalSec)}` : ''} <span class="muted">(${lv} levels; pairs measured before are reused, a second change adds more)</span></dd>
  </dl>
  <div class="modal-note">Measures both halves on every level and follows the one that carries the change, even under the threshold; a close call gets up to
    ${DEEP_RUNS} runs, and so does the commit it ends on. It ends on a commit, or on the shortest range that still moves. Cancel removes its queued step.</div>`;
  const v = await modal(`Deep search of bisect #${b.id}`, body, 'Start', { value: priorityOf });
  if (!v) return;
  await api.post(`/api/bisects/${id}/deep`, { priority: v });
  toast(`bisect #${id}: deep search started`);
  refresh();
}
const bisectActions = (b) => (b.status === 'running' ? `<button class="small" data-bisect-cancel="${b.id}">Cancel</button>`
  : b.status === 'stopped' ? `<button class="small" data-bisect-resume="${b.id}">Resume</button> <button class="small" data-bisect-cancel="${b.id}">Cancel</button>`
    : canGoDeep(b) ? deepButton(b.id) : '');
// one line of a bisection: where it is now, or how it ended
const foundOf = (b) => b.state.findings.filter((f) => f.kind === 'found');
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
function bisectNow(b) {
  const s = b.state, n = s.steps.length + (b.waiting ? 1 : 0), left = s.open.reduce((x, r) => x + r.commits, 0), found = foundOf(b);
  if (b.status === 'found') return `found <a class="mono" href="#/commits/${b.resultSha}">${fmt.sha(b.resultSha)}</a> <span class="job-subject" title="${esc(b.subjects[b.resultSha] || '')}">${esc(b.subjects[b.resultSha] || '')}</span>${found.length > 1 ? ` <span class="muted">+${found.length - 1} more</span>` : ''}`;
  if (b.status === 'running') return `${s.deep ? 'deep search, ' : ''}step ${n}${b.waiting ? ` ${jobLink(b.waiting)} ${badge(b.waiting.status)}` : ''} · ${s.open.length > 1 ? `${s.open.length} ranges, ` : ''}${plural(left, 'commit')} left
    ${found.length ? `· ${found.length} found ` : ''}${b.etaSec ? `· done ${etaStart(new Date(Date.now() + b.etaSec * 1000).toISOString())}` : ''}`;
  return `<span class="muted">${esc(b.reason || '')}</span>`;
}
const metricName = (b) => esc(METRIC_NAMES[b.metric] || b.metric);
const bisectWhat = (b) => `${term(b.metric, metricName(b))} ${esc(b.direction)}${b.state.root ? ` ${fmt.pct(b.state.root.deltaPct)}` : ''}`;
const bisectActive = (b) => b.status === 'running' || b.status === 'stopped';
const BISECT_MARKS = { running: '↻', stopped: '‖', found: '✓', inconclusive: '?', spread: '≈', lost: '≈', cancelled: '×' };
// a bisection in a few lines: where it is, the commits found with their Δ, what is spread over small changes, the share explained
function bisectResult(b) {
  const big = (x, y) => Math.abs(y.result.deltaPct) - Math.abs(x.result.deltaPct);
  const found = foundOf(b).sort(big), spread = b.state.findings.filter((f) => f.kind === 'spread').sort(big);
  const root = b.state.root ? b.state.root.deltaPct : 0, sum = found.reduce((x, f) => x + f.result.deltaPct, 0);
  const now = b.status === 'running' ? bisectNow(b) : b.status === 'found' || b.status === 'spread' ? '' : esc(b.reason || '');
  return `${now ? `<div class="muted">${now}</div>` : ''}
    ${found.map((f) => `<div><a class="mono" href="#/commits/${esc(f.b)}">${fmt.sha(f.b)}</a> ${fmt.pct(f.result.deltaPct)} <span class="muted">${subjectHtml(b.subjects[f.b])}</span></div>`).join('')}
    ${spread.map((f) => `<div class="muted">${plural(f.commits, 'commit')} ${fmt.sha(f.a)}..${fmt.sha(f.b)} ${fmt.pct(f.result.deltaPct)}: spread over small changes</div>`).join('')}
    ${found.length && root && b.status === 'found' ? `<div class="muted">explains ${Math.round(100 * sum / root)} % of ${fmt.pct(root)}</div>` : ''}`;
}

// A search run again over the same range says more than the old one: a row per metric of a segment, the newest search
// that was not cancelled on it, the others named next to it
function latestBisects(list) {
  const groups = new Map();
  [...list].sort((x, y) => x.id - y.id).forEach((b) => {
    const key = [b.scenario, b.metric, b.shaA, b.shaB].join('|'), g = groups.get(key);
    if (g && b.status === 'cancelled') g.earlier.push(b);
    else if (g) { g.earlier.push(g.b); g.b = b; }
    else groups.set(key, { b, earlier: [] });
  });
  return [...groups.values()];
}
const earlierMark = (g) => (g.earlier.length ? ` <span class="muted" title="earlier searches of the same range">${g.earlier.map((b) => `<a href="#/bisects/${b.id}">#${b.id}</a>`).join(' ')}</span>` : '');

// Δ of a finished job in one line: the verdict metrics, coloured as in the tables
function deltaLine(j) {
  const m = (j && j.comparison && j.comparison.metrics) || {};
  const parts = METRIC_COLS.slice(0, 4).filter(([k]) => m[k] && m[k].deltaPct != null).map(([k, h]) => {
    const r = m[k];
    return `${h} <span class="dlt ${deltaClass(k, r.deltaPct, r.thresholdPct, r.verdict)}">${fmt.pct(r.deltaPct)}</span>`;
  });
  return parts.length ? `${parts.join(' · ')} ${j.verdict ? badge(j.verdict) : ''}` : '';
}
const mins = (sec) => fmt.len(sec);
// A modal in the page: resolves true on the main button (or Enter), false on Cancel, Esc or a click outside
function modal(title, body, ok = 'OK', { open, value, danger } = {}) {
  return new Promise((resolve) => {
    const w = document.createElement('div');
    w.className = 'modal-back';
    w.innerHTML = `<div class="modal" role="dialog" aria-modal="true"><h3>${title}</h3><div class="modal-body">${body}</div>
      <div class="modal-foot"><div class="modal-sum" data-sum></div><button data-no>Cancel</button><button class="${danger ? 'danger' : 'primary'}" data-yes>${ok}</button></div></div>`;
    // value(w) reads the dialog's inputs before it goes away; without it a confirmation resolves to true
    const yes = () => { const v = value ? value(w) : true; done(v); };
    const done = (v) => { document.removeEventListener('keydown', key, true); w.remove(); resolve(v); };
    const key = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); done(false); }
      if (e.key === 'Enter') { e.preventDefault(); if (!w.querySelector('[data-yes]').disabled) yes(); }
    };
    w.addEventListener('click', (e) => { if (e.target === w || e.target.closest('[data-no]')) done(false); else if (e.target.closest('[data-yes]')) yes(); });
    document.addEventListener('keydown', key, true);
    document.body.appendChild(w);
    if (open) open(w);
    w.querySelector('[data-yes]').focus();
  });
}
// The confirmation of every launch: what is queued, the Δ it re-checks, how long it takes and when it should be done
// With `choose` ([{name, checked}]) the Scenario row is a set of checkboxes, the estimate follows the choice and the
// promise resolves to the chosen names (false on cancel). With `scenarios` (names) it covers all of them.
async function confirmLaunch({ title, what, job, note, scenario, scenarios, choose, type = 'abba', jobs = 1, shas = [], extraBuilds = 0, priority = 1, ok = 'Queue' }) {
  const estimate = async (sc, n) => {
    const q = new URLSearchParams({ scenario: sc, type, jobs: n, shas: shas.filter(Boolean).join(','), extraBuilds, priority });
    const e = await api.get(`/api/estimate?${q}`).catch(() => null);
    return e ? `<dt>Takes</dt><dd>~${mins(e.totalSec)} <span class="muted">(${e.jobs > 1 ? `${e.jobs} jobs × ` : ''}${mins(e.runSec)}${e.builds ? ` + ${e.builds} build${e.builds > 1 ? 's' : ''} × ${mins(e.buildSec)}` : ''})</span></dd>
    <dt>Done</dt><dd>${etaStart(e.endAt)} <span class="muted">· starts ${e.ahead ? `after ${e.ahead} job${e.ahead > 1 ? 's' : ''} in the queue, ${etaStart(e.startAt)}` : 'right away'}</span></dd>` : '';
  };
  const picked = choose ? choose.filter((c) => c.checked).map((c) => c.name) : null;
  const est = picked ? (picked.length ? await estimate(picked[0], picked.length) : '') : await estimate(scenarios ? scenarios.join(',') : scenario, jobs);
  const dl = job && job.status === 'done' ? deltaLine(job) : '';
  const scenarioRow = choose
    ? `<div class="choose two">${choose.map((c) => `<label><input type="checkbox" name="sc" value="${esc(c.name)}" ${c.checked ? 'checked' : ''}> ${esc(scName(c.name))}${c.note ? ` <span class="muted">${esc(c.note)}</span>` : ''}</label>`).join('')}</div>`
    : scenarios ? `${scenarios.slice(0, 4).map((n) => esc(scName(n))).join(', ')}${scenarios.length > 4 ? ` <span class="muted">and ${scenarios.length - 4} more</span>` : ''} · ${esc(type)}`
    : `${esc(scName(scenario))} · ${esc(type)}`;
  const body = `<dl class="kv">
    <dt>What</dt><dd>${what || (job ? `${jobTitle(job, false)} <span class="muted">${jobRefsShort(job)}</span>` : '')}${note ? `<div class="muted">${esc(note)}</div>` : ''}</dd>
    <dt>Scenario${choose || scenarios ? 's' : ''}</dt><dd>${scenarioRow}</dd>
    ${dl ? `<dt>Now</dt><dd>${dl}${job.runs > 1 ? ` <span class="muted">×${job.runs}</span>` : ''}</dd>` : ''}
  </dl><dl class="kv est">${est}</dl>`;
  if (!choose) return modal(title, body, ok);
  const chosen = (w) => [...w.querySelectorAll('input[name=sc]:checked')].map((i) => i.value);
  return modal(title, body, ok, {
    value: chosen,
    open: (w) => {
      const yes = w.querySelector('[data-yes]');
      yes.disabled = !chosen(w).length;
      w.querySelector('.choose').addEventListener('change', async () => {
        const list = chosen(w);
        yes.disabled = !list.length;
        w.querySelector('.est').innerHTML = list.length ? await estimate(list[0], list.length) : '';
      });
    },
  });
}
// metrics that decide a job verdict, in this order (bold first rows on the job page)
const VERDICT_KEYS = ['throughput', 'p99Us', 'serverCpuUsPerOp'];
// "14:05 (in 35 min)" for a future ISO time, used by the queue ETA
const etaStart = (iso) => {
  if (!iso) return '–';
  const d = new Date(iso), left = (d - Date.now()) / 1000;
  const hm = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  const day = d.toDateString() !== new Date().toDateString() ? `${fmtDay(iso)} ` : '';
  return `${day}${hm} <span class="muted">${left <= 0 ? 'next' : `in ${fmt.len(left)}`}</span>`;
};
// how long the running job should still take, seconds: nothing once it runs over its estimate
const timeLeft = (j) => (j.startedAt && j.etaSec ? Math.max(0, Date.parse(j.startedAt) / 1000 + j.etaSec - Date.now() / 1000) : 0);
const metricHead = ([k, h]) => `<th class="num"${hint(k)}>${h}</th>`;
const metricHeads = () => METRIC_COLS.map(metricHead).join('') + '<th>Verdict</th>';

let toastTimer;
export function toast(msg, isError) {
  let t = $('.toast');
  if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); }
  t.textContent = msg;
  t.style.background = isError ? 'var(--bad)' : 'var(--fg)';
  t.style.color = isError ? '#fff' : 'var(--bg)';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.remove(), isError ? 7000 : 3500);
}
export async function guarded(fn) {
  try { return await fn(); } catch (e) { toast(e.message, true); throw e; }
}
const scenarioOptions = (list, selected) => list.map((s) => `<option value="${esc(s.name)}" ${s.name === selected ? 'selected' : ''}>${esc(scName(s.name))} (${s.warmupSec}+${s.measureSec} s)</option>`).join('');
function verdictBadge(j) {
  if (!j.verdict) return '';
  const thr = j.comparison && j.comparison.metrics && j.comparison.metrics.throughput;
  return `${badge(j.verdict)} ${thr && thr.deltaPct != null ? `<span class="muted mono">${fmt.pct(thr.deltaPct)}</span>` : ''}`;
}
// Two-line job caption: the commit subject when the job measures a branch commit, otherwise the label;
// then a short muted line "B vs parent A" / "B vs A" without repeating what the first line already says.
const jobSubjectText = (j) => (j.type === 'single' ? j.subjectA : j.subjectB);
// a segment job measures several commits at once: "B vs A (N commits)", A marked as the segment start
const segmentSize = (j) => {
  const m = /\((\d+) commits\)$/.exec(j.label || '');
  return m ? Number(m[1]) : /\(segment start\)/.test(j.refA || '') ? null : 0;
};
const isSegment = (j) => segmentSize(j) !== 0;
const isAA = (j) => j.type === 'abba' && j.shaA && j.shaA === j.shaB;
const fmtDay = (iso) => (iso ? new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: new Date(iso).getFullYear() !== new Date().getFullYear() ? 'numeric' : undefined }) : '?');
const jobTitle = (j, link = true) => {
  const n = segmentSize(j);
  const t = isAA(j) ? `A/A calibration · ${scName(j.scenario)}`
    : j.source === 'release' ? `${j.refB || 'master'} vs ${j.refA} · ${scName(j.scenario)}`
    : j.comparisonId ? `${j.label} · ${scName(j.scenario)}`
    : isSegment(j) ? `Segment of ${n || 'several'} commits, ${fmtDay(j.dateA)} – ${fmtDay(j.dateB)}` : jobSubjectText(j) || j.label;
  const body = `<span class="job-subject" title="${esc(t)}">${esc(t)}</span>`;
  return link ? `<a class="job-title" href="#/jobs/${j.id}">${body}</a>` : body;
};
// "a1b2c3d4e5 IGNITE-123 Subject…" with the full sha in the tooltip; the ref is shown only when it is not just the sha
const refLine = (ref, sha, subject) => {
  const showRef = ref && !/^[0-9a-f]{7,40}$/i.test(ref) && !(sha || '').startsWith(ref);
  return `<a class="mono" href="#/commits/${esc(sha || '')}" title="${esc(sha || '')}">${fmt.sha(sha)}</a>`
    + (subject ? ` <span class="job-subject" title="${esc(subject)}">${subjectHtml(subject)}</span>` : '')
    + (showRef ? ` <span class="muted">(${esc(ref)})</span>` : '');
};
const jobRefsShort = (j) => {
  if (isAA(j)) return `${esc(j.refA || '')} <span class="mono">${fmt.sha(j.shaA)}</span> against itself: every Δ is stand noise`;
  if (j.source === 'release') return `<span class="mono">${fmt.sha(j.shaB)}</span> vs ${esc(j.refA)} <span class="mono">${fmt.sha(j.shaA)}</span>`;
  // the last commit's subject says nothing about a segment: only in the tooltip
  if (isSegment(j)) return `<span class="mono" title="${esc(j.subjectB ? `last commit: ${j.subjectB}` : '')}">${fmt.sha(j.shaA)}..${fmt.sha(j.shaB)}</span>`;
  const vsParent = / vs parent$/.test(j.label || '') || /\(parent\)/.test(j.refA || '');
  if (j.type === 'single') return `<span class="mono">${fmt.sha(j.shaA)}</span>`;
  return `<span class="mono">${fmt.sha(j.shaB)}</span> vs ${vsParent ? 'parent ' : ''}<span class="mono">${fmt.sha(j.shaA)}</span>`;
};

// ------------------------------------------------------------------ views
export const views = {}; // exported for the UI smoke test (ui-smoke.mjs)

views.queue = {
  poll: 10000,
  live: true,
  async render() {
    this.historyLimit = this.historyLimit || 10;
    const [active, history, health, shownBisects] = await Promise.all([
      api.get('/api/jobs?filter=active&limit=101'), api.get(`/api/jobs?filter=${this.noSteps ? 'history-no-steps' : 'history'}&limit=${this.historyLimit + 1}`),
      api.get('/api/health'), api.get('/api/bisects?active=true&limit=20').catch(() => []),
    ]);
    const running = active.find((j) => j.status === 'running');
    const queued = active.filter((j) => j.status === 'queued'), paused = health.worker.paused;
    const log = running ? await api.get(`/api/jobs/${running.id}/log?lines=25`) : null;
    return `
      <h1>Queue</h1>
      ${paused ? `<div class="card notice">${running ? `Closing for maintenance: ${jobLink(running)} finishes, no new jobs start.` : 'Closed for maintenance: queued jobs wait.'} <a href="#/settings">Settings</a></div>` : ''}
      <div class="card now">${running ? `
        <div class="now-head">
          <div class="now-title">${jobLink(running)} ${jobTitle(running)}${parentTag(running)}</div>
          <div class="now-tools"><button class="small" data-log-toggle>Log</button><button class="small" data-cancel="${running.id}">Cancel</button></div>
        </div>
        <div class="progress"><div style="width:${running.progress}%"></div></div>
        <div class="now-meta muted"><span>${esc(scName(running.scenario))} · ${jobRefsShort(running)}</span><span>${esc(running.stage || '')} · started ${fmt.ago(running.startedAt)}${running.cancelRequested ? ' · <span class="error">cancelling…</span>' : ''}</span><b>${running.progress}%${timeLeft(running) ? ` · ${fmt.len(timeLeft(running))} left` : ''}</b></div>
        <pre class="log" ${this.logOpen ? '' : 'hidden'}>${esc(log && log.text ? log.text : '(no log yet)')}</pre>` : '<div class="muted">Nothing is running.</div>'}
      </div>
      <h2>Waiting (${health.jobs.queued || 0})${(health.jobs.queued || 0) > queued.length ? ` <span class="muted">first ${queued.length} shown</span>` : ''}</h2>
      <table class="commits runs-table"><thead><tr><th>#</th><th>Job</th><th>Scenario</th><th>Source</th><th class="num">Priority</th><th title="estimated from the median duration of recent jobs of the same scenario plus builds">Starts</th><th class="num">Takes</th><th></th></tr></thead>
      <tbody>${queued.map((j) => `<tr>
        <td>${jobLink(j)}</td><td class="subject">${jobTitle(j)}${parentTag(j)}<div class="muted">${jobRefsShort(j)}</div></td><td>${esc(scName(j.scenario))}</td><td>${jobSource(j)}</td>
        <td class="num">${j.priority} <button class="small" data-prio="${j.id}" data-delta="1" title="raise">▲</button><button class="small" data-prio="${j.id}" data-delta="-1" title="lower">▼</button></td>
        <td style="white-space:nowrap">${health.worker.paused ? '<span class="muted">paused</span>' : etaStart(j.etaStartAt)}</td><td class="num muted">${j.etaSec != null ? `~${mins(j.etaSec)}` : '–'}</td>
        <td><button class="small" data-cancel="${j.id}">Cancel</button></td></tr>`).join('') || '<tr><td colspan="8" class="muted">empty</td></tr>'}</tbody></table>
      ${health.queueDrainsAt && !health.worker.paused ? `<div class="muted">${(health.jobs.queued || 0) > queued.length ? `…and ${(health.jobs.queued || 0) - queued.length} more. ` : ''}What waits now ends at about ${etaStart(health.queueDrainsAt)}${health.bisectsDoneAt ? `; active bisects keep adding steps until about ${etaStart(health.bisectsDoneAt)}` : ''}.</div>` : ''}
      ${shownBisects.length ? `<h2>Active bisects <span class="muted">a step at a time in the queue · <a href="#/bisects">all bisects</a></span></h2>
      <table><thead><tr><th>#</th><th>Scenario</th><th>Metric</th><th>Status</th><th>Now</th><th></th></tr></thead>
      <tbody>${shownBisects.map((b) => `<tr>
        <td><a href="#/bisects/${b.id}">#${b.id}</a></td><td>${esc(scName(b.scenario))}</td><td>${bisectWhat(b)}</td>
        <td>${badge(b.status)}</td><td class="job-cell">${bisectNow(b)}</td><td style="white-space:nowrap">${bisectActions(b)}</td></tr>`).join('')}</tbody></table>` : ''}
      <h2 class="with-tools">History <span class="tools"><label class="inline-check"><input type="checkbox" id="no-steps" ${this.noSteps ? 'checked' : ''}> without bisect steps</label></span></h2>
      ${historyTable(history.slice(0, this.historyLimit))}
      ${history.length > this.historyLimit ? '<button class="att-more" data-history-more>Show more</button>' : ''}`;
  },
  bind(root) {
    bindJobActions(root, this);
    root.addEventListener('click', (e) => {
      if (e.target.closest('button[data-history-more]')) { this.historyLimit += 30; refresh(); }
      if (e.target.closest('[data-log-toggle]')) { this.logOpen = !this.logOpen; $('pre.log', root).hidden = !this.logOpen; }
    });
    $('#no-steps', root).addEventListener('change', (e) => { this.noSteps = e.target.checked; refresh(); });
  },
};

const prLink = (ref) => { const m = /^(?:pr\/|pull\/|#)(\d+)\b/i.exec(ref || ''); return m && repoWeb ? ` <a href="${esc(repoWeb)}/pull/${m[1]}" target="_blank" rel="noopener" title="open on GitHub">PR ${m[1]} ↗</a>` : ''; };

const jobSource = (j) => `${esc(j.source)}${j.type === 'abba' ? '' : ` · ${esc(j.type)}`}`;
function historyTable(history) {
  return `<table class="commits runs-table"><thead><tr><th>#</th><th>Job</th><th>Scenario</th><th>Source</th><th>Result</th><th>Finished</th><th class="num">Took</th><th></th></tr></thead>
    <tbody>${history.map((j) => `<tr>
      <td>${jobLink(j)}</td><td class="subject">${jobTitle(j)}${prLink(j.refB)}${parentTag(j)}<div class="muted">${jobRefsShort(j)}</div>${j.error ? `<div class="error one-line" title="${esc(j.error)}">${esc(j.error)}</div>` : ''}</td>
      <td>${esc(scName(j.scenario))}</td><td>${jobSource(j)}</td><td>${j.status === 'done' ? verdictBadge(j) : badge(j.status)}</td>
      <td class="muted">${fmt.ago(j.finishedAt)}</td><td class="num">${fmt.dur(j.startedAt, j.finishedAt)}</td>
      <td><button class="small" data-retry="${j.id}">Retry</button></td></tr>`).join('') || '<tr><td colspan="8" class="muted">nothing yet</td></tr>'}</tbody></table>`;
}

function bindJobActions(root, view) {
  root.addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.cancel) guarded(async () => {
      if (!(await modal(`Cancel job #${b.dataset.cancel}?`, '<div class="muted">A running job is stopped at once.</div>', 'Cancel job', { danger: true }))) return;
      await api.post(`/api/jobs/${b.dataset.cancel}/cancel`); toast(`job #${b.dataset.cancel} cancelled`); refresh(); });
    if (b.dataset.retry) guarded(() => moreRun(b.dataset.retry, true));
    if (b.dataset.bisect) guarded(() => startBisect(b.dataset.bisect, b.dataset.metric || (view && view.metric)));
    if (b.dataset.bisectCancel) guarded(() => cancelBisect(b.dataset.bisectCancel));
    if (b.dataset.bisectResume) guarded(() => resumeBisect(b.dataset.bisectResume));
    if (b.dataset.bisectDeep) guarded(() => deepBisect(b.dataset.bisectDeep));
    if (b.dataset.bisectDelete) guarded(() => deleteBisect(b.dataset.bisectDelete));
    if (b.dataset.prio) guarded(async () => {
      const cur = await api.get(`/api/jobs/${b.dataset.prio}`);
      await api.post(`/api/jobs/${b.dataset.prio}/priority`, { priority: cur.priority + Number(b.dataset.delta) });
      refresh();
    });
  });
}

// A running job page polls only the job row and the log tail, and slowly: the controller shares the machine with the
// measured JVMs, so series, profiles and file listings are loaded once the job has finished.
// Where the JVMs ran and what each server did, from the first measured phase: only a remote stand has anything to tell
function layoutSection(r) {
  if (!r.loaderHost) return '';
  const host = (name) => { const f = (r.hosts || {})[name]; return f ? `${f.cpus} CPUs, ${fmt.num(f.memMb / 1024, 0)} GB` : ''; };
  return `<h2>Layout <span class="muted">phase ${esc(r.phase)}</span></h2>
    <table><thead><tr><th>JVM</th><th>Host</th><th>Machine</th><th class="num">CPU %</th><th class="num">machine CPU %</th><th class="num">GC pause ms</th><th class="num">heap after GC, MB</th></tr></thead>
    <tbody>${r.nodes.map((n) => `<tr><td>node-${n.index}</td><td class="mono">${esc(n.host)}</td><td class="muted">${esc(host(n.host))}</td>
      <td class="num">${n.cpu ? fmt.num(n.cpu.serverJvmPct, 1) : '–'}</td><td class="num">${n.cpu ? fmt.num(n.cpu.machinePct, 1) : '–'}</td>
      <td class="num">${n.gc ? fmt.num(n.gc.totalPauseMs) : '–'}</td><td class="num">${n.heap ? fmt.num(n.heap.afterGcMbMean) : '–'}</td></tr>`).join('')}
      <tr><td>loader</td><td class="mono">${esc(r.loaderHost)}</td><td class="muted">${esc(host(r.loaderHost))}</td>
      <td class="num">${r.cpu ? fmt.num(r.cpu.loaderJvmPct, 1) : '–'}</td><td class="num">–</td><td class="num">–</td><td class="num">–</td></tr></tbody></table>`;
}
// the metrics of the job page that stay in sight, verdict metrics first; the others unfold
const JOB_MAIN = [...VERDICT_KEYS, 'allocKbPerOp'];
const mainRank = (k) => (JOB_MAIN.includes(k) ? JOB_MAIN.indexOf(k) : JOB_MAIN.length);
// JobService.BROKEN_SPREAD: phases of one side this many noise floors apart; the one further from the other side is it
const BROKEN_SPREAD = 2.5;
function brokenPhases(r) {
  const middle = (names) => names.reduce((x, p) => x + r.values[p], 0) / names.length;
  return [['A1', 'A2', 'B1', 'B2', r.spreadAPct], ['B1', 'B2', 'A1', 'A2', r.spreadBPct]].filter(([, , , , spread]) => spread > BROKEN_SPREAD * r.noisePct)
    .map(([x, y, p, q]) => { const mid = middle([p, q]); return Math.abs(r.values[x] - mid) > Math.abs(r.values[y] - mid) ? x : y; });
}
views.job = {
  poll: 15000,
  live() { return this.active; },
  async render(params) {
    const j = await api.get(`/api/jobs/${params.id}`);
    this.active = j.status === 'running' || j.status === 'queued';
    const running = j.status === 'running';
    const log = running || j.status === 'failed' ? await api.get(`/api/jobs/${j.id}/log?lines=40`).catch(() => null) : null;
    const files = this.active ? j.runs.map(() => []) : await Promise.all(j.runs.map((r) => api.get(`/api/runs/${r.id}/files`).catch(() => [])));
    const prof = !this.active && j.runs.some((r) => r.status === 'ok') ? await api.get(`/api/jobs/${j.id}/profile?limit=30`).catch(() => null) : null;
    const measured = this.active ? null : j.runs.find((r) => r.status === 'ok');
    const result = measured ? await api.get(`/api/runs/${measured.id}/result`).catch(() => null) : null;
    const cmp = j.comparison;
    const phases = cmp ? cmp.phases : [];
    const metrics = cmp ? Object.entries(cmp.metrics).sort(([a], [b]) => mainRank(a) - mainRank(b)) : [];
    const more = metrics.filter(([k]) => !JOB_MAIN.includes(k)).length;
    const broken = cmp && cmp.metrics.throughput ? brokenPhases(cmp.metrics.throughput) : [];
    return `
      <h1>${jobTitle(j, false)} ${badge(j.status)} ${j.verdict ? badge(j.verdict) : ''}
        ${j.source !== 'compare' && j.shaB ? ` <a class="small-link" href="#/commits/${j.shaB}">commit page →</a>` : ''}</h1>
      <div class="muted" style="margin:-6px 0 12px">job #${j.id}${parentTag(j)}</div>
      <div class="row">
        <div class="card"><dl class="kv">
          <dt>Type</dt><dd>${esc(j.type)} · ${esc(j.source)} · scenario <b>${esc(scName(j.scenario))}</b>${j.scenarioHash ? ` <span class="muted mono">${esc(j.scenarioHash.slice(0, 12))}</span>` : ''}</dd>
          <dt>Measures</dt><dd>${measures(j)}</dd>
          <dt>A (baseline)</dt><dd>${refLine(j.refA, j.shaA, j.subjectA)}</dd>
          ${j.type === 'abba' ? `<dt>B (target)</dt><dd>${refLine(j.refB, j.shaB, j.subjectB)}</dd>` : ''}
          <dt>Timing</dt><dd>created ${fmt.date(j.createdAt)} · started ${fmt.date(j.startedAt)} · finished ${fmt.date(j.finishedAt)} · took ${fmt.dur(j.startedAt, j.finishedAt)}</dd>
          ${running ? `<dt>Stage</dt><dd>${esc(j.stage)} · ${j.progress}%</dd>` : ''}
          ${j.error ? `<dt>Error</dt><dd class="error">${esc(j.error)}</dd>` : ''}
        </dl>
        <div class="actions-inline" style="margin-top:10px">
          ${jobActions(j)}
        </div></div>
      </div>
      ${running ? `<div class="progress"><div style="width:${j.progress}%"></div></div>` : ''}
      ${j.segment ? segmentSection(j) : ''}
      ${log && log.text ? `<h2>Log tail <span class="muted mono">${esc(log.file || '')}</span></h2><pre class="log">${esc(log.text)}</pre>` : ''}
      ${cmp ? `
        <h2>Comparison <span class="muted">${term('floor', 'noise floor')}: ${esc(cmp.noiseSource)}${cmp.calibrations ? ` (${cmp.calibrations} calibrations)` : ''}</span></h2>
        <table><thead><tr><th>Metric</th>${phases.map((p) => `<th class="num">${esc(p)}</th>`).join('')}<th class="num">A</th><th class="num">B</th><th class="num">Δ</th><th class="num"${hint('CI')}>95% CI</th><th class="num"${hint('threshold')}>Threshold</th><th>Verdict</th></tr></thead>
        <tbody>${metrics.map(([k, r]) => `<tr class="${VERDICT_KEYS.includes(k) ? 'verdict-metric' : ''}${JOB_MAIN.includes(k) ? '' : ' metric-more'}" ${JOB_MAIN.includes(k) || this.allMetrics ? '' : 'hidden'}>
          <td>${VERDICT_KEYS.includes(k) ? `<b>${esc(r.title)}</b>` : esc(r.title)} <span class="muted">${esc(r.unit)}</span></td>
          ${phases.map((p) => `<td class="num${k === 'throughput' && broken.includes(p) ? ' phase-broken' : ''}">${fmt.num(r.values[p], metricDigits(k))}</td>`).join('')}
          <td class="num">${fmt.num(r.meanA, metricDigits(k))}</td><td class="num">${fmt.num(r.meanB, metricDigits(k))}</td>
          <td class="num">${VERDICT_KEYS.includes(k) ? deltaCell(r) : `<span class="dlt ${deltaClass(k, r.deltaPct, r.thresholdPct, r.verdict)}">${fmt.pct(r.deltaPct)}</span>`}</td>
          <td class="num muted">${r.ciLow != null ? `${fmt.pct(r.ciLow)} … ${fmt.pct(r.ciHigh)}` : '–'}</td>
          <td class="num muted">±${fmt.num(r.thresholdPct, 1)}%${r.spreadAPct > r.noisePct || r.spreadBPct > r.noisePct ? ' <span title="spread between repeats exceeds the noise floor">(spread)</span>' : ''}</td>
          <td>${VERDICT_KEYS.includes(k) ? badge(r.verdict) : ''}</td></tr>`).join('')}</tbody></table>
        ${more ? `<button class="att-more" data-metrics-more>${this.allMetrics ? 'Fewer metrics' : `${plural(more, 'more metric')}`}</button>` : ''}
        ${broken.length ? `<div class="card notice" style="margin-top:6px">Phase ${broken.join(', ')} is far from its twin on ops/s: this run says little, a deep search leaves it out and measures the pair again.</div>` : ''}
        <div class="muted">${esc(cmp.note || '')}</div>` : ''}
      <h2>Per-second series <span class="muted">${j.type === 'abba' ? 'A phases blue, B phases orange, repeats dashed' : ''}</span></h2>
      ${this.active ? '<div class="muted">Shown when the job finishes: the page does not stream metrics while it runs, so as not to load the stand.</div>'
        : `<div id="charts" class="chart-grid${this.allCharts ? ' show-all' : ''}"><div class="muted">loading charts…</div></div>
           <button class="att-more" data-charts-more>${this.allCharts ? 'Fewer charts' : 'All charts'}</button>`}
      ${prof && prof.available ? profileSection(prof.diff, j.type === 'abba', this.profMode || 'self') : ''}
      ${result ? layoutSection(result) : ''}
      <h2>Phases</h2>
      <table><thead><tr><th>Phase</th><th>Commit</th><th>Status</th><th>Ignite</th><th class="num">ops/s</th><th class="num">p50 µs</th><th class="num">p99 µs</th><th class="num">GC %</th><th class="num">alloc MB/s</th><th class="num">srv CPU %</th><th class="num">wall</th><th>Files</th></tr></thead>
      <tbody>${j.runs.map((r, i) => `<tr>
        <td><b>${esc(r.phase)}</b></td><td class="mono">${fmt.sha(r.sha)}</td><td>${badge(r.status)}${r.error ? `<div class="error">${esc(r.error)}</div>` : ''}</td>
        <td>${esc(r.igniteVersion || '')}</td><td class="num">${fmt.num(r.throughput)}</td><td class="num">${fmt.num(r.p50Us)}</td><td class="num">${fmt.num(r.p99Us)}</td>
        <td class="num">${fmt.num(r.gcPausePct, 2)}</td><td class="num">${fmt.num(r.allocMbPerSec, 1)}</td><td class="num">${fmt.num(r.serverCpuPct, 1)}</td>
        <td class="num">${r.wallSec != null ? Math.round(r.wallSec) + ' s' : '–'}</td>
        <td>${(files[i] || []).map((f) => `<a href="/api/runs/${r.id}/files/${esc(f.name)}" target="_blank">${esc(f.name)}</a>`).join(' · ')}</td></tr>`).join('') || '<tr><td colspan="12" class="muted">no phases yet</td></tr>'}</tbody></table>`;
  },
  bind(root) {
    bindJobActions(root, this);
    const sel = $('#prof-mode', root);
    if (sel) sel.addEventListener('change', (e) => { this.profMode = e.target.value; lastHtml = null; refresh(); });
    root.addEventListener('click', (e) => {
      const b = e.target.closest('button[data-more]');
      if (b) guarded(() => moreRun(b.dataset.more));
      const sp = e.target.closest('button[data-split]');
      if (sp) guarded(() => splitJob(sp.dataset.split, sp.dataset.mode));
      const m = e.target.closest('button[data-metrics-more]');
      if (m) {
        this.allMetrics = !this.allMetrics;
        root.querySelectorAll('tr.metric-more').forEach((t) => { t.hidden = !this.allMetrics; });
        m.textContent = this.allMetrics ? 'Fewer metrics' : plural(root.querySelectorAll('tr.metric-more').length, 'more metric');
      }
      const c = e.target.closest('button[data-charts-more]');
      if (c) {
        this.allCharts = !this.allCharts;
        $('#charts').classList.toggle('show-all', this.allCharts);
        c.textContent = this.allCharts ? 'Fewer charts' : 'All charts';
      }
    });
  },
};

// Every bisection of the stand: the active ones first, a chip narrows the list and stays in the address
const BISECT_SHOW = [['all', 'All', () => true], ['active', 'Active', (b) => bisectActive(b)], ['deep', 'Needs a deep search', (b) => canGoDeep(b) && b.status === 'inconclusive'],
  ['found', 'Found', (b) => b.status === 'found'], ['none', 'No single commit', (b) => b.status === 'spread' || b.status === 'lost']];
views.bisects = {
  poll: 15000,
  live() { return this.active; },
  async render() {
    const groups = latestBisects(await api.get('/api/bisects?limit=500'));
    this.active = groups.some((g) => g.b.status === 'running');
    const show = new URLSearchParams(location.hash.split('?')[1] || '').get('show') || 'all';
    const [, , fits] = BISECT_SHOW.find(([k]) => k === show) || BISECT_SHOW[0];
    const rows = groups.filter((g) => fits(g.b)).sort((x, y) => bisectActive(y.b) - bisectActive(x.b) || y.b.id - x.b.id);
    return `
      <h1>Bisects <span class="muted">which commits moved a metric</span></h1>
      <div class="chips">${BISECT_SHOW.map(([k, name, f]) => `<a class="chip ${k === show ? 'on' : ''}" href="#/bisects${k === 'all' ? '' : `?show=${k}`}">${name} <span class="muted">${groups.filter((g) => f(g.b)).length}</span></a>`).join('')}</div>
      <table class="commits"><thead><tr><th>#</th><th>Scenario</th><th>Metric</th><th>Segment</th><th>Status</th><th>Result</th><th>Started</th><th></th></tr></thead>
      <tbody>${rows.map((g) => { const b = g.b; return `<tr><td style="white-space:nowrap"><a href="#/bisects/${b.id}">#${b.id}</a>${earlierMark(g)}</td><td>${esc(scName(b.scenario))}</td><td>${bisectWhat(b)}</td>
        <td class="mono" style="white-space:nowrap"><a href="#/jobs/${b.rootJob}" title="the segment it started from">${fmt.sha(b.shaA)}..${fmt.sha(b.shaB)}</a>${b.comparisonId ? `<div><a class="small-link" href="#/comparisons/${b.comparisonId}">comparison #${b.comparisonId}</a></div>` : ''}</td>
        <td>${badge(b.status)}</td><td class="bisect-result">${bisectResult(b)}</td><td class="muted" style="white-space:nowrap">${fmt.date(b.createdAt)}</td><td class="row-action">${bisectActions(b)}</td></tr>`; }).join('')
        || '<tr><td colspan="8" class="muted">nothing here</td></tr>'}</tbody></table>`;
  },
  bind(root) {
    bindJobActions(root, this);
  },
};

// Bisection page: where it stands, what every step measured and the decision taken from it
const ROLE_NAMES = { left: 'left half', right: 'right half', repeat: 'the range again', leaf: 'the commit', confirm: 'the commit again', root: 'the segment again' };
// The search on a map of the segment, in the colours of Timeline: the whole segment on top, every row below half as wide.
// A repeat replaces the block of its pair; what is still suspected and what was found run through all the rows.
const SEARCH_ROW = 16, SEARCH_GAP = 3;
function searchMap(b, zoom) {
  const s = b.state, positions = b.positions || {}, pos = (sha) => positions[sha];
  if (pos(b.shaB) == null) return '';
  const placed = (r) => pos(r.a) != null && pos(r.b) != null;
  const hi = pos(b.shaB), [from, to] = zoom || [0, hi];
  const X = (v) => ((Math.max(from, Math.min(to, v)) - from) / (to - from)) * 100;
  const place = (a, z) => `left:${X(a)}%;width:${X(z) - X(a)}%`;
  const inView = (a, z) => z > from && a < to;
  const blocks = new Map([[`${b.shaA}..${b.shaB}`, { a: b.shaA, b: b.shaB, result: s.root, jobId: b.rootJob, what: 'the segment' }]]);
  s.steps.forEach((x) => blocks.set(`${x.a}..${x.b}`, { ...x, what: `step ${x.n}: ${ROLE_NAMES[x.role] || x.role}` }));
  if (b.waiting && s.waitA) {
    const key = `${s.waitA}..${s.waitB}`;
    blocks.set(key, { ...blocks.get(key), a: s.waitA, b: s.waitB, jobId: b.waiting.id, pending: b.waiting.status, what: `step ${s.steps.length + 1}: ${ROLE_NAMES[s.waitRole] || s.waitRole || ''}` });
  }
  const items = [...blocks.values()].filter(placed).map((x) => ({ ...x, lo: pos(x.a), hi: pos(x.b) }));
  items.forEach((it, i) => {
    const parents = items.slice(0, i).filter((p) => p.lo <= it.lo && it.hi <= p.hi);
    it.row = Math.max(Math.round(Math.log2(hi / Math.max(1, it.hi - it.lo))), ...parents.map((p) => p.row + 1));
  });
  const rows = [...new Set(items.map((it) => it.row))].sort((x, y) => x - y);
  const rowY = (row) => rows.indexOf(row) * (SEARCH_ROW + SEARCH_GAP) + SEARCH_GAP, H = rowY(rows[rows.length - 1]) + SEARCH_ROW + SEARCH_GAP;
  const labels = rows.map((row) => `<div style="top:${rowY(row) + 1}px">${plural(Math.max(...items.filter((it) => it.row === row).map((it) => it.hi - it.lo)), 'commit')}</div>`).join('');
  const block = (it) => {
    const r = it.result, level = !r && it.pending ? 'pending' : mapLevel(b.metric, r && r.deltaPct, r && r.thresholdPct, r && r.verdict);
    const subject = it.hi - it.lo === 1 && b.commits[it.b] ? b.commits[it.b].subject : '';
    const tip = [it.what, `${plural(it.hi - it.lo, 'commit')}: ${fmt.sha(it.a)}..${fmt.sha(it.b)}`, subject,
      r ? `Δ ${metricName(b)} ${fmt.pct(r.deltaPct)}, threshold ±${fmt.num(r.thresholdPct, 1)} %, ${r.verdict}` : '',
      it.pending ? `job ${it.pending}` : '', it.decision].filter(Boolean).join('\n');
    const picked = it.hi - it.lo === 1 && foundOf(b).some((f) => f.b === it.b) ? ' picked' : '';
    return `<a class="search-block ${level}${picked}${it.pending === 'running' ? ' running' : ''}" href="#/jobs/${it.jobId}" style="${place(it.lo, it.hi)};top:${rowY(it.row)}px" title="${esc(tip)}">${r ? `<span>${fmt.pct(r.deltaPct)}${r.runs > 1 ? ` ×${r.runs}` : ''}</span>` : ''}</a>`;
  };
  const band = (cls, r, tip) => (placed(r) && inView(pos(r.a), pos(r.b)) ? `<i class="search-band ${cls}" style="${place(pos(r.a), pos(r.b))}" title="${esc(tip)}"></i>` : '');
  const bands = s.open.map((r) => band('open', r, `still suspected: ${plural(r.commits, 'commit')}`))
    .concat(s.findings.filter((f) => f.kind === 'spread').map((f) => band('spread', f, `spread over small changes: ${plural(f.commits, 'commit')}`))).join('');
  const found = foundOf(b).filter((f) => placed(f) && inView(pos(f.a), pos(f.b))).map((f) => `<a class="search-found" href="#/commits/${esc(f.b)}" style="left:${X(pos(f.b) - 0.5)}%"
    title="${esc(`found ${fmt.sha(f.b)} ${b.commits[f.b] ? b.commits[f.b].subject : ''}\nΔ ${fmt.pct(f.result.deltaPct)}`)}"></a>`).join('');
  const ticks = [0, 1, 2, 3, 4].map((k) => {
    const v = Math.round(from + ((to - from) * k) / 4), sha = Object.keys(positions).find((x) => positions[x] === v);
    return `<span>${v}${sha && b.commits[sha] ? ` · ${fmtDay(b.commits[sha].committedAt)}` : ''}</span>`;
  }).join('');
  const legend = [['noise', 'noise', 'noise'], ['over', 'beyond the threshold', 'over'], ['bad', 'regression', 'regression'], ['good', 'improvement', 'improvement'],
    ...(b.waiting ? [['running', 'measured now']] : []), ...(s.open.length ? [['band', 'still suspected', 'band']] : []),
    ...(s.findings.some((f) => f.kind === 'spread') ? [['spread', 'spread', 'spread']] : []), ...(found ? [['found', 'found', 'found']] : [])];
  return `<div class="map-legend">${legend.map(([c, name, help]) => `<span><i class="sw ${c}"></i>${help ? term(help, name) : name}</span>`).join('')}</div>
    <div class="map-wrap"><div class="map-lanes" style="height:${H}px">${labels}</div>
      <div id="search-map" class="search-map" data-from="${from}" data-to="${to}" style="height:${H}px">${bands}${found}${items.filter((it) => inView(it.lo, it.hi)).map(block).join('')}<i id="search-brush" class="search-brush" hidden></i></div></div>
    <div class="map-ticks">${ticks}</div>`;
}
views.bisect = {
  poll: 15000,
  live() { return this.active; },
  async render(params) {
    const b = await api.get(`/api/bisects/${params.id}`);
    this.active = b.status === 'running';
    if (this.id !== params.id) { this.id = params.id; this.zoom = null; }
    const s = b.state, w = b.waiting, map = searchMap(b, this.zoom), better = HIGHER_BETTER.has(b.metric) ? 'Higher is better.' : 'Lower is better.';
    const range = (a, z, n) => `<a class="mono" href="#/commits/${esc(a)}">${fmt.sha(a)}</a>..<a class="mono" href="#/commits/${esc(z)}">${fmt.sha(z)}</a>${n != null ? ` <span class="muted">${n} commit${n > 1 ? 's' : ''}</span>` : ''}`;
    const size = (a, z, n) => (n === 1 ? `<a class="mono" href="#/commits/${esc(z)}">${fmt.sha(z)}</a>` : `<span title="${fmt.sha(a)}..${fmt.sha(z)}">${plural(n, 'commit')}</span>`);
    const res = (r) => (r ? `${valDelta(b.metric, null, 0, r.deltaPct, r.verdict, r.thresholdPct, true).replace(/^–\s*/, '')} <span class="muted">±${fmt.num(r.thresholdPct, 1)}%${r.runs > 1 ? ` · ×${r.runs}` : ''}${r.dropped ? ` · ${r.dropped} left out` : ''}</span>` : '–');
    const stepRow = (x) => `<tr>
      <td class="num">${x.n}</td><td>${esc(ROLE_NAMES[x.role] || x.role)}</td><td>${size(x.a, x.b, x.commits)}</td>
      <td>${x.reused ? `<span class="muted">reused</span> <a href="#/jobs/${x.jobId}">#${x.jobId}</a>` : `<a href="#/jobs/${x.jobId}">#${x.jobId}</a>`}</td>
      <td class="num">${res(x.result)}</td><td>${x.result ? badge(x.result.verdict) : ''}</td><td>${esc(x.decision || '')}</td></tr>`;
    // a deep search measures a pair again and again: a row per pair, with what every run of it showed
    const pairs = new Map();
    s.steps.slice(s.deep ? s.deepFrom : s.steps.length).forEach((x) => {
      const p = pairs.get(`${x.a}..${x.b}`) || { first: x, jobs: new Set() };
      p.last = x;
      [x.jobId, ...((x.result && x.result.jobIds) || [])].forEach((id) => p.jobs.add(id));
      pairs.set(`${x.a}..${x.b}`, p);
    });
    const pairRow = ({ first, last, jobs }) => `<tr>
      <td class="num">${first.n === last.n ? last.n : `${first.n}–${last.n}`}</td><td>${esc(ROLE_NAMES[first.role] || first.role)}</td><td>${size(first.a, first.b, first.commits)}</td>
      <td>${[...jobs].sort((x, y) => x - y).map((id) => `<a href="#/jobs/${id}">#${id}</a>`).join(' ')}</td>
      <td class="num">${res(last.result)}${((last.result || {}).deltas || []).length > 1 ? `<div class="muted small-text">${last.result.deltas.map((d) => fmt.pct(d)).join(' ')}</div>` : ''}</td>
      <td>${last.result ? badge(last.result.verdict) : ''}</td><td>${esc(last.decision || '')}</td></tr>`;
    const rows = s.deep ? [...pairs.values()].map(pairRow).join('') : s.steps.map(stepRow).join('');
    const quick = s.deep ? s.steps.slice(0, s.deepFrom).map(stepRow).join('') : '';
    const stepsHead = `<thead><tr><th class="num">#</th><th>Measured</th><th>Range</th><th>Job</th><th class="num">Δ ${esc(METRIC_NAMES[b.metric] || b.metric)}</th><th>Verdict</th><th>Decision</th></tr></thead>`;
    const waitRow = w ? `<tr class="pending"><td class="num">${s.steps.length + 1}</td><td>${esc(ROLE_NAMES[s.waitRole] || s.waitRole || '')}</td>
      <td>${range(s.waitA, s.waitB)}</td><td>${jobLink(w)} ${badge(w.status)}</td>
      <td colspan="3" class="muted">${w.status === 'running' ? `${esc(w.stage || '')} · ${w.progress || 0}%` : 'in the queue'}</td></tr>` : '';
    const stoppedRow = !w && b.status === 'stopped' && s.waitRole ? `<tr class="pending"><td class="num">${s.steps.length + 1}</td><td>${esc(ROLE_NAMES[s.waitRole] || s.waitRole)}</td>
      <td>${range(s.waitA, s.waitB)}</td><td colspan="4" class="error">not measured: ${esc(b.reason || '')}</td></tr>` : '';
    return `
      <h1>Bisect #${b.id}: ${metricName(b)} ${badge(b.status)} ${b.comparisonId ? `<a class="small-link" href="#/comparisons/${b.comparisonId}">comparison #${b.comparisonId} →</a>`
        : `<a class="small-link" href="#/timeline?scenario=${encodeURIComponent(b.scenario)}&metric=${encodeURIComponent(b.metric)}&job=${b.rootJob}">open on Timeline →</a>`}</h1>
      <div class="muted" style="margin:-6px 0 12px">${esc(scName(b.scenario))} · started ${fmt.date(b.createdAt)}${b.finishedAt ? ` · ended ${fmt.date(b.finishedAt)}` : ''}</div>
      <div class="row"><div class="card" style="flex:1 1 520px"><dl class="kv">
        <dt>Metric</dt><dd><b>${metricName(b)}</b> ${badge(b.direction)} <span class="muted">${esc(HELP[b.metric] || '')} ${better} Every step and the verdicts below are about it alone.</span></dd>
        <dt>Segment</dt><dd>${range(b.shaA, b.shaB)} · <a href="#/jobs/${b.rootJob}">job #${b.rootJob}</a> · ${res(s.root)}</dd>
        ${(b.status === 'running' || b.status === 'stopped') && s.open.length ? `<dt>Searching</dt><dd>${s.open.map((r, i) => `<div>${range(r.a, r.b, r.commits)}
          <span class="muted">${r.cur ? `Δ ${fmt.pct(r.cur.deltaPct)}` : `≈ ${fmt.pct(r.est)} (inferred)`}${i === 0 ? ' · now' : ''}</span></div>`).join('')}
          ${b.stepsLeft ? `<div class="muted">≈ ${plural(b.stepsLeft, 'step')} left${b.etaSec ? `, ~${mins(b.etaSec)}` : ''}</div>` : ''}</dd>` : ''}
        ${b.reason ? `<dt>${b.status === 'stopped' ? 'Stopped' : 'Result'}</dt><dd class="${b.status === 'stopped' ? 'error' : ''}">${esc(b.reason)}</dd>` : ''}
        <dt>Priority</dt><dd>${b.priority}${b.priority > 1 ? ' <span class="muted">(ahead of ordinary manual jobs)</span>' : ''}</dd>
        ${s.deep ? `<dt>Search</dt><dd>${term('deep', 'deep')} <span class="muted">from step ${s.deepFrom + 1}: half of the noise floor, ±${fmt.num(s.floorPct / 2, 1)} %, counts here</span></dd>` : ''}
      </dl>
      <div class="actions-inline" style="margin-top:10px">${bisectActions(b)}${bisectActive(b) ? '' : ` <button class="small" data-bisect-delete="${b.id}">Delete</button>`}</div></div></div>
      ${map ? `<h2>Search <span class="muted">Δ ${metricName(b)} of every measured range, commits counted from the start of the segment · click a block for its job, drag to zoom</span>
        ${this.zoom ? '<button class="small" id="zoom-reset">Show all</button>' : ''}</h2><div class="card">${map}</div>` : ''}
      ${s.findings.length ? `<h2>Found <span class="muted">what each branch of the search ended on, largest first</span></h2>
      <table><thead><tr><th>Outcome</th><th>Commit or range</th><th>Subject</th><th class="num">Δ ${esc(METRIC_NAMES[b.metric] || b.metric)}</th><th>Note</th></tr></thead>
      <tbody>${[...s.findings].sort((x, y) => Math.abs((y.result || {}).deltaPct || 0) - Math.abs((x.result || {}).deltaPct || 0)).map((f) => `<tr>
        <td>${badge(f.kind)}</td><td>${f.commits <= 1 ? `<a class="mono" href="#/commits/${esc(f.b)}">${fmt.sha(f.b)}</a>` : range(f.a, f.b, f.commits)}</td>
        <td class="subject">${f.commits <= 1 ? subjectHtml(b.subjects[f.b]) : ''}</td><td class="num">${res(f.result)}</td><td class="muted">${esc(f.note || '')}</td></tr>`).join('')}</tbody></table>` : ''}
      <h2>${s.deep ? `${term('deep', 'Deep search')} <span class="muted">both halves of every level; Δ is the median of the runs, each run is listed under it</span>`
        : 'Steps <span class="muted">the left half first; the right one too when the left moved or is unclear; every moved half is searched</span>'}</h2>
      <table>${stepsHead}<tbody>${rows}${waitRow}${stoppedRow}${rows || waitRow || stoppedRow ? '' : '<tr><td colspan="7" class="muted">no steps yet</td></tr>'}</tbody></table>
      ${s.deep ? `<details data-quick ${this.quickOpen ? 'open' : ''}><summary class="muted">Quick search before it: ${plural(s.deepFrom, 'step')}</summary><table>${stepsHead}<tbody>${quick}</tbody></table></details>` : ''}
      <div class="muted small-text">A half moved when its own verdict on the metric points the same way as the segment's. A quiet left half (no verdict,
        at most ⅓ of the range's Δ) sends the search right without measuring it; otherwise the right half is measured too and every half that moved is
        searched further, the largest Δ first. A range that moves while neither half does is measured once more and reported as spread over small
        changes. A commit whose Δ is under 2× the threshold is measured once more.
        ${s.deep ? `The deep search measures both halves on every level and compares them: the one that clearly outweighs the other carries the change. A close
        call gets up to ${DEEP_RUNS} runs, Δ is their median, a run with a broken phase is left out; a commit has to hold over ${DEEP_RUNS} runs.` : ''}</div>`;
  },
  bind(root) {
    bindJobActions(root, this);
    root.querySelectorAll('details[data-quick]').forEach((d) => d.addEventListener('toggle', () => { this.quickOpen = d.open; }));
    const reset = $('#zoom-reset', root), map = $('#search-map', root);
    if (reset) reset.addEventListener('click', () => { this.zoom = null; refresh(); });
    if (!map) return;
    const from = Number(map.dataset.from), to = Number(map.dataset.to), brush = $('#search-brush', map);
    const at = (ev) => { const r = map.getBoundingClientRect(); return Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width)); };
    let start = null, dragged = false;
    map.addEventListener('mousedown', (ev) => { ev.preventDefault(); start = at(ev); dragged = false; brush.hidden = true; });
    map.addEventListener('mousemove', (ev) => {
      if (start == null) return;
      const x = at(ev), a = Math.min(start, x), z = Math.max(start, x);
      dragged = z - a > 0.01;
      brush.hidden = !dragged;
      brush.style.left = `${a * 100}%`;
      brush.style.width = `${(z - a) * 100}%`;
    });
    map.addEventListener('mouseup', (ev) => {
      if (start == null) return;
      const x = at(ev), a = Math.min(start, x), z = Math.max(start, x), lo = Math.floor(from + a * (to - from)), hi = Math.ceil(from + z * (to - from));
      start = null;
      if (dragged && hi - lo >= 2) { this.zoom = [lo, hi]; refresh(); }
    });
    map.addEventListener('click', (ev) => { if (dragged) ev.preventDefault(); }); // the end of a drag is no click on a block
  },
};

// one plain sentence: what this job compares and how many commits that covers
function measures(j) {
  if (j.type === 'single') return 'one build, single phase (no comparison)';
  if (j.type === 'probe') return 'the load of the stand: more threads until the throughput stops growing; stays out of the history';
  if (j.shaA && j.shaA === j.shaB) return 'A/A calibration: the same build on both sides, Δ is pure stand noise';
  if (j.segment) return `<b>${j.segment.count} commits</b>, ${fmtDay(j.segment.fromDate)} – ${fmtDay(j.segment.toDate)}: the total change from A to B (list below)`;
  if (/ vs parent$/.test(j.label || '') || /\(parent\)/.test(j.refA || '')) return '<b>1 commit</b>: the change B made over its parent A';
  if (j.source === 'compare') return /merged into/.test(j.refB || '') ? 'a pull request: master + PR (B) against master (A)' : 'B against A (manual comparison)';
  return 'B against A';
}

// Stand tile of the running job: what it is a part of, its scenario with a link to the job, the phase and the time left. The refs
// and the stage in full are one click away.
function workerTile(j) {
  const kind = j.bisectId ? `<a href="#/bisects/${j.bisectId}">Bisect #${j.bisectId}</a> · step ${j.bisectStep}`
    : j.source === 'calibration' ? 'A/A calibration' : j.source === 'release' ? `master vs ${esc(j.refA)}`
    : j.comparisonId ? `<a href="#/comparisons/${j.comparisonId}">Comparison #${j.comparisonId}</a>` : isSegment(j) ? `Segment of ${segmentSize(j) || 'several'} commits` : 'Commit vs parent';
  const left = timeLeft(j);
  const stage = (j.stage || '').replace(/^phase (\S+).*/, '$1').replace(/^build.*/, 'build');
  return { side: `${esc(stage)}${left ? ` · ${fmt.len(left)} left` : ''}`,
    body: `<div>${kind} · <a href="#/jobs/${j.id}" title="job #${j.id}">${esc(scName(j.scenario))}</a></div>
      <div class="progress" title="${j.progress || 0}%"><div style="width:${j.progress || 0}%"></div></div>` };
}

// A table folds what needs no eyes, and says so: a green row that names what is inside and unfolds it
const calmLine = (attr, cols, open, text, names) => `<tr class="calm-row" ${attr}><td colspan="${cols}"><span class="chev">${open ? '▾' : '▸'}</span>
  <b>✓ ${esc(text)}</b> <span class="muted">${names.map(esc).join(' · ')}</span></td></tr>`;
// Timeline: card of the clicked block — what it measured, the 4 metrics with phase values, and its commits
function blockCard(j, queue) {
  const m = (j.comparison && j.comparison.metrics) || {};
  const phases = (j.comparison && j.comparison.phases) || ['A1', 'B1', 'B2', 'A2'];
  const bars = (r, dg) => {
    const vs = phases.map((p) => r.values[p]).filter((v) => v != null);
    if (!vs.length) return '';
    const lo = Math.min(...vs), hi = Math.max(...vs), span = hi - lo || Math.abs(hi) * 0.01 || 1;
    return `<svg viewBox="0 0 ${phases.length * 14} 24" width="${phases.length * 14}" height="24" style="vertical-align:middle">${phases.map((p, i) => {
      const v = r.values[p]; if (v == null) return '';
      const h = 4 + ((v - lo) / span) * 18;
      return `<rect x="${i * 14 + 2}" y="${24 - h}" width="10" height="${h}" rx="1" fill="${p.startsWith('A') ? '#2563eb' : '#ea580c'}"><title>${p}: ${fmt.num(v, dg)}</title></rect>`;
    }).join('')}</svg>`;
  };
  const rows = METRIC_COLS.slice(0, 4).map(([k, h, dg]) => {
    const r = m[k];
    if (!r) return '';
    return `<tr><td>${VERDICT_KEYS.includes(k) ? `<b>${h}</b>` : h}</td>
      <td class="num">${fmt.num(r.meanA, dg)}</td><td class="num">${valDelta(k, r.meanB, dg, r.deltaPct, r.verdict, r.thresholdPct)}</td>
      <td class="num muted">${r.ciLow != null ? `${fmt.pct(r.ciLow)} … ${fmt.pct(r.ciHigh)}` : '–'}</td><td class="num muted">±${fmt.num(r.thresholdPct, 1)}%</td>
      <td>${bars(r, dg)} <span class="muted small-text">${phases.map((p) => fmt.num(r.values[p], dg)).join(' · ')}</span></td></tr>`;
  }).join('');
  return `
    <div class="card block-card">
      <div class="release-head">
        <div><span class="release-title">${jobTitle(j, false)}</span> <span class="muted">${measures(j)}</span>${parentTag(j)}</div>
        <div>${badge(j.status)} ${j.verdict ? badge(j.verdict) : ''} <a href="#/jobs/${j.id}">job #${j.id} →</a> ${jobActions(j)}</div>
      </div>
      ${queue ? queueLine(j, queue) : ''}
      ${j.comparison ? `<table class="commits runs-table" style="margin-top:8px"><thead><tr><th>Metric</th><th class="num">A</th><th class="num">B · Δ</th><th class="num"${hint('CI')}>95% CI</th><th class="num"${hint('threshold')}>Threshold</th><th>Phases ${phases.join(' ')} <span class="muted">(blue A, orange B)</span></th></tr></thead>
        <tbody>${rows}</tbody></table>` : `<div class="muted" style="margin-top:8px">${j.status === 'done' ? 'no comparison' : `not measured yet (${esc(j.stage || j.status)})`}</div>`}
      ${j.segment ? segmentSection(j) : ''}
    </div>`;
}

// Timeline card of a queued or running job: where it is in the queue and when it should be done
function queueLine(j, queue) {
  const e = queue.eta, start = e.etaStartAt ? Date.parse(e.etaStartAt) : null;
  const end = start != null && e.etaSec != null ? new Date(start + e.etaSec * 1000).toISOString() : null;
  if (j.status === 'running')
    return `<div class="queue-line">running · ${esc(j.stage || '')} · ${j.progress || 0}%<div class="progress"><div style="width:${j.progress || 0}%"></div></div>
      ${end ? `<span class="muted">done at about ${etaStart(end)}</span>` : ''}</div>`;
  return `<div class="queue-line">queued · ${queue.position} of ${queue.of} in the queue (priority ${j.priority})
    ${start ? ` · starts ${etaStart(e.etaStartAt)}` : ''}${end ? ` · done ${etaStart(end)}` : ''}${e.etaSec ? ` <span class="muted">(~${mins(e.etaSec)} of work)</span>` : ''}</div>`;
}

function segmentSection(j) {
  const sg = j.segment, done = sg.commits.filter((c) => c.jobId).length;
  return `
    <details class="card" data-open="segment" ${sg.commits.length <= 30 ? 'open' : ''}>
      <summary><b>Segment: ${sg.count} commits</b> <span class="muted">${fmtDay(sg.fromDate)} – ${fmtDay(sg.toDate)} · from
        <a class="mono" href="#/commits/${sg.fromSha}">${fmt.sha(sg.fromSha)}</a> (A) to <a class="mono" href="#/commits/${sg.toSha}">${fmt.sha(sg.toSha)}</a> (B)
        · ${done} measured on their own</span></summary>
      <table class="commits runs-table"><thead><tr><th class="num">#</th><th>Commit</th><th>Date</th><th>Subject</th><th>Author</th><th>Own run vs parent</th></tr></thead>
      <tbody>${sg.commits.slice().reverse().map((c) => `<tr>
        <td class="num muted">${c.seq ?? ''}</td><td class="mono"><a href="#/commits/${c.sha}">${fmt.sha(c.sha)}</a></td>
        <td class="muted" style="white-space:nowrap">${fmtDay(c.committedAt)}</td>
        <td class="subject" title="${esc(c.subject || '')}">${subjectHtml(c.subject)}</td><td class="muted" style="white-space:nowrap">${esc(c.author || '')}</td>
        <td style="white-space:nowrap">${c.jobId ? `<a href="#/jobs/${c.jobId}">#${c.jobId}</a> ${badge(c.verdict)} <span class="muted mono">${fmt.pct(c.throughputDeltaPct)}</span>` : '<span class="muted">–</span>'}</td></tr>`).join('')}</tbody></table>
    </details>`;
}

function profileSection(d, abba, mode) {
  const rows = mode === 'total' ? d.total : d.self;
  const shortName = (m) => { const i = m.lastIndexOf('.', m.lastIndexOf('.') - 1); return i > 0 ? m.slice(i + 1) : m; };
  // a share from n samples has a binomial error; a Δ is highlighted only when it exceeds 2 standard errors and 1 pp
  const se = (pct, n) => (n > 0 ? Math.sqrt((pct / 100) * (1 - pct / 100) / n) * 100 : Infinity);
  const significant = (r) => Math.abs(r.deltaPp) >= 1 && Math.abs(r.deltaPp) > 2 * Math.sqrt(se(r.pctA, d.samplesA) ** 2 + se(r.pctB, d.samplesB) ** 2);
  const few = abba ? Math.min(d.samplesA, d.samplesB) < 2000 : d.samplesB < 2000;
  return `
    <h2>Hot methods <span class="muted">${abba ? `A: ${fmt.num(d.samplesA)} samples in ${d.phasesA} phase(s) · B: ${fmt.num(d.samplesB)} in ${d.phasesB}` : `${fmt.num(d.samplesB)} samples`}
      · jdk.ExecutionSample, server JVMs, measure window</span>
      <select id="prof-mode" class="small"><option value="self" ${mode === 'self' ? 'selected' : ''}>self (top of stack)</option><option value="total" ${mode === 'total' ? 'selected' : ''}>total (anywhere on stack)</option></select></h2>
    <table class="profile"><thead><tr><th>Method</th>${abba ? '<th class="num">A %</th><th class="num">B %</th><th class="num">Δ pp</th>' : '<th class="num">%</th>'}</tr></thead>
    <tbody>${rows.map((r) => `<tr><td class="mono" title="${esc(r.method)}">${esc(shortName(r.method))}</td>
      ${abba ? `<td class="num">${r.pctA.toFixed(2)}</td><td class="num">${r.pctB.toFixed(2)}</td><td class="num ${significant(r) ? (r.deltaPp > 0 ? 'delta regression' : 'delta improvement') : 'muted'}">${(r.deltaPp > 0 ? '+' : '') + r.deltaPp.toFixed(2)}</td>`
        : `<td class="num">${r.pctB.toFixed(2)}</td>`}</tr>`).join('')}</tbody></table>
    <div class="muted">${abba ? 'Sorted by |Δ|: methods that gained or lost the most share of CPU samples between A and B. Red = more time in B; only Δ above 2 sampling standard errors is coloured.' : 'Share of CPU samples per method.'}
      ${few ? '<b>Few samples: shares are only rough, differences of a few pp are usually sampling noise.</b>' : ''}</div>`;
}

function metricDigits(k) {
  return k === 'gcPausePct' || k === 'allocKbPerOp' ? 2 : k === 'throughput' || k.endsWith('Us') || k.endsWith('Ms') || k === 'heapAfterGcMb' ? 0 : 1;
}

// Commits: one row per commit with a dot for each run against the parent (colour = verdict, the tooltip names the scenario);
// a dot or the hash opens the commit page with the metrics of every scenario. Segments live on Timeline.
function verdictDot(p, sc, sha, active) {
  if (active) return `<a href="#/jobs/${active.id}" class="vdot vdot-${active.status}" title="${esc(scName(sc))}: ${active.status} (job #${active.id})"></a>`;
  const d = (k) => (p.deltaPct[k] != null ? `${fmt.pct(p.deltaPct[k])}` : '–');
  const over = METRIC_COLS.some(([k]) => deltaLevel(p.deltaPct[k], p.thresholdPct[k], p.metricVerdict[k]) === 'over');
  const cls = p.verdict === 'regression' ? 'bad' : p.verdict === 'improvement' ? 'good' : over ? 'over' : 'ok';
  return `<a href="#/commits/${sha}" class="vdot vdot-${cls}" title="${esc(scName(sc))}: ${esc(p.verdict || 'no verdict')}${p.runs > 1 ? ` ×${p.runs}` : ''}
ops/s ${d('throughput')} · p99 ${d('p99Us')} · CPU/op ${d('serverCpuUsPerOp')} · alloc/op ${d('allocKbPerOp')}"></a>`;
}
const COMMITS_PAGE = 50;
views.commits = {
  poll: 15000,
  // everything at once: pages and filters then switch without the server, a visit to the page loads it again
  async load() {
    const [settings, scenarios] = await Promise.all([api.get('/api/settings'), api.get('/api/scenarios')]);
    const names = scenarios.map((s) => s.name);
    const rel = await api.get(`/api/release?scenario=${encodeURIComponent(names[0] || '')}`).catch(() => ({}));
    const [commits, ...trends] = await Promise.all([
      api.get('/api/commits?limit=5000'),
      ...names.map((n) => api.get(`/api/trend?scenario=${encodeURIComponent(n)}&limit=5000`).catch(() => [])),
    ]);
    // only runs of a single commit against its parent; segments are on Timeline
    const segments = names.flatMap((n, i) => trends[i].filter((p) => (p.span || 1) > 1 && p.baseSeq != null).map((p) => ({ scenario: n, from: p.baseSeq, to: p.seq, span: p.span, job: p.jobId })));
    return { settings, names, rel, commits, segments, res: names.map((n, i) => new Map(trends[i].filter((p) => (p.span || 1) <= 1).map((p) => [p.sha, p]))) };
  },
  async render() {
    if (!this.keep || !this.data) this.data = await this.load();
    this.keep = false;
    const { settings, names, rel, commits, segments, res } = this.data;
    // the shortest measured segment around a commit that has no run of its own
    const inSegment = (c) => {
      const s = segments.filter((x) => x.from < c.seq && c.seq <= x.to).sort((a, b) => a.span - b.span)[0];
      return s ? `<a class="in-seg" href="#/timeline?scenario=${encodeURIComponent(s.scenario)}&job=${s.job}" title="${esc(scName(s.scenario))}: measured as a part of ${s.span} commits">in ${s.span}</a>` : '';
    };
    const picked = new Set(rel.cherryPicked || []);
    const since = (this.scope || 'release') === 'release' && rel.tag;
    const scoped = since ? commits.slice(0, rel.unreleased + picked.size).filter((c) => !picked.has(c.sha)) : commits;
    // an active run of this commit vs its parent; A/A calibrations of the same sha are not that
    const activeOf = (c, sc) => (c.jobs || []).find((j) => j.scenario === sc && j.source !== 'calibration' && (j.status === 'queued' || j.status === 'running'));
    const dots = (c) => names.map((n, i) => { const p = res[i].get(c.sha), run = activeOf(c, n); return p || run ? verdictDot(p, n, c.sha, run) : ''; }).join('');
    const measured = scoped.filter((c) => dots(c)).length;
    const listed = this.onlyMeasured ? scoped.filter((c) => dots(c)) : scoped;
    const pages = Math.max(1, Math.ceil(listed.length / COMMITS_PAGE)), page = Math.min(this.page || 0, pages - 1);
    const rows = listed.slice(page * COMMITS_PAGE, (page + 1) * COMMITS_PAGE);
    const go = (to, name, off) => `<button type="button" class="small" data-page="${to}" ${off ? 'disabled' : ''}>${name}</button>`;
    const pager = listed.length ? `<span class="pager">${go(0, 'First', !page)} ${go(page - 1, 'Prev', !page)}
      <span class="muted">${page * COMMITS_PAGE + 1}–${page * COMMITS_PAGE + rows.length} of ${listed.length}</span>
      ${go(page + 1, 'Next', page >= pages - 1)} ${go(pages - 1, 'Last', page >= pages - 1)}</span>` : '';
    return `
      <h1>Commits <span class="muted">${esc(settings['repo.branch'])}, first-parent history</span></h1>
      <div class="card actions-inline">
        Show <select id="scope">${rel.tag ? `<option value="release" ${since ? 'selected' : ''}>since ${esc(rel.tag)} (${rel.unreleased})</option>` : ''}
          <option value="all" ${since ? '' : 'selected'}>all (${commits.length})</option></select>
        <label class="inline"><input type="checkbox" id="only-measured" ${this.onlyMeasured ? 'checked' : ''}> only measured <span class="muted">${measured} of ${scoped.length}</span></label>
        <span class="legend"><span class="vdot vdot-ok"></span> ${term('no-change', 'no change')} <span class="vdot vdot-over"></span> ${term('over', 'beyond threshold')} <span class="vdot vdot-bad"></span> ${term('regression')} <span class="vdot vdot-good"></span> ${term('improvement')} <span class="vdot vdot-queued"></span> queued · <span class="in-seg">in N</span> inside a measured segment</span>
        ${pager}
      </div>
      <table class="commits commit-list"><thead><tr><th class="num">#</th><th>Commit</th><th>Date</th><th>Subject</th><th>Runs</th><th></th></tr></thead>
      <tbody>${rows.map((c) => `<tr>
        <td class="num muted">${c.seq ?? ''}</td>
        <td class="mono"><a href="#/commits/${c.sha}">${fmt.sha(c.sha)}</a></td>
        <td class="muted" style="white-space:nowrap" title="${esc(c.committedAt)}">${fmt.date(c.committedAt)}</td><td class="subject" title="${esc(c.subject)}${c.author ? ` (${esc(c.author)})` : ''}">${subjectHtml(c.subject)}</td>
        <td class="dots">${dots(c) || inSegment(c)}</td>
        <td class="row-action"><button class="small" data-run="${c.sha}" data-parent="${c.parentSha || ''}">Run</button></td></tr>`).join('')
        || `<tr><td colspan="6" class="muted">${this.onlyMeasured ? 'nothing measured one by one here' : `no commits yet: they come with the first fetch of the repository, <a href="#/dashboard">Dashboard</a> shows how far it is`}</td></tr>`}</tbody></table>
      <div class="commits-foot">${pages > 1 ? pager : ''}<span class="muted small-text">Run = ABBA of the commit against its parent; the scenarios are chosen in the dialog.</span></div>`;
  },
  bind(root) {
    const show = (page) => { this.page = page; this.keep = true; refresh(); };
    $('#scope', root).addEventListener('change', (e) => { this.scope = e.target.value; show(0); });
    $('#only-measured', root).addEventListener('change', (e) => { this.onlyMeasured = e.target.checked; show(0); });
    root.addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (b && b.dataset.page) { show(Number(b.dataset.page)); window.scrollTo(0, 0); return; }
      if (!b || !b.dataset.run) return;
      guarded(async () => {
        const scenarios = await api.get('/api/scenarios');
        const subj = b.closest('tr') && b.closest('tr').querySelector('.subject');
        const list = await confirmLaunch({ title: 'Measure the commit', shas: [b.dataset.parent, b.dataset.run], extraBuilds: b.dataset.parent ? 0 : 1,
          choose: scenarios.map((s) => ({ name: s.name, checked: false })),
          what: `<span class="mono">${fmt.sha(b.dataset.run)}</span> ${esc(subj ? subj.textContent : '')}`, note: 'ABBA against its parent, one job per scenario' });
        if (!list) return;
        for (const scenario of list) {
          const j = await api.post('/api/jobs', { type: 'abba', refA: b.dataset.parent || `${b.dataset.run}~1`, refB: b.dataset.run, scenario, priority: 1 }).catch((err) => toast(`${scenario}: ${err.message}`, true));
          if (j) toast(`job #${j.id} queued: ${j.label} [${scenario}]`);
        }
        refresh();
      });
    });
  },
};

views.commit = {
  poll: 15000,
  async render(params) {
    const [d, scenarios] = await Promise.all([api.get(`/api/commits/${encodeURIComponent(params.id)}`), api.get('/api/scenarios')]);
    const c = d.commit || {};
    const RUN_COLS = METRIC_COLS.slice(0, 4);
    const roleTitle = { target: 'this commit is B', baseline: 'this commit is A (baseline)', 'A/A': 'calibration, same build on both sides' };
    const vsParent = ({ role, job: j }) => role === 'target' && j.type === 'abba' && j.source !== 'calibration' && c.parentSha && j.shaA === c.parentSha;
    const row = ({ role, job: j }) => {
      const m = (j.comparison && j.comparison.metrics) || {};
      const side = role === 'baseline' ? 'meanA' : 'meanB';
      const cells = RUN_COLS.map(([k, , dg]) => {
        const r = m[k];
        return `<td class="num">${r ? valDelta(k, r[side], dg, r.deltaPct, r.verdict, r.thresholdPct) : '–'}</td>`;
      }).join('');
      const seg = isSegment(j) ? segmentSize(j) : 0;
      const other = role === 'baseline' ? `vs B ${fmt.sha(j.shaB)}` : role === 'target' && j.type === 'abba' ? `vs A ${fmt.sha(j.shaA)}` : '';
      role = seg && role !== 'A/A' ? (role === 'baseline' ? 'segment start' : `segment end (${seg} commits)`) : role;
      return `<tr class="${j.status === 'done' ? '' : 'muted'}"><td style="white-space:nowrap">${jobLink(j)}${role === 'target' && j.type === 'abba' && j.status === 'done' && j.source !== 'calibration' ? ' ' + moreRunBtn(j.id) : ''}</td>
        <td><span title="${esc(roleTitle[role] || '')}">${esc(role)}</span> <span class="muted mono">${other}</span></td>
        <td>${esc(scName(j.scenario))}<div class="muted">${esc(j.type)} · ${esc(j.source)}</div></td>
        <td>${badge(j.status)}</td>${cells}<td>${verdictBadge(j)}</td><td class="muted" style="white-space:nowrap">${fmt.date(j.finishedAt || j.createdAt)}</td></tr>`;
    };
    const mainRows = d.jobs.filter(vsParent).map(row).join('');
    const otherRows = d.jobs.filter((x) => !vsParent(x)).map(row).join('');
    const nMain = d.jobs.filter(vsParent).length;
    const pooledRows = (d.pooled || []).filter((g) => g.shaA === c.parentSha).map((g) => {
      const m = g.comparison.metrics;
      const cells = RUN_COLS.map(([k, , dg]) => `<td class="num">${m[k] ? valDelta(k, m[k].meanB, dg, m[k].deltaPct, m[k].verdict, m[k].thresholdPct) : '–'}</td>`).join('');
      const t = m.throughput || {};
      const ci = t.ciLow != null ? `CI ${fmt.pct(t.ciLow, 2)}..${fmt.pct(t.ciHigh, 2)}, threshold ${fmt.num(t.thresholdPct, 2)}%` : '';
      return `<tr class="pooled"><td style="white-space:nowrap"><b>×${g.jobIds.length}</b> ${moreRunBtn(g.jobIds[g.jobIds.length - 1])}</td>
        <td>pooled <span class="muted mono">vs A ${fmt.sha(g.shaA)}</span></td>
        <td>${esc(g.scenario)}<div class="muted">${g.jobIds.map((i) => `<a href="#/jobs/${i}">#${i}</a>`).join(' ')}</div></td>
        <td class="muted" title="throughput">${ci}</td>${cells}<td>${badge(g.comparison.verdict)}</td><td></td></tr>`;
    }).join('');
    // summary: this commit vs its parent in every measured scenario (pooled runs when there are several, else the newest
    // done run); the scenarios without a run unfold on request, to measure them
    let idleCount = 0;
    const summary = scenarios.map(({ name }) => {
      const g = (d.pooled || []).find((x) => x.scenario === name && x.shaA === c.parentSha);
      const j = d.jobs.find((x) => x.job.scenario === name && vsParent(x) && x.job.status === 'done');
      const act = d.jobs.find((x) => x.job.scenario === name && (x.job.status === 'queued' || x.job.status === 'running'));
      const m = g ? g.comparison.metrics : j && j.job.comparison ? j.job.comparison.metrics : null;
      const verdict = g ? g.comparison.verdict : j ? j.job.verdict : null;
      const runs = g ? g.jobIds.length : j ? 1 : 0;
      const cells = METRIC_COLS.map(([k, , dg]) => `<td class="num">${m && m[k] ? valDelta(k, m[k].meanB, dg, m[k].deltaPct, m[k].verdict, m[k].thresholdPct) : '<span class="muted">–</span>'}</td>`).join('');
      const idle = !m && !act;
      idleCount += idle ? 1 : 0;
      return `<tr class="${idle ? 'sc-idle' : ''}" ${idle ? 'hidden' : ''}><td><b>${esc(scName(name))}</b></td>${cells}<td>${verdict ? badge(verdict) : ''}${runs > 1 ? ` <span class="muted">×${runs}</span>` : ''}</td>
        <td class="row-action" style="opacity:1">${act ? `${jobLink(act.job)} ${badge(act.job.status)}` : j ? moreRunBtn(j.job.id) : `<button class="small" data-run-sc="${esc(name)}">Run</button>`}</td></tr>`;
    }).join('');
    return `
      <h1>Commit <span class="mono">${fmt.sha(d.sha)}</span>${c.seq != null ? ` <span class="muted">#${c.seq}</span>` : ''}</h1>
      <div class="card">
        <div><b>${subjectHtml(c.subject)}</b></div>
        <div class="muted">${esc(c.author || '')} · ${fmt.date(c.committedAt)} · parent <a class="mono" href="#/commits/${esc(c.parentSha || '')}">${fmt.sha(c.parentSha)}</a>
          ${repoWeb ? `· <a href="${esc(repoWeb)}/commit/${d.sha}" target="_blank" rel="noopener">repository</a>` : ''}</div>
      </div>
      <h2>By scenario <span class="muted">this commit vs its parent; several runs are pooled</span></h2>
      <table class="commits runs-table"><thead><tr><th>Scenario</th>${METRIC_COLS.map(metricHead).join('')}<th>Verdict</th><th></th></tr></thead><tbody>${summary}</tbody></table>
      ${idleCount ? `<button class="att-more" data-show-idle>${plural(idleCount, 'scenario')} without a run</button>` : ''}
      <h2>Runs vs parent <span class="muted">(${nMain}, newest first; Δ is this commit vs its parent)</span></h2>
      <table class="commits runs-table"><thead><tr><th>Job</th><th>Role</th><th>Scenario</th><th>Status</th>${RUN_COLS.map(metricHead).join('')}<th>Verdict</th><th>Finished</th></tr></thead>
      <tbody>${pooledRows}${mainRows || `<tr><td colspan="${METRIC_COLS.length + 6}" class="muted">no runs against the parent yet: press Run on the Commits page</td></tr>`}</tbody></table>
      ${otherRows ? `<h2>Other runs <span class="muted">(${d.jobs.length - nMain}: calibrations, other baselines, this commit as baseline; values are this commit's side, Δ is B vs A)</span></h2>
      <table class="commits runs-table"><thead><tr><th>Job</th><th>Role</th><th>Scenario</th><th>Status</th>${RUN_COLS.map(metricHead).join('')}<th>Verdict</th><th>Finished</th></tr></thead>
      <tbody>${otherRows}</tbody></table>` : ''}`;
  },
  bind(root) {
    root.addEventListener('click', (e) => {
      const b = e.target.closest('button[data-more]');
      if (b) guarded(() => moreRun(b.dataset.more));
      const idle = e.target.closest('button[data-show-idle]');
      if (idle) { root.querySelectorAll('tr.sc-idle').forEach((t) => { t.hidden = false; }); idle.remove(); }
      const r = e.target.closest('button[data-run-sc]');
      if (r) guarded(async () => {
        const d = await api.get(`/api/commits/${encodeURIComponent(location.hash.split('/')[2])}`), c = d.commit || {};
        if (!(await confirmLaunch({ title: 'Measure the commit', scenario: r.dataset.runSc, shas: [c.parentSha, d.sha], extraBuilds: c.parentSha ? 0 : 1,
          what: `<span class="mono">${fmt.sha(d.sha)}</span> ${esc(c.subject || '')}`, note: 'ABBA against its parent' }))) return;
        const j = await api.post('/api/jobs', { type: 'abba', refA: c.parentSha || `${d.sha}~1`, refB: d.sha, scenario: r.dataset.runSc, priority: 1 });
        toast(`job #${j.id} queued: ${j.label}`);
        refresh();
      });
    });
  },
};

// A row that unfolds the detail row right after it
function unfoldRow(row, open) {
  row.nextElementSibling.hidden = !open;
  $('.chev', row).textContent = open ? '▾' : '▸';
}
// Noise: A/A calibrations of every scenario. A row per scenario (own floors, last A/A, what to check) unfolds its history;
// one button queues A/A for all scenarios, a row button for one
views.noise = {
  poll: 20000,
  async render() {
    const [settings, scenarios, active] = await Promise.all([api.get('/api/settings'), api.get('/api/scenarios'), api.get('/api/jobs?filter=active&limit=200')]);
    const all = await Promise.all(scenarios.map((s) => api.get(`/api/noise?scenario=${encodeURIComponent(s.name)}`).catch(() => ({ scenario: s.name, floors: {}, calibrations: [] }))));
    if (!this.open) this.open = new Set();
    const verdictKeys = METRIC_COLS.slice(0, 3).map(([k, h]) => [k, h]);
    // verdict metrics first, then the diagnostics shown in the history tables
    const keys = [...verdictKeys.map(([k, h]) => [k, h, true]), ['allocKbPerOp', 'alloc KB/op'],
      ['p50Us', 'p50'], ['p999Us', 'p99.9'], ['serverStartMs', 'start'], ['preloadMs', 'preload'], ['gcPausePct', 'GC pause']];
    const floorKeys = verdictKeys.concat([['allocKbPerOp', 'alloc KB/op']]);
    const custom = (n) => n.source !== 'default' && (n.calibrations || []).length >= 3;
    const standard = all.find((n) => !custom(n)), own = all.some(custom);
    const few = all.filter((n) => (n.calibrations || []).length < OWN_FLOORS && !active.some((j) => j.scenario === n.scenario && j.source === 'calibration'));
    this.few = few.map((n) => n.scenario);
    const rows = all.map((n) => {
      const sc = n.scenario, floors = n.floors || {}, cals = n.calibrations || [], last = cals[0], open = this.open.has(sc);
      const run = active.find((j) => j.scenario === sc && j.source === 'calibration');
      const over = last ? verdictKeys.filter(([k]) => last.deltaPct[k] != null && Math.abs(last.deltaPct[k]) > floors[k]) : [];
      const status = !last ? '<span class="muted">no A/A yet</span>' : over.length ? `<b class="bad-text" title="last A/A over the floor">check: ${over.map(([, h]) => h).join(', ')}</b>` : '';
      const history = cals.length ? `<table class="commits runs-table"><thead><tr><th>Metric</th><th class="num">Floor</th>${cals.map((c) => `<th class="num"><a href="#/jobs/${c.jobId}" title="${esc(c.label)}">#${c.jobId}</a><div class="muted" style="font-weight:normal">${fmtDay(c.finishedAt)}</div></th>`).join('')}</tr></thead>
        <tbody>${keys.map(([k, h, v]) => `<tr><td>${v ? `<b>${esc(h)}</b>` : esc(h)}</td><td class="num">±${fmt.num(floors[k], 1)}%</td>${cals.map((c) => {
          const d = c.deltaPct[k];
          return `<td class="num ${d != null && Math.abs(d) > floors[k] ? 'over' : 'muted'}">${fmt.pct(d)}</td>`;
        }).join('')}</tr>`).join('')}</tbody></table>` : '<div class="muted">no calibrations yet: defaults are used</div>';
      return `<tr class="click-row" data-noise="${esc(sc)}"><td><span class="chev">${open ? '▾' : '▸'}</span> <b>${esc(scName(sc))}</b></td>
        ${own ? `<td>${custom(n) ? floorKeys.map(([k, h]) => `${h} ±${fmt.num(floors[k], 1)}%`).join(' · ') : '<span class="muted">default</span>'}</td>` : ''}
        ${verdictKeys.map(([k]) => `<td class="num">${last ? `<span class="${last.deltaPct[k] != null && Math.abs(last.deltaPct[k]) > floors[k] ? 'over' : 'muted'}">${fmt.pct(last.deltaPct[k])}</span>` : '–'}</td>`).join('')}
        <td class="num">${cals.length < OWN_FLOORS ? `<span title="a scenario gets its own floors after ${OWN_FLOORS} calibrations">${cals.length} of ${OWN_FLOORS}</span>` : cals.length}</td><td class="muted">${last ? fmt.ago(last.finishedAt) : '–'}</td><td>${status}</td>
        <td class="row-action" style="opacity:1">${run ? `${jobLink(run)} ${badge(run.status)}` : `<button class="small" data-aa="${esc(sc)}">Run A/A</button>`}</td></tr>
        <tr class="row-detail" ${open ? '' : 'hidden'}><td colspan="${own ? 9 : 8}">${history}</td></tr>`;
    }).join('');
    return `
      <h1 class="with-tools">Noise <span class="muted">${term('A/A')}: the same build on both sides, so every Δ is stand noise</span>
        <span class="tools">${few.length ? `<button class="primary" id="calibrate-few" title="one more A/A for every scenario with fewer than ${OWN_FLOORS}">Run A/A for ${plural(few.length, 'scenario')} with too few</button> ` : ''}<button id="calibrate-all">All scenarios</button></span></h1>
      ${standard ? `<div class="muted" style="margin-bottom:6px">Default ${term('floor', 'floors')}: ${floorKeys.map(([k, h]) => `${h} ±${fmt.num(standard.floors[k], 1)}%`).join(' · ')}. A scenario gets its own after ${OWN_FLOORS} calibrations${all.every((n) => !custom(n)) ? ', none has them yet' : ''}.</div>` : ''}
      <table class="commits runs-table"><thead><tr><th>Scenario</th>${own ? `<th${hint('floor')}>Floors</th>` : ''}
        ${verdictKeys.map(([k, h]) => `<th class="num"${hint(k)}>Δ ${h}</th>`).join('')}<th class="num"${hint('A/A')}>A/A</th><th>Last</th><th></th><th></th></tr></thead><tbody>${rows}</tbody></table>
      <div class="muted small-text" style="margin:6px 0 14px">Floor = 95th percentile of |Δ| over the last 10 calibrations, never below the default.
        A Δ between commits counts only beyond the floor and the spread between repeats. Red: a calibration went over the current floor; several in a row mean the stand got noisier.
        Scheduled A/A: ${settings['calibration.mode'] === 'keep' ? 'on' : 'off'} (Settings).</div>`;
  },
  bind(root) {
    const queue = async (list) => {
      if (!(await confirmLaunch({ title: list.length > 1 ? 'A/A calibration: every scenario' : `A/A calibration: ${list[0]}`, scenario: list[0], jobs: list.length, priority: 0,
        what: `master head against itself: ${list.map((x) => esc(scName(x))).join(', ')}`, note: 'measures the noise floors the thresholds use; one ABBA job per scenario' }))) return;
      for (const sc of list) await api.post('/api/calibrate', { ref: 'master', scenario: sc }).catch((e) => toast(`${sc}: ${e.message}`, true));
      toast(`${list.length} A/A job(s) queued`);
      refresh();
    };
    $('#calibrate-all', root).addEventListener('click', () => guarded(async () => queue((await api.get('/api/scenarios')).map((s) => s.name))));
    const few = $('#calibrate-few', root);
    if (few) few.addEventListener('click', () => guarded(() => queue(this.few)));
    root.addEventListener('click', (e) => {
      const b = e.target.closest('button[data-aa]');
      if (b) { guarded(() => queue([b.dataset.aa])); return; }
      const r = e.target.closest('tr[data-noise]');
      if (r && !e.target.closest('a') && !e.target.closest('button')) {
        const sc = r.dataset.noise, open = !this.open.has(sc);
        if (open) this.open.add(sc); else this.open.delete(sc);
        unfoldRow(r, open);
      }
    });
  },
};

views.timeline = {
  poll: 30000,
  async render() {
    // the address holds what the page shows, so a link opens the same view; a change of the page writes it back below.
    // The menu opens the overview alone, a scenario unfolds below it. A link to a scenario is about that scenario: there the
    // overview stays folded instead
    if (location.hash !== this.url) {
      const q = new URLSearchParams(location.hash.split('?')[1] || '');
      this.open = q.has('scenario');
      this.one = this.open && !q.has('all');
      if (q.get('metric')) this.metric = q.get('metric');
      if (this.open) this.scenario = q.get('scenario');
      this.range = q.has('from') ? [Number(q.get('from')), Number(q.get('to'))] : null;
      this.sel = q.get('job') ? Number(q.get('job')) : null;
    }
    const [settings, scenarios] = await Promise.all([api.get('/api/settings'), api.get('/api/scenarios')]);
    const scenario = this.scenario || settings['autorun.scenario'].split(',')[0].trim();
    const metric = this.metric || 'throughput';
    this.url = `#/timeline?${new URLSearchParams({ ...(this.open ? { scenario } : {}), metric, ...(this.sel ? { job: this.sel } : {}), ...(this.range ? { from: this.range[0], to: this.range[1] } : {}), ...(this.one ? {} : { all: 1 }) })}`;
    if (location.hash !== this.url) history.replaceState(null, '', this.url);
    const [rel, commits, trend, active, allBisects] = await Promise.all([
      api.get(`/api/release?scenario=${encodeURIComponent(scenario)}`).catch(() => ({})),
      api.get(`/api/commits?limit=1000&scenario=${encodeURIComponent(scenario)}`),
      // refined: also the segments a split replaced as the point of their end commit, so a bisection keeps its parents
      this.open ? api.get(`/api/trend?scenario=${encodeURIComponent(scenario)}&limit=1000&refined=true`) : [],
      api.get('/api/jobs?filter=active&limit=5000'),
      api.get('/api/bisects?active=true').catch(() => []),
    ]);
    // a bisection of a comparison searches another range: it is not on this map of the branch
    const branchBisects = allBisects.filter((b) => !b.comparisonId), bisects = branchBisects.filter((b) => b.scenario === scenario);
    // the runs of all the scenarios are the heavy part of the page: they live for a minute while the map follows its controls
    if (!this.one && (!this.all || Date.now() - this.all.at > 60000)) {
      const each = (path) => Promise.all(scenarios.map((x) => api.get(path(encodeURIComponent(x.name))).catch(() => null)));
      const [trends, rels] = await Promise.all([each((n) => `/api/trend?scenario=${n}&limit=1000&refined=true`), each((n) => `/api/release?scenario=${n}`)]);
      this.all = { at: Date.now(), trends, rels };
    }
    const bySha = new Map(commits.map((c) => [c.sha, c]));
    const picked = new Set(rel.cherryPicked || []);
    // queued and running branch jobs of this scenario: drawn as dashed outlines where their block will appear
    const bp = rel.branchPoint && bySha.get(rel.branchPoint);
    const lo = bp ? bp.seq : Math.min(...commits.map((c) => c.seq)), hi = Math.max(...commits.map((c) => c.seq));
    // a base off the branch (the release tag after a split of "master vs tag") starts at the branch point
    const pending = active.filter((j) => j.scenario === scenario && j.type === 'abba' && j.shaA !== j.shaB
      && j.source !== 'compare' && j.source !== 'release' && bySha.has(j.shaB))
      .map((j) => ({ job: j, a: bySha.has(j.shaA) ? bySha.get(j.shaA).seq : lo, b: bySha.get(j.shaB).seq })).filter((x) => x.a < x.b);
    const [from, to] = this.range || [lo, hi];
    const pts = trend.filter((p) => p.seq != null && p.seq > lo);
    const mt = METRIC_COLS.find(([k]) => k === metric) || METRIC_COLS[0];
    // map geometry: x in commit numbers over [from, to], 1000 user units wide
    const W = 1000, X = (v) => ((v - from) / Math.max(1, to - from)) * W;
    const fills = { none: 'var(--line)', bad: 'var(--bad)', good: 'var(--ok)', over: '#eda100', noise: 'var(--map-noise)' };
    const color = (p) => fills[mapLevel(metric, p.deltaPct[metric], p.thresholdPct[metric], p.metricVerdict[metric])];
    // lanes: rows by segment size (largest on top, single commits at the bottom); overlapping blocks of the same
    // size go to extra rows of that size, so nothing is drawn over anything else. Queued jobs take their own place.
    const items = pts.map((p) => ({ kind: 'done', p, a: p.baseSeq != null ? p.baseSeq : p.seq - 1, b: p.seq }))
      .concat(pending.map((x) => ({ kind: 'pending', job: x.job, a: x.a, b: x.b })))
      .filter((it) => it.b > from && it.a < to);
    const sizeClass = (span) => (span <= 1 ? 0 : 1 + Math.floor(Math.log2(span)));
    const classLabel = (c) => (c === 'release' ? `vs ${esc(rel.tag)}` : c === 0 ? '1 commit' : `${2 ** (c - 1)}–${2 ** c - 1} commits`);
    const classes = [...new Set(items.map((it) => sizeClass(it.b - it.a)))].sort((x, y) => y - x);
    const lanes = []; // { cls, ends: last b in the lane }
    for (const cls of classes) {
      const group = items.filter((it) => sizeClass(it.b - it.a) === cls).sort((x, y) => x.a - y.a);
      const mine = [];
      for (const it of group) {
        let lane = mine.find((l) => l.end <= it.a);
        if (!lane) { lane = { cls, end: -Infinity, idx: lanes.length }; lanes.push(lane); mine.push(lane); }
        lane.end = it.b;
        it.lane = lane.idx;
      }
    }
    // the headline "master vs release" on its own top row over the commits it covers, so a Δ from the dashboard is
    // visible here too; the rows below show how the measured segments split it
    const rj = rel.job, rb = rj && bySha.get(rj.shaB);
    if (rb && rb.seq > from && lo < to) {
      const m = (rj.comparison && rj.comparison.metrics) || {}, pick = (f) => Object.fromEntries(Object.entries(m).map(([k, r]) => [k, r[f]]));
      const p = { jobId: rj.id, sha: rj.shaB, subject: `${rel.branch || 'master'} vs ${rel.tag}`, span: rb.seq - lo,
        deltaPct: pick('deltaPct'), thresholdPct: pick('thresholdPct'), metricVerdict: pick('verdict') };
      lanes.forEach((l) => l.idx++);
      items.forEach((it) => { it.lane++; });
      lanes.unshift({ cls: 'release', idx: 0 });
      items.unshift({ kind: 'done', p, a: lo, b: rb.seq, lane: 0 });
    }
    const LH = 16, GAP = 3, top = 4, H = top + Math.max(1, lanes.length) * (LH + GAP) + 10;
    const laneY = (n) => top + n * (LH + GAP);
    const block = (it) => {
      const p = it.p, a = Math.max(from, it.a), b = Math.min(to, it.b);
      const x = X(a), w = Math.max(1.5, X(b) - X(a) - (b - a > 1 ? 1.5 : 0.3));
      const sel = this.sel === p.jobId ? ' stroke="var(--fg)" stroke-width="2" vector-effect="non-scaling-stroke"' : '';
      const refined = p.refinedBy ? ` · refined by #${p.refinedBy}: a shorter run ends at the same commit` : '';
      return `<rect class="map-block${p.refinedBy ? ' refined' : ''}" data-job="${p.jobId}" x="${x}" y="${laneY(it.lane)}" width="${w}" height="${LH}" rx="2" fill="${color(p)}"${sel}>
        <title>${(p.span || 1) > 1 ? `segment of ${p.span} commits` : '1 commit'} · ${fmt.sha(p.sha)} ${esc(p.subject || '')} · Δ ${fmt.pct(p.deltaPct[metric])}${refined}</title></rect>`;
    };
    // the svg stretches with the page and text in it would too: the Δ of a wide block lies over it
    const label = (it) => {
      const p = it.p, a = Math.max(from, it.a), b = Math.min(to, it.b), d = p.deltaPct[metric];
      if (d == null || X(b) - X(a) < 50) return '';
      return `<span class="map-label ${mapLevel(metric, d, p.thresholdPct[metric], p.metricVerdict[metric])}" style="left:${X(a) / 10}%;width:${(X(b) - X(a)) / 10}%;top:${laneY(it.lane)}px">${fmt.pct(d)}</span>`;
    };
    const ghost = (it) => {
      const job = it.job, a = Math.max(from, it.a), b = Math.min(to, it.b), run = job.status === 'running';
      const x = X(a), w = Math.max(2, X(b) - X(a) - 1.5);
      return `<g><rect class="map-ghost${run ? ' running' : ''}" data-job="${job.id}" x="${x}" y="${laneY(it.lane)}" width="${w}" height="${LH}" rx="2"
        fill="${run ? 'var(--accent)' : 'none'}" fill-opacity="${run ? 0.18 : 0}" stroke="var(--accent)" stroke-width="1.5" stroke-dasharray="${run ? '0' : '4 3'}" vector-effect="non-scaling-stroke">
        <title>${run ? 'running' : 'queued'} #${job.id}: ${esc(job.label)}${job.etaStartAt && !run ? ` · starts ~${fmt.date(job.etaStartAt)}` : ''}</title></rect></g>`;
    };
    // an active bisection: its suspect range as a band through every row (a base off the branch starts at the branch point)
    const seqOf = (sha) => (bySha.has(sha) ? bySha.get(sha).seq : lo);
    const bands = bisects.flatMap((b) => b.state.open.map((r) => {
      const a = Math.max(from, seqOf(r.a)), z = Math.min(to, seqOf(r.b));
      if (z <= from || a >= to) return '';
      return `<rect class="map-band" x="${X(a)}" y="0" width="${Math.max(2, X(z) - X(a))}" height="${H}" vector-effect="non-scaling-stroke">
        <title>bisect #${b.id}: a change is in these ${r.commits} commits</title></rect>`;
    })).join('');
    const strips = bisects.map((b) => `<div class="bisect-strip">
      <a href="#/bisects/${b.id}"><b>Bisect #${b.id}</b></a> · ${bisectWhat(b)} · ${badge(b.status)} · ${bisectNow(b)}
      ${b.status === 'running' ? `<span class="muted">(${b.state.open.map((r) => `#${seqOf(r.a) + 1}–#${seqOf(r.b)}`).join(', ')})</span>` : ''}
      <span class="tools"><a class="small-link" href="#/bisects/${b.id}">Open</a> ${bisectActions(b)}</span></div>`).join('');
    const ticks = [];
    for (let k = 0; k <= 4; k++) {
      const v = Math.round(from + ((to - from) * k) / 4), c = commits.find((x) => x.seq === v);
      ticks.push(`<span>#${v}${c ? ` · ${fmtDay(c.committedAt)}` : ''}</span>`);
    }
    const laneLabels = lanes.map((l, n) => (n === 0 || lanes[n - 1].cls !== l.cls ? `<div style="top:${laneY(n) + 1}px">${classLabel(l.cls)}</div>` : '')).join('');
    const map = `<div class="map-wrap"><div class="map-lanes" style="height:${H}px">${laneLabels}</div>
      <div class="map-plot"><svg id="map" data-from="${from}" data-to="${to}" viewBox="0 0 ${W} ${H}" width="100%" preserveAspectRatio="none" style="cursor:crosshair;height:${H}px;display:block">
      <rect x="0" y="${top}" width="${W}" height="${H - top - 8}" fill="var(--line)" opacity=".3"/>
      ${bands}
      ${items.map((it) => (it.kind === 'done' ? block(it) : ghost(it))).join('')}
      <rect id="brush" x="0" y="0" width="0" height="${H}" fill="var(--accent)" opacity=".15"/></svg>
      ${items.filter((it) => it.kind === 'done').map(label).join('')}</div></div>
      <div class="map-ticks">${ticks.join('')}</div>`;
    // the clicked block: its job in full (metrics with phases and CI, the commits of a segment)
    let detail = '';
    if (this.sel) {
      const j = await api.get(`/api/jobs/${this.sel}`).catch(() => null);
      if (j) {
        const queued = active.filter((x) => x.status === 'queued');
        const q = active.find((x) => x.id === j.id);
        detail = blockCard(j, q ? { eta: q, position: q.status === 'queued' ? queued.indexOf(q) + 1 : 0, of: queued.length } : null);
      }
    }
    const cov = rel.coverage || {};
    // A row per scenario says where master stands, not what each run did: a run that moved is a place where a change happened,
    // its width says how exactly that is known, and the level after it holds as long as the direct run of master against the
    // release still shows the change. The runs go from the longest to the shortest, so the most exact one is on top.
    const P = (v) => ((Math.max(from, Math.min(to, v)) - from) / Math.max(1, to - from)) * 100;
    const stripBlock = (sc, a, b, jobId, cls, tip) => (b <= from || a >= to ? ''
      : `<i class="ov-block ${cls}" data-ov-sc="${esc(sc)}" data-ov-job="${jobId}" style="left:${P(a)}%;width:${P(b) - P(a)}%" title="${esc(tip)}"></i>`);
    const firsts = new Set(scenarioGroups(scenarios).families.map((g) => g.scenarios[0].name));
    const overview = this.one ? '' : scenarios.map((x, i) => {
      const sc = x.name, points = (this.all.trends[i] || []).filter((p) => p.seq != null && p.seq > lo);
      const r = this.all.rels[i] || {}, whole = r.job && r.job.comparison && r.job.comparison.metrics && r.job.comparison.metrics[metric];
      const end = r.job && bySha.get(r.job.shaB);
      const runs = points.filter((p) => p.deltaPct[metric] != null).map((p) => ({ p, a: p.baseSeq != null ? p.baseSeq : p.seq - 1, b: p.seq,
        cls: mapLevel(metric, p.deltaPct[metric], p.thresholdPct[metric], p.metricVerdict[metric]) })).sort((u, v) => (v.b - v.a) - (u.b - u.a));
      const tip = ({ p, a, b }) => `${b - a > 1 ? `${b - a} commits` : '1 commit'} · ${fmt.sha(p.sha)} ${p.subject || ''} · Δ ${fmt.pct(p.deltaPct[metric])}`;
      // the shortest runs with a verdict: as close to a change as it is known; only the direct run says it is still there
      const moved = runs.filter((x) => x.cls === 'bad' || x.cls === 'good');
      const steps = moved.filter((x) => !moved.some((y) => y.cls === x.cls && y.a >= x.a && y.b <= x.b && y.b - y.a < x.b - x.a));
      const held = whole && whole.deltaPct != null && deltaLevel(whole.deltaPct, whole.thresholdPct, whole.verdict) ? ((HIGHER_BETTER.has(metric) ? whole.deltaPct < 0 : whole.deltaPct > 0) ? 'bad' : 'good') : '';
      const same = steps.filter((x) => x.cls === held), since = same.length ? Math.min(...same.map((x) => x.b)) : null;
      const level = runs.map((x) => stripBlock(sc, x.a, x.b, x.p.jobId, x.cls === 'over' ? 'over' : since != null && x.a >= since && end && x.a < end.seq ? `after ${held}` : '', tip(x))).join('');
      const changes = steps.map((x) => stripBlock(sc, x.a, x.b, x.p.jobId, `${x.b - x.a > 1 ? 'range ' : ''}${x.cls}`, tip(x))).join('');
      const nowhere = held && !same.length && end ? stripBlock(sc, lo, end.seq, r.job.id, `range ${held}`, `${esc(rel.tag || 'release')}: Δ ${fmt.pct(whole.deltaPct)} over ${end.seq - lo} commits, somewhere in here`) : '';
      const search = branchBisects.find((b) => b.scenario === sc);
      return `<div class="ov-row${this.open && sc === scenario ? ' on' : ''}${firsts.has(sc) ? ' first' : ''}" data-ov-row="${esc(sc)}">
        <div class="ov-name" title="${esc(sc)}">${esc(scName(sc))}${search ? ` <a class="bisect-mark" href="#/bisects/${search.id}" title="bisect #${search.id} is searching">${BISECT_MARKS[search.status]} #${search.id}</a>` : ''}</div>
        <div class="ov-strip">${level}${changes}${nowhere}</div>
        <div class="ov-delta">${whole && whole.deltaPct != null ? `<span class="dlt ${deltaClass(metric, whole.deltaPct, whole.thresholdPct, whole.verdict, true)}" title="master vs ${esc(rel.tag || 'the release')}">${fmt.pct(whole.deltaPct)}</span>` : ''}</div></div>`;
    }).join('');
    const legendOf = (items) => `<div class="map-legend">${items.map(([c, name, help]) => `<span><i class="sw ${c}"></i>${help ? term(help, name) : name}</span>`).join('')}</div>`;
    const legend = legendOf([['noise', 'noise', 'noise'], ['over', 'beyond the threshold', 'over'], ['bad', 'regression', 'regression'], ['good', 'improvement', 'improvement'], ['refined', 'refined by a split', 'refined'], ['none', 'not measured'],
      ['queued', `queued (${pending.filter((x) => x.job.status === 'queued').length})`], ['running', 'running'], ['band', 'suspected by a bisect', 'band']]);
    const levels = legendOf([['noise', 'quiet', 'noise'], ['after-bad', 'still worse than the release', 'stillWorse'], ['after-good', 'still better than the release', 'stillBetter'], ['range-bad', 'changed somewhere here', 'somewhere'],
      ['commit', 'the commit'], ['over', 'beyond the threshold', 'over'], ['none', 'not measured']]);
    return `
      <h1 class="with-tools">Timeline <span class="muted">since ${esc(rel.tag || 'the release')}, commits on one scale</span><span class="tools">Metric <select id="metric">${METRIC_COLS.slice(0, 4).map(([k, h]) => `<option value="${k}" ${k === metric ? 'selected' : ''}>${h}</option>`).join('')}</select></span></h1>
      ${this.one ? `<button class="ov-fold" data-ov-toggle><span class="chev">▸</span> All ${plural(scenarios.length, 'scenario')}</button>` : `<div class="card">
        <button class="ov-fold" data-ov-toggle><span class="chev">▾</span> All ${plural(scenarios.length, 'scenario')}</button>
        ${levels}
        <div class="ov">${overview}</div>
        <div class="map-ticks ov-ticks">${ticks.join('')}</div>
      </div>`}
      ${!this.open ? '<div class="muted small-text">Click a scenario for its segments, charts and runs.</div>' : `<h2 class="with-tools"><button class="ov-fold" data-detail-close title="close the details"><span class="chev">▾</span> ${esc(scName(scenario))}</button> <span class="tools">Scenario <select id="scenario">${scenarioOptions(scenarios, scenario)}</select></span></h2>
      <div class="card">
        ${this.one ? legend : ''}
        <div class="muted small-text">${rel.tag ? `${rel.unreleased} commits since ${esc(rel.tag)}: ` : ''}${cov.single ?? 0} one by one, ${cov.segment ?? 0} in segments, ${cov.none ?? 0} not measured${picked.size ? `, ${picked.size} cherry-picked into ${esc(rel.tag)} not counted` : ''} · click a block for details, drag to zoom
          ${this.range ? ' · <button class="small" id="zoom-reset">Show all</button>' : ''}</div>
        ${strips}
        ${map}
      </div>
      ${detail}
      <details class="card" id="charts-box" ${this.chartsOpen ? 'open' : ''}><summary><b>Charts</b> <span class="muted">levels over time with the phase range of every run (stand health, absolute values)</span></summary>
        <div id="charts" class="chart-grid" data-scenario="${esc(scenario)}" data-limit="1000" data-from="${from}" data-to="${to}"><div class="muted">loading charts…</div></div>
      </details>`}`;
  },
  bind(root) {
    bindJobActions(root, this);
    const pick = $('#scenario', root);
    if (pick) pick.addEventListener('change', (e) => { this.scenario = e.target.value; this.sel = null; refresh(); });
    $('#metric', root).addEventListener('change', (e) => { this.metric = e.target.value; refresh(); });
    const cb = $('#charts-box', root);
    if (cb) cb.addEventListener('toggle', () => { this.chartsOpen = cb.open; if (cb.open && window.benchCharts) window.benchCharts('timeline', {}); });
    const reset = $('#zoom-reset', root);
    if (reset) reset.addEventListener('click', () => { this.range = null; refresh(); });
    const svg = $('#map', root), brush = $('#brush', root);
    if (svg) {
      const from = Number(svg.dataset.from), to = Number(svg.dataset.to);
      const toSeq = (ev) => { const r = svg.getBoundingClientRect(); return from + ((ev.clientX - r.left) / r.width) * (to - from); };
      const toX = (ev) => { const r = svg.getBoundingClientRect(); return ((ev.clientX - r.left) / r.width) * 1000; };
      let start = null, startX = 0;
      svg.addEventListener('mousedown', (ev) => { start = toSeq(ev); startX = toX(ev); brush.setAttribute('x', startX); brush.setAttribute('width', 0); });
      svg.addEventListener('mousemove', (ev) => { if (start == null) return; const x = toX(ev); brush.setAttribute('x', Math.min(x, startX)); brush.setAttribute('width', Math.abs(x - startX)); });
      svg.addEventListener('mouseup', (ev) => {
        if (start == null) return;
        const end = toSeq(ev), a = Math.min(start, end), b = Math.max(start, end);
        start = null;
        if (b - a >= 3) { this.range = [Math.floor(a), Math.ceil(b)]; refresh(); return; }
        const blk = ev.target.closest('.map-block, .map-ghost');
        brush.setAttribute('width', 0);
        if (blk) { this.sel = Number(blk.dataset.job); refresh(); }
      });
    }
    root.addEventListener('click', (e) => {
      const b = e.target.closest('button[data-more]');
      if (b) guarded(() => moreRun(b.dataset.more));
      const sp = e.target.closest('button[data-split]');
      if (sp) guarded(() => splitJob(sp.dataset.split, sp.dataset.mode));
      if (e.target.closest('[data-ov-toggle]')) { this.one = !this.one; this.open = this.open || this.one; refresh(); }
      if (e.target.closest('[data-detail-close]')) { this.open = false; this.one = false; this.sel = null; refresh(); }
      // a block of the overview opens its run in the map below, a row just that scenario, and the open one closes
      const row = !e.target.closest('a') && e.target.closest('[data-ov-row]');
      if (row) {
        const blk = e.target.closest('[data-ov-job]'), same = this.open && this.scenario === row.dataset.ovRow && !blk;
        this.open = !same;
        this.scenario = row.dataset.ovRow;
        this.sel = blk ? Number(blk.dataset.ovJob) : null;
        refresh();
      }
    });
  },
};

// Scenario groups of the multi-select, a chip toggles all of a group. They follow the ids and the topology, so a new
// scenario joins them by itself; one that fits no family is listed under Other.
const SC_FAMILIES = [['Put/get', (s) => s.name.startsWith('putget')], ['Transactions', (s) => s.name.startsWith('tx')],
  ['SQL H2', (s) => s.name.startsWith('sql-h2')], ['SQL Calcite', (s) => s.name.startsWith('sql-calcite')]];
function scenarioGroups(list) {
  const known = (s) => SC_FAMILIES.some(([, f]) => f(s));
  const families = [...SC_FAMILIES, ['Other', (s) => !known(s)]].map(([name, f]) => ({ name, scenarios: list.filter(f) })).filter((g) => g.scenarios.length);
  const topology = [['1 server', (s) => s.servers === 1], ['2+ servers', (s) => s.servers > 1]].map(([name, f]) => ({ name, scenarios: list.filter(f) })).filter((g) => g.scenarios.length);
  return { families, chips: [{ name: 'All', scenarios: list }, ...families, ...topology] };
}
const pickedAll = (names, picked) => names.every((n) => picked.has(n));
function scenarioPicker(list, picked) {
  const { families, chips } = scenarioGroups(list);
  return `<label>Scenarios <span data-picked>${picked.size} chosen</span> <span data-time></span></label>
    <div class="chips">${chips.map((c) => { const names = c.scenarios.map((s) => s.name); return `<button type="button" class="chip ${pickedAll(names, picked) ? 'on' : ''}" data-preset="${esc(names.join(','))}">${esc(c.name)} <span class="muted">${names.length}</span></button>`; }).join('')}</div>
    <div class="sc-groups">${families.map((g) => `<div><div class="sc-group">${esc(g.name)}</div>${g.scenarios.map((s) => `<label class="sc-item" title="${esc(s.description)}"><input type="checkbox" data-sc="${esc(s.name)}" ${picked.has(s.name) ? 'checked' : ''}> ${esc(scName(s.name))}</label>`).join('')}</div>`).join('')}</div>`;
}
// the picker keeps the set up to date: a chip toggles all of its group. Next to it stands how long the choice takes:
// a job per scenario plus `builds` new builds, "a run" of a schedule when `per` says so
function bindScenarioPicker(form, picked, { builds = 0, per = '' } = {}) {
  const time = async () => {
    const names = [...picked], out = $('[data-time]', form);
    const q = new URLSearchParams({ scenario: names.join(','), type: 'abba', jobs: names.length, shas: '', extraBuilds: builds, priority: 2 });
    const e = names.length ? await api.get(`/api/estimate?${q}`).catch(() => null) : null;
    out.textContent = e ? `· ≈ ${mins(e.totalSec)}${per ? ` ${per}` : ''}` : '';
  };
  time();
  const sync = () => {
    time();
    form.querySelectorAll('[data-sc]').forEach((i) => { i.checked = picked.has(i.dataset.sc); });
    form.querySelectorAll('[data-preset]').forEach((b) => b.classList.toggle('on', pickedAll(b.dataset.preset.split(','), picked)));
    $('[data-picked]', form).textContent = `${picked.size} chosen`;
  };
  form.addEventListener('click', (e) => {
    const p = e.target.closest('[data-preset]');
    if (!p) return;
    const names = p.dataset.preset.split(','), on = pickedAll(names, picked);
    names.forEach((n) => (on ? picked.delete(n) : picked.add(n)));
    sync();
  });
  form.addEventListener('change', (e) => {
    const n = e.target.dataset.sc;
    if (!n) return;
    if (e.target.checked) picked.add(n); else picked.delete(n);
    sync();
  });
}
const compareResult = (c) => [[c.regressions, 'regression', 'regression'], [c.improvements, 'improvement', 'improvement'], [c.noChange, 'no-change', 'no change']]
  .filter(([n]) => n).map(([n, cls, w]) => `<span class="badge ${cls}">${n} ${w}</span>`).join(' ');
const comparisonActive = (c) => c.status === 'queued' || c.status === 'running';
// the status of a comparison is that of its jobs: with all of them measured its bisects can go on for hours
const comparisonBadge = (c) => badge(c.status === 'done' && c.searching ? 'searching' : c.status);

views.compare = {
  poll: 15000,
  live() { return this.active; },
  async render() {
    const [settings, scenarios, list] = await Promise.all([api.get('/api/settings'), api.get('/api/scenarios'), api.get('/api/comparisons?limit=30')]);
    const branch = settings['repo.branch'];
    const form = this.formState || { base: '', target: '' };
    const mode = this.mode || 'pr';
    this.active = list.some((c) => comparisonActive(c) || c.doneAt);
    this.picked = this.picked || new Set([settings['autorun.scenario'].split(',')[0].trim()]);
    return `
      <h1>Compare <span class="muted">PR vs master or any two refs, over a set of scenarios</span></h1>
      <div class="row">
        <div class="card" style="flex:3 1 520px">
          <div class="seg" role="tablist">
            <button type="button" data-mode="pr" class="${mode === 'pr' ? 'on' : ''}">PR vs ${esc(branch)}</button>
            <button type="button" data-mode="refs" class="${mode === 'refs' ? 'on' : ''}">Ref vs ref</button>
          </div>
          <form id="compare">
            ${mode === 'pr' ? `
            <label>Pull request: GitHub link or pr/N</label>
            <input name="target" value="${esc(form.target)}" placeholder="${esc(repoWeb || 'https://github.com/org/repo')}/pull/13704" required>
            <div class="muted" style="margin:4px 0 8px">A = current ${esc(branch)}, B = ${esc(branch)} + PR (GitHub's test merge <code>refs/pull/N/merge</code>)</div>` : `
            <label>Target (B): commit, tag, branch or PR — GitHub link, pr/N, sha</label>
            <input name="target" value="${esc(form.target)}" placeholder="2.18.0, ${esc(repoWeb || 'https://github.com/org/repo')}/commit/…" required>
            <label>Baseline (A): commit, branch or tag — empty = parent for a commit already in ${esc(branch)}, merge-base otherwise</label>
            <input name="base" value="${esc(form.base)}" placeholder="2.17.0, ignite-2.18, a sha, …">`}
            ${scenarioPicker(scenarios, this.picked)}
            <div class="actions"><button class="primary"${hint('ABBA')}>${mode === 'pr' ? `Queue ABBA: ${esc(branch)} vs ${esc(branch)} + PR` : 'Queue ABBA: B vs A'}</button></div>
          </form>
        </div>
        <div class="card" style="flex:2 1 340px">
          <h2 style="margin-top:0">Comparisons</h2>
          ${list.map((c) => `<div class="cmp-item"><div><a href="#/comparisons/${c.id}"><b>${esc(c.label)}</b></a>${prLink(c.refB)} <span class="muted">#${c.id}</span></div>
            <div class="cmp-meta">${comparisonActive(c) ? `${badge(c.status)} ${c.measured} of ${c.total}` : c.searching ? comparisonBadge(c) : ''} ${compareResult(c)}
              <span class="muted" ${c.doneAt ? 'title="its jobs and bisects, one after another"' : ''}>${c.doneAt ? `ends ${etaStart(c.doneAt)}` : fmt.ago(c.finishedAt || c.createdAt)}</span></div></div>`).join('') || '<div class="muted">no comparisons yet</div>'}
        </div>
      </div>
      <details data-notes ${this.notes ? 'open' : ''}><summary class="muted">How refs are resolved</summary>
        <ul class="muted">
          <li>Both refs are resolved to commits once, when the comparison is queued: every scenario measures the same two commits.</li>
          <li><code>pr/N</code> fetches <code>refs/pull/N/head</code>. GitHub links work in both fields: <code>…/pull/N</code>, <code>…/pull/N/commits/SHA</code>, <code>…/commit/SHA</code>, <code>…/tree/BRANCH</code>, <code>…/releases/tag/TAG</code>.</li>
          <li>For a PR with an empty baseline, B is GitHub's test merge of the PR into the current branch (<code>refs/pull/N/merge</code>) and A is the branch commit it was merged into: the delta is exactly what merging the PR now would change. It needs an open PR without conflicts.</li>
          <li>To measure the PR head on its own old base instead, put the merge-base or any ref into the baseline field.</li>
          <li>Release tags work too: <code>2.17.0</code> vs <code>2.18.0</code>.</li>
          <li>Results stay here: they never enter Dashboard, Commits or the noise floors.</li>
        </ul>
      </details>`;
  },
  bind(root) {
    root.querySelectorAll('.seg button[data-mode]').forEach((b) => b.addEventListener('click', () => { this.mode = b.dataset.mode; refresh(); }));
    const f = $('#compare', root);
    root.querySelectorAll('details[data-notes]').forEach((d) => d.addEventListener('toggle', () => { this.notes = d.open; }));
    bindScenarioPicker(f, this.picked, { builds: 2 });
    f.addEventListener('input', () => { this.formState = { ...(this.formState || {}), ...Object.fromEntries(new FormData(f)) }; });
    f.addEventListener('submit', (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(f));
      guarded(async () => {
        if (!this.picked.size) { toast('choose at least one scenario', true); return; }
        const names = [...f.querySelectorAll('[data-sc]')].map((i) => i.dataset.sc).filter((n) => this.picked.has(n));
        // PR mode never sends a baseline: the server then compares master vs master + PR
        const pr = this.mode !== 'refs', refs = pr ? [] : [d.base, d.target].filter(Boolean);
        const shas = await Promise.all(refs.map((r) => api.post('/api/resolve', { ref: r }).then((c) => c.sha))); // a wrong ref fails here
        if (!(await confirmLaunch({ title: pr ? 'Compare PR vs master' : 'Compare refs', scenarios: names, jobs: names.length, shas, extraBuilds: 2 - shas.length, priority: 2,
          what: pr ? `master vs master + <b>${esc(d.target)}</b>` : `<b>${esc(d.base || 'merge base')}</b> vs <b>${esc(d.target)}</b>`,
          note: 'debug run: not shown on Dashboard, Timeline or Commits' }))) return;
        const c = await api.post('/api/comparisons', { base: pr ? '' : d.base, target: d.target, scenarios: names, priority: 2 });
        toast(`comparison #${c.id} queued: ${c.label}`);
        location.hash = `#/comparisons/${c.id}`;
      });
    });
  },
};

// A result: the commits found, each with what it moved in which scenario, the largest change first, then what moved with no
// commit to blame yet. A commit is filled, what is not pinned down stays soft so that it does not read as a fact.
const effectChip = (metric, d, id, pinned) => {
  const worse = HIGHER_BETTER.has(metric) ? d < 0 : d > 0, cls = pinned ? (worse ? 'v-worse' : 'v-better') : worse ? 'hl-worse' : 'hl-better';
  return `<a class="effect" ${id ? `href="#/bisects/${id}" title="bisect #${id}"` : ''}>${esc(METRIC_NAMES[metric] || metric)} <span class="dlt ${cls}">${fmt.pct(d)}</span></a>`;
};
// The states of what moved, in the order to look at them, the cheapest search first. A group says what is left to do: the matrix
// and its legend show the group, the details of a cell and Result the state. What each one means is in HELP.
const ATT_GROUPS = { search: ['●', 'To search'], running: ['↻', 'Searching'], nocommit: ['≈', 'No single commit'], explained: ['✓', 'Explained'] };
const ATT_STATES = { narrowed: ['search', 'Narrowed'], new: ['search', 'New runs'], inconclusive: ['search', 'Needs a deep search'], unexplored: ['search', 'Not searched'], partial: ['search', 'Partly explained'],
  stopped: ['search', 'Stopped'], running: ['running', 'Searching'], spread: ['nocommit', 'Spread over small changes'], lost: ['nocommit', 'Did not repeat'], explained: ['explained', 'Explained'] };
// A card of its own, so that it reads as the conclusion of the page. On the left what was found, a block per commit with a
// strip for the way it moved things; on the right what is left, by kind.
// found: [{ sha, subject, scenario, metric, d, id }]; loose: [{ kind, size, n, range, href, count, scenario, cells, action }]
function resultBlock(title, found, loose, { going, openTitle }) {
  const commits = new Map();
  found.forEach((x) => {
    const c = commits.get(x.sha) || { sha: x.sha, subject: x.subject, most: 0, scenarios: new Map(), worse: 0, better: 0 };
    c.most = Math.max(c.most, Math.abs(x.d));
    c.subject = c.subject || x.subject;
    c[(HIGHER_BETTER.has(x.metric) ? x.d < 0 : x.d > 0) ? 'worse' : 'better']++;
    c.scenarios.set(x.scenario, [...(c.scenarios.get(x.scenario) || []), x]);
    commits.set(x.sha, c);
  });
  const list = [...commits.values()].sort((x, y) => y.most - x.most);
  if (!list.length && !loose.length) return '';
  const commitBlock = (c) => `<div class="res-commit ${c.worse && c.better ? 'mixed' : c.worse ? 'worse' : 'better'}">
    <div class="res-commit-head"><span class="res-subject">${subjectHtml(c.subject)}</span><a class="mono" href="#/commits/${esc(c.sha)}">${fmt.sha(c.sha)}</a></div>
    ${[...c.scenarios].map(([scenario, effects]) => `<div class="res-line"><span class="res-sc">${esc(scName(scenario))}</span><span>${effects.map((x) => effectChip(x.metric, x.d, x.id, true)).join('')}</span></div>`).join('')}</div>`;
  const openGroup = ([kind, [group, name]]) => {
    const rows = loose.filter((x) => x.kind === kind).sort((x, y) => y.size - x.size);
    if (!rows.length) return '';
    // the same range on every row is said once, in the head of the group
    const same = rows.every((x) => x.n === rows[0].n);
    return `<div class="res-group" data-group="${group}"><div class="res-group-head"><b>${name}</b>${same ? ` · ${rows[0].range}` : ''} <span class="muted">${esc(HELP[kind] || '')}</span></div>
      ${rows.map((x) => `<div class="res-open ${same ? 'one-range' : ''}"><span class="res-sc">${x.href ? `<a href="${x.href}" title="the run that shows it">${esc(scName(x.scenario))}</a>` : esc(scName(x.scenario))}</span>
        <span class="res-fx">${x.cells}</span>${same ? '' : `<span class="res-range">${x.range}</span>`}<span class="res-act">${x.action}</span></div>`).join('')}</div>`;
  };
  const scenarios = new Set(found.map((x) => x.scenario));
  return `<div class="card result">
    <div class="res-head"><h2>${title}</h2>
      <span class="res-stat found"><b>${list.length}</b> ${list.length === 1 ? 'commit' : 'commits'} in ${plural(scenarios.size, 'scenario')}${going ? ' so far' : ''}</span>
      <span class="res-stat open"><b>${loose.reduce((sum, x) => sum + (x.count || 1), 0)}</b> ${esc(openTitle.toLowerCase())}</span></div>
    <div class="res-grid ${list.length && loose.length ? 'two' : ''}">
      ${list.length ? `<section data-group="explained"><div class="tile-label">Found</div>${list.map(commitBlock).join('')}</section>` : ''}
      ${loose.length ? `<section><div class="tile-label">${esc(openTitle)}</div>${Object.entries(ATT_STATES).map(openGroup).join('')}</section>` : ''}
    </div></div>`;
}
const rangeOf = (n, a, b) => `<span title="${fmt.sha(a)}..${fmt.sha(b)}">${plural(n, 'commit')}</span>`;
// What the searches of a comparison came to
function comparisonResult(searches, c) {
  const found = searches.flatMap(({ b }) => foundOf(b).map((f) => ({ sha: f.b, subject: b.subjects[f.b], scenario: b.scenario, metric: b.metric, d: f.result.deltaPct, id: b.id })));
  // a range or a whole segment that moved: spread over small changes, too close to the noise, still searched, or not searched at all
  const loose = [];
  const add = (kind, scenario, n, a, b, cells, size, action = '') => loose.push({ kind, scenario, n, range: rangeOf(n, a, b), cells, size, action });
  searches.forEach(({ b }) => {
    b.state.findings.filter((f) => f.kind === 'spread').forEach((f) => add('spread', b.scenario, f.commits, f.a, f.b, effectChip(b.metric, f.result.deltaPct, b.id), Math.abs(f.result.deltaPct)));
    if (b.status === 'inconclusive' && b.state.root) add('inconclusive', b.scenario, c.commits, b.shaA, b.shaB, effectChip(b.metric, b.state.root.deltaPct, b.id), Math.abs(b.state.root.deltaPct), deepButton(b.id));
    if (bisectActive(b)) b.state.open.forEach((r) => { const d = r.cur ? r.cur.deltaPct : r.est; add(b.status, b.scenario, r.commits, r.a, r.b, effectChip(b.metric, d, b.id), Math.abs(d)); });
  });
  bisectCandidates(c).forEach((r) => {
    const m = r.comparison.metrics, moved = rankedMetrics(m).filter((k) => k !== 'heapAfterGcMb'); // heap after GC alone says little
    if (moved.length) add('unexplored', r.scenario, c.commits, c.shaA, c.shaB, moved.map((k) => effectChip(k, m[k].deltaPct)).join(''), Math.max(...moved.map((k) => Math.abs(m[k].deltaPct))), bisectBtn(r.job.id));
  });
  return resultBlock(`Result <span class="muted">${esc(c.label)}</span>`, found, loose, { going: searches.some(({ b }) => bisectActive(b)), openTitle: 'Not pinned down' });
}
// What the master card comes to: the commits that explain master, then what is worth a look.
// The metrics of a scenario that wait for the same search share a row.
function attentionResult(a) {
  const lines = a.rows.flatMap((r) => r.lines.map((l) => ({ ...l, scenario: r.scenario, busy: r.activeBisect })));
  const found = lines.flatMap((l) => l.commits.map((c) => ({ sha: c.sha, subject: c.subject, scenario: l.scenario, metric: l.metric, d: c.deltaPct, id: c.bisectId || l.bisectId })));
  const open = new Map();
  lines.filter((l) => l.status !== 'explained').forEach((l) => {
    const n = l.status === 'new' ? l.span : l.status === 'narrowed' ? l.narrowSpan : l.rangeCommits;
    const key = [l.scenario, l.status, n, l.jobId].join('|'), g = open.get(key) || { l, n, cells: [], size: 0 };
    const d = l.status === 'partial' ? l.restPct : l.deltaPct;
    g.cells.push(effectChip(l.metric, d, l.bisectId));
    g.size = Math.max(g.size, Math.abs(d));
    open.set(key, g);
  });
  const loose = [...open.values()].map(({ l, n, cells, size }) => ({ kind: l.status, size, n, range: plural(n, 'commit'), href: l.jobId ? `#/jobs/${l.jobId}` : '',
    count: cells.length, scenario: l.scenario, cells: cells.join(''), action: attentionAction(l, l.busy) }));
  return resultBlock(`Result <span class="muted">master vs ${esc(a.tag || 'the release')}</span>`, found, loose, { going: lines.some((l) => l.status === 'running'), openTitle: 'Still open' });
}
// One comparison, by its link: a row per scenario with what its finished runs show, repeats pooled
views.comparison = {
  poll: 15000,
  live() { return this.active; },
  async render(params) {
    const c = await api.get(`/api/comparisons/${params.id}`);
    const going = comparisonActive(c);
    this.active = going || c.bisects.some((b) => b.status === 'running');
    const jobs = c.rows.map((r) => r.job), running = jobs.find((j) => j.status === 'running');
    const queued = jobs.filter((j) => j.status === 'queued').length;
    const latest = new Map(c.bisects.map((b) => [b.scenario, b])); // oldest first: the latest one of a scenario stays
    const settled = jobs.every((j) => j.status === 'done'); // then a status per scenario says nothing
    const searches = latestBisects(c.bisects);
    const cols = this.allCols ? METRIC_COLS : METRIC_COLS.slice(0, 4); // how long nodes start and load is noise of the stand
    const rows = c.rows.map((r) => {
      const j = r.job, m = (r.comparison && r.comparison.metrics) || {}, b = latest.get(r.scenario);
      const moved = j.status === 'done' && c.commits > 1 && !(b && bisectActive(b)) ? rankedMetrics(m) : [];
      const cells = cols.map(([k, , dg]) => {
        const v = m[k] ? valDelta(k, m[k].meanB, dg, m[k].deltaPct, m[k].verdict, m[k].thresholdPct) : '–';
        return `<td class="num">${moved.includes(k) ? `<button class="cell-btn" data-bisect="${j.id}" data-metric="${k}" title="bisect ${esc(METRIC_NAMES[k])}">${v}</button>` : v}</td>`;
      }).join('');
      const state = j.status === 'running' ? `<div class="muted">${esc(j.stage || '')} · ${j.progress || 0}%</div>` : j.status === 'queued' ? `<div class="muted">${etaStart(j.etaStartAt)}</div>` : '';
      const status = settled ? '' : `<td>${badge(j.status)}${state}</td>`;
      return `<tr>
        <td class="job-cell"><a href="#/jobs/${j.id}">${esc(scName(r.scenario))}</a>${runsMark(r)}${b ? ` <a class="bisect-mark" href="#/bisects/${b.id}" title="bisect #${b.id} of ${esc(METRIC_NAMES[b.metric] || b.metric)}: ${esc(b.status)}">${BISECT_MARKS[b.status] || ''} #${b.id}</a>` : ''}${j.error ? `<div class="error one-line" title="${esc(j.error)}">${esc(j.error)}</div>` : ''}</td>
        ${status}${cells}<td>${badge(r.verdict)}</td>
        <td class="row-action">${j.status === 'queued' || j.status === 'running' ? '' : r.comparison ? `${moreRunBtn(j.id)}${moved.length ? ` ${bisectBtn(j.id)}` : ''}` : `<button class="small" data-retry="${j.id}">Retry</button>`}</td></tr>`;
    }).join('');
    const stateOf = [...new Set(searches.map((g) => g.b.status))].map((s) => `<span class="badge ${esc(s)}">${searches.filter((g) => g.b.status === s).length} ${esc(s)}</span>`).join(' ');
    const activeBisects = c.bisects.filter(bisectActive).length;
    return `
      <h1>${esc(c.label)} ${comparisonBadge(c)}${prLink(c.refB)}</h1>
      <div class="muted" style="margin:-6px 0 12px">comparison #${c.id} · ${plural(c.total, 'scenario')} · queued ${fmt.date(c.createdAt)}${c.finishedAt ? ` · ${c.searching ? 'measured' : 'ended'} ${fmt.date(c.finishedAt)}` : ''} · not shown on Dashboard, Timeline or Commits</div>
      <div class="row"><div class="card" style="flex:1 1 520px">
        <dl class="kv">
          <dt>Baseline (A)</dt><dd>${refLine(c.refA, c.shaA, c.subjectA)} <span class="muted">${fmtDay(c.dateA)}</span></dd>
          <dt>Target (B)</dt><dd>${refLine(c.refB, c.shaB, c.subjectB)} <span class="muted">${fmtDay(c.dateB)}</span></dd>
          <dt>Measured</dt><dd>${c.measured} of ${c.total} ${compareResult(c)}</dd>
          ${c.bisects.length ? `<dt>Bisects</dt><dd>${stateOf}</dd>` : ''}
          ${running ? `<dt>Now</dt><dd>${jobLink(running)} ${esc(scName(running.scenario))} <span class="muted">${esc(running.stage || '')} · ${running.progress || 0}%</span></dd>` : ''}
          ${c.doneAt ? `<dt title="its jobs and bisects, one after another">Ends</dt><dd>${etaStart(c.doneAt)}</dd>` : ''}
        </dl>
        ${going ? `<div class="progress"><div style="width:${c.total ? Math.round(100 * c.measured / c.total) : 0}%"></div></div>` : ''}
        <div class="actions-inline" style="margin-top:10px">${going || activeBisects
          ? `<button class="small" data-comparison-cancel="${c.id}" data-running="${running ? 1 : 0}" data-queued="${queued}" data-bisects="${activeBisects}">Cancel</button>`
          : `<button class="small" data-comparison-delete="${c.id}">Delete</button>`}${c.commits > 1 && bisectCandidates(c).length
          ? ` <button class="small" data-bisect-all="${c.id}" title="one bisection per scenario that moved: choose them and their metrics first">Bisect deviations</button>` : ''}</div>
      </div></div>
      <table class="commits runs-table comparison-rows"><thead><tr><th>Scenario</th>${settled ? '' : '<th>Status</th>'}${cols.map(metricHead).join('')}<th>Verdict</th><th></th></tr></thead><tbody>${rows}</tbody></table>
      <button class="att-more" data-all-cols>${this.allCols ? 'Hide start and preload' : 'Show start and preload'}</button>
      <div class="muted">Values are B, Δ is B vs A. One more run of a scenario is pooled with its previous runs here. A Δ with a verdict starts a bisection of that metric.</div>
      ${comparisonResult(searches, c)}
      ${c.bisects.length ? `<h2>Bisects <span class="muted">which commits moved the metric between A and B</span></h2>
      <table class="commits"><thead><tr><th>#</th><th>Scenario</th><th>Metric</th><th>Status</th><th>Result</th><th></th></tr></thead>
      <tbody>${searches.map(({ b, earlier }) => `<tr><td style="white-space:nowrap"><a href="#/bisects/${b.id}">#${b.id}</a>${earlierMark({ earlier })}</td><td>${esc(scName(b.scenario))}</td><td>${bisectWhat(b)}</td>
        <td>${badge(b.status)}</td><td class="bisect-result">${bisectResult(b)}</td><td class="row-action">${bisectActions(b)}</td></tr>`).join('')}</tbody></table>` : ''}`;
  },
  bind(root) {
    bindJobActions(root, this);
    root.addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.more) guarded(() => moreRun(b.dataset.more));
      if (b.dataset.allCols !== undefined) { this.allCols = !this.allCols; refresh(); }
      if (b.dataset.bisectAll) guarded(() => startBisects(b.dataset.bisectAll));
      if (b.dataset.comparisonCancel) guarded(async () => {
        const id = b.dataset.comparisonCancel, q = Number(b.dataset.queued), n = Number(b.dataset.bisects);
        const body = `<div>${q ? `${plural(q, 'queued job')} leave${q === 1 ? 's' : ''} the queue. ` : ''}${b.dataset.running === '1' ? 'The running job stops at once. ' : ''}${n ? `${plural(n, 'active bisection')} stop${n === 1 ? 's' : ''} too. ` : ''}What is finished stays.</div>`;
        if (!(await modal(`Cancel comparison #${id}?`, body, 'Cancel comparison', { danger: true }))) return;
        await api.post(`/api/comparisons/${id}/cancel`);
        toast(`comparison #${id} cancelled`);
        refresh();
      });
      if (b.dataset.comparisonDelete) guarded(async () => {
        const id = b.dataset.comparisonDelete;
        if (!(await modal(`Delete comparison #${id}?`, '<div>Its jobs, their runs and files go too. This cannot be undone.</div>', 'Delete', { danger: true }))) return;
        await api.del(`/api/comparisons/${id}`);
        toast(`comparison #${id} deleted`);
        location.hash = '#/compare';
      });
    });
  },
};

// Scenarios: one Java class each in the harness (org.apache.ignite.firewatch.scenarios); the page shows what @Bench says about the
// cluster and the load, and the source of the class and its base classes exactly as the harness jar carries it
const javaCode = (src) => esc(src).replace(/(\/\*[\s\S]*?\*\/|\/\/[^\n]*)/g, '<span class="code-comment">$1</span>');
// The summary of a scenario, cut out of its real source: every scenario method as it is in effect (the most specific
// override, plus the overridden one when it calls super) and the constants, without imports, javadoc and boilerplate
function methodChain(sources, name) {
  const out = [];
  for (const f of sources) { // most specific class first
    const m = new RegExp(`(?:@Override\\s+)?(?:public|protected)[^;{(]*\\s${name}\\s*\\(`).exec(f.text);
    if (!m) continue;
    let i = f.text.indexOf('{', m.index), depth = 0, end = i;
    for (; end < f.text.length; end++) {
      if (f.text[end] === '{') depth++;
      else if (f.text[end] === '}' && --depth === 0) break;
    }
    const code = f.text.slice(m.index, end + 1).replace(/^@Override\s+/, '');
    const lines = code.split('\n');
    const pad = Math.min(...lines.slice(1).filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length), 4);
    out.unshift({ cls: f.file.replace(/\.java$/, ''), code: [lines[0], ...lines.slice(1).map((l) => l.slice(pad))].join('\n') });
    if (!code.includes(`super.${name}(`)) break;
  }
  return out;
}
function scenarioSummary(d) {
  const src = d.sources || [];
  const block = (title, names, empty) => {
    const parts = names.flatMap((n) => methodChain(src, n));
    return `<div class="code-title">${title}</div>${parts.length
      ? `<pre class="code">${parts.map((p) => `<span class="code-comment">// ${esc(p.cls)}</span>\n${javaCode(p.code)}`).join('\n\n')}</pre>`
      : `<div class="muted small-text">${empty}</div>`}`;
  };
  const consts = src.flatMap((f) => f.text.split('\n').filter((l) => /\bstatic final\b/.test(l) && !/PASSWORD/.test(l)).map((l) => l.trim()));
  return `<div class="code-grid">
    <div>
      ${block('Server node <span class="muted">changes to Ignite defaults</span>', ['configureServer'], 'Ignite defaults')}
      ${block('Loader client', ['configureThinClient', 'configureClient'], 'defaults (addresses and discovery are set by the harness)')}
      ${block('Cache', ['cacheConfiguration'], 'none')}
    </div>
    <div>
      ${consts.length ? `<div class="code-title">Constants</div><pre class="code">${javaCode(consts.join('\n'))}</pre>` : ''}
      ${block('Load <span class="muted">setup once, then op() in every thread</span>', ['setup', 'op'], '–')}
    </div>
  </div>`;
}
views.scenarios = {
  async render() {
    const scenarios = await api.get('/api/scenarios');
    const described = await Promise.all(scenarios.map((sc) => api.get(`/api/scenarios/${encodeURIComponent(sc.name)}/describe`).catch((e) => ({ name: sc.name, error: e.message }))));
    if (!this.open) this.open = new Set(); // all folded at first; opened rows stay open across re-renders
    const fact = (label, value, sub) => `<div class="stat"><div class="stat-label">${label}</div><div class="fact-value">${value}</div><div class="muted small-text">${sub}</div></div>`;
    const detail = (d) => {
      const sv = d.servers || {}, ld = d.loader || {};
      const clients = `${ld.count > 1 ? `${ld.count} ` : ''}${ld.client}`;
      return `
        <div class="muted scenario-desc">${plural(sv.count, 'server')} · ${clients} client × ${ld.threads} threads · ${d.warmupSec + d.measureSec} s per phase${(d.modules || []).length ? ` · ignite-core + ${d.modules.map(esc).join(', ')}` : ''}</div>
        <div class="facts">
          ${fact('Servers', plural(sv.count, 'node'), `heap ${sv.heapMb} MB · ${esc((sv.jvmOpts || []).join(' '))}`)}
          ${fact('Load', `${clients} × ${ld.threads}`, `client JVM${ld.count > 1 ? 's' : ''} × threads · heap ${ld.heapMb} MB`)}
          ${fact('Pacing', ld.rateOpsPerSec ? `${fmt.num(ld.rateOpsPerSec)} ops/s` : 'closed loop', ld.rateOpsPerSec ? 'open loop, latency from the scheduled time' : 'next op right after the previous one')}
          ${fact('Phase', `${d.warmupSec} + ${d.measureSec} s`, 'warmup + measure, fresh JVMs')}
          ${fact('Class', esc(d.className), `version ${d.version}: raise it when what is measured changes`)}
        </div>
        ${scenarioSummary(d)}
        <details class="scenario-json"><summary class="muted">Full source: ${(d.sources || []).map((f) => esc(f.file)).join(', ')}</summary>
        ${(d.sources || []).map((f, i) => `<div class="code-title">${esc(f.file)} ${i === 0 ? '<span class="muted">the scenario</span>' : '<span class="muted">base class</span>'}</div>
          <pre class="code">${javaCode(f.text.replace(/^(package|import) [^\n]*\n/gm, '').replace(/^\s*\n+/, ''))}</pre>`).join('')}
        </details>`;
    };
    const row = (s) => {
      const d = described.find((x) => x.name === s.name), open = this.open.has(s.name);
      if (d.error) return `<tr><td colspan="2" class="error">${esc(d.name)}: ${esc(d.error)}</td></tr>`;
      return `<tr class="click-row" data-scenario="${esc(d.name)}"><td class="sc-name"><span class="chev">${open ? '▾' : '▸'}</span> <b>${esc(d.title || d.name)}</b></td><td class="muted">${esc(d.description || '')}</td></tr>
        <tr class="row-detail" ${open ? '' : 'hidden'}><td colspan="2">${detail(d)}</td></tr>`;
    };
    return `<h1>Scenarios <span class="muted">Java classes in the harness: add a file to add a scenario, delete it to remove one</span></h1>
      ${scenarioGroups(scenarios).families.map((g) => `<h2>${esc(g.name)}</h2><table class="scenario-list"><tbody>${g.scenarios.map(row).join('')}</tbody></table>`).join('')}`;
  },
  bind(root) {
    root.addEventListener('click', (e) => {
      const r = e.target.closest('tr[data-scenario]');
      if (!r || e.target.closest('a')) return;
      const n = r.dataset.scenario, open = !this.open.has(n);
      if (open) this.open.add(n); else this.open.delete(n);
      unfoldRow(r, open);
    });
  },
};

const AUTORUN_MODES = [
  ['off', 'Off', 'New commits are only listed: nothing runs by itself.'],
  ['segments', 'Segments', 'One ABBA per batch of commits: the head against the end of the previous segment. Cheap and covers every commit; Split or Bisect then narrows a segment down.'],
  ['abba-vs-parent', 'Every commit', 'An ABBA of each commit against its parent. Exact, but a build and about 9 minutes per commit and scenario.'],
  ['single', 'Single phase', 'One phase of each commit, no baseline. A quick level for the charts: no Δ and no verdict.'],
];
// A recurring run, the A/A or master vs release: off, or how fresh it is kept (Freshness on the server)
const keepField = (name, value) => `<label class="keep-days">every <input name="${name}" type="number" min="1" value="${esc(value)}"> days</label>`;
function scheduleForm(p, title, text, settings, scenarios, picked) {
  const on = settings[`${p}.mode`] === 'keep', last = settings[`${p}.lastDate`];
  return `<form id="schedule-${p}" class="card">
    <h2 style="margin-top:0">${title} <span class="muted">${text} Last run: ${last ? fmtDay(last) : 'never'}.</span></h2>
    <div class="mode-row"><label class="mode-opt"><input type="radio" name="${p}.mode" value="off" ${on ? '' : 'checked'}><span>Off</span></label>
      <label class="mode-opt"><input type="radio" name="${p}.mode" value="keep" ${on ? 'checked' : ''}><span>Keep fresh</span></label>
      <span data-if-on>${keepField(`${p}.keepDays`, settings[`${p}.keepDays`] || '7')}</span></div>
    <div data-if-on>${scenarioPicker(scenarios, picked)}</div>
    <div class="actions"><button class="primary">Save</button></div>
  </form>`;
}
// What can go wrong with the stand, a line each: ok, warn (worth a look), bad (needs a hand), info (a fact, not a state)
function standChecks(health, stand, builds, noises) {
  const w = health.watcher, s = health.system, cur = health.worker.job;
  const freeGb = Math.min(s.dataFreeMb, s.cacheFreeMb) / 1024, failed = builds.filter((b) => b.status === 'failed').length;
  const few = noises.filter((n) => (n.calibrations || []).length < OWN_FLOORS).length;
  const over = noises.filter((n) => { const l = (n.calibrations || [])[0]; return l && VERDICT_KEYS.some((k) => l.deltaPct[k] != null && Math.abs(l.deltaPct[k]) > (n.floors || {})[k]); }).length;
  const lastAa = noises.map((n) => (n.calibrations || [])[0]).filter(Boolean).map((c) => c.finishedAt).sort().pop();
  return [
    { name: 'Worker', level: health.worker.paused ? 'warn' : 'ok', text: health.worker.paused ? (cur ? `closing, ${jobLink(cur)} finishes` : 'closed for maintenance') : cur ? `running ${jobLink(cur)}` : 'idle',
    action: `<button class="small" id="maintenance" data-paused="${health.worker.paused ? 1 : 0}">${health.worker.paused ? 'Reopen' : 'Close for maintenance'}</button>` },
    { name: 'Watcher', level: w.lastError ? 'bad' : w.mirrorReady ? 'ok' : 'warn', hint: w.lastError || w.head || '', text: w.lastError ? esc(w.lastError)
      : w.mirrorReady ? `${esc(w.branch)} <span class="mono">${fmt.sha(w.head)}</span> · ${w.polling ? 'polling…' : fmt.ago(w.lastPollAt)}` : 'the mirror is being cloned',
    action: w.lastError ? '' : '<button class="small" id="poll">Poll now</button>' },
    { name: 'Disk', level: freeGb < 2 ? 'bad' : freeGb < 5 ? 'warn' : 'ok', text: `${fmt.num(freeGb, 1)} GB free · runs ${fmt.num(s.runsMb / 1024, 1)} GB · builds ${fmt.num(s.artifactsMb / 1024, 1)} GB` },
    { name: 'Memory', level: 'ok', text: `${fmt.num(s.memFreeMb)} of ${fmt.num(s.memTotalMb)} MB free · load ${fmt.num(s.loadAvg, 2)}` },
    { name: 'JVMs', level: s.strayJvms && !health.worker.busy ? 'warn' : 'ok', text: !s.strayJvms ? 'none left over' : health.worker.busy ? `${s.strayJvms} of the running job` : `${s.strayJvms} left over`,
      action: s.strayJvms && !health.worker.busy ? '<button class="small" id="kill" title="no job is running: these JVMs are leftovers">Kill all</button>' : '' },
    { name: 'Builds', level: failed ? 'warn' : 'ok', text: failed ? `${failed} of the last ${builds.length} failed` : `last ${builds.length} fine` },
    { name: 'Noise', level: over || few ? 'warn' : 'ok', text: `<a href="#/noise">${over ? `${over} of ${noises.length} over the floor` : few ? `default floors in ${few} of ${noises.length}` : 'own floors'}</a>${lastAa ? ` · A/A ${fmt.ago(lastAa)}` : ''}` },
    { name: 'Stand', level: stand.mode === 'remote' && !(stand.check && stand.check.ok) ? 'warn' : 'ok', text: stand.mode === 'remote' ? `${plural(stand.hosts.length, 'host')}${stand.check ? `, Check ${stand.check.ok ? 'passed' : 'failed'} ${fmt.ago(stand.check.at)}` : ', no Check yet'}` : 'this machine' },
    { name: 'Automation', level: 'info', text: `auto-run ${w.autorunEnabled ? 'on' : 'off'}` },
  ];
}
const CHECK_SIGNS = { ok: '✓', warn: '!', bad: '✗', info: '·' };
function standState(health, stand, builds, noises) {
  const checks = standChecks(health, stand, builds, noises);
  const bad = checks.filter((c) => c.level === 'bad').length, warn = checks.filter((c) => c.level === 'warn').length;
  const verdict = bad ? ['bad', `✗ ${plural(bad + warn, 'thing')} to fix`] : warn ? ['warn', `! ${plural(warn, 'thing')} to look at`] : ['ok', '✓ All is well'];
  return `<div class="card state">
    <div class="state-head"><b class="state-verdict ${verdict[0]}">${verdict[1]}</b>
      <span class="muted">${esc(health.version)} · up ${fmt.dur(new Date(Date.now() - health.uptimeSec * 1000).toISOString())} · ${esc(health.system.jdk)}</span></div>
    <div class="state-grid">${checks.map((c) => `<div class="state-item ${c.level}"><i>${CHECK_SIGNS[c.level]}</i><span class="state-name">${c.name}</span>
      <span class="state-text" ${c.hint ? `title="${esc(c.hint)}"` : ''}>${c.text}</span>${c.action || ''}</div>`).join('')}</div>
  </div>`;
}
const ticketRow = (key = '', l = { url: '', pr: '' }) => `<tr data-ticket><td><input name="key" value="${esc(key)}" placeholder="IGNITE" autocomplete="off" spellcheck="false"></td>
  <td><input name="url" value="${esc(l.url)}" placeholder="https://issues.apache.org/jira/browse/" autocomplete="off" spellcheck="false"></td>
  <td><input name="pr" value="${esc(l.pr)}" placeholder="${esc(defaultPrUrl() || 'https://github.com/org/repo/pull/')}" autocomplete="off" spellcheck="false"></td>
  <td><button type="button" class="small" data-ticket-remove>Remove</button></td></tr>`;
const SETTINGS_TABS = [['automation', 'Automation'], ['release', 'Release'], ['notify', 'Notifications'], ['limits', 'Limits'], ['stand', 'Stand'], ['system', 'System'], ['repository', 'Application']];
// Where the JVMs run: the setup of a new stand and the Stand section of Settings share these fields
const STAND_MODES = [['local', 'This machine', 'All JVMs run here, next to the application.'],
  ['remote', 'Remote Linux hosts', 'Server nodes and the loader run on these hosts over ssh; commits are still built here.']];
const HOST_INPUTS = [['address', 'Address', 'bench-1.lan'], ['user', 'User', 'bench'], ['port', 'SSH port', '22'], ['javaHome', 'JDK 17', '/usr/lib/jvm/java-17'], ['workDir', 'Work directory', '/opt/firewatch'], ['dataAddress', 'Data address', 'optional']];
const hostRow = (h = { port: 22, role: 'servers' }) => `<tr data-host>
  ${HOST_INPUTS.map(([k, , ph]) => `<td><input name="${k}" value="${esc(h[k] ?? '')}" placeholder="${ph}" autocomplete="off"></td>`).join('')}
  <td><select name="role">${['servers', 'loaders'].map((r) => `<option ${r === h.role ? 'selected' : ''}>${r}</option>`).join('')}</select></td>
  <td><button type="button" class="small" data-host-remove>Remove</button></td></tr>`;
function standFields(stand) {
  const hosts = stand.hosts.length ? stand.hosts : [{ port: 22, role: 'servers' }, { port: 22, role: 'loaders' }];
  return `<div class="mode-line"><div class="seg-radio">${STAND_MODES.map(([k, name]) => `<label><input type="radio" name="stand.mode" value="${k}" ${k === stand.mode ? 'checked' : ''}><span>${name}</span></label>`).join('')}</div>
      <span class="hint" data-mode-text>${(STAND_MODES.find(([k]) => k === stand.mode) || STAND_MODES[0])[2]}</span></div>
    <div data-if-remote ${stand.mode === 'remote' ? '' : 'hidden'}>
      <table class="hosts"><thead><tr>${HOST_INPUTS.map(([, h]) => `<th>${h}</th>`).join('')}<th>Role</th><th></th></tr></thead>
        <tbody>${hosts.map((h) => hostRow(h)).join('')}</tbody></table>
      <div class="step-foot"><button type="button" class="small" data-host-add>Add host</button>
        <span class="hint">ssh key: ${stand.sshKey ? `<code>${esc(stand.sshKey)}</code>` : 'the default of ssh'} · a host missing from known_hosts is ${stand.sshAcceptNewHosts ? 'taken, its key added' : 'refused'}</span>
        <details class="check-log step-log"><summary>About the hosts</summary><div class="hint">The same node always lands on the same host. Data address is an optional separate network for the traffic between nodes.
          The ssh key and known_hosts stay out of the database: they are files given to the application by <code>IGNITE_FIREWATCH_SSH_KEY</code> and <code>IGNITE_FIREWATCH_SSH_KNOWN_HOSTS</code>;
          <code>IGNITE_FIREWATCH_SSH_ACCEPT_NEW_HOSTS</code> takes the key of a host known_hosts lacks on first contact.</div></details></div>
    </div>`;
}
// The hosts as Check saw them or as the form has them: a report is good for exactly these
const hostsKey = (hosts) => JSON.stringify(hosts.map((h) => ['role', ...HOST_INPUTS.map(([k]) => k)].map((k) => String(h[k] ?? ''))));
const CHECK_MARKS = { ok: '✓', warn: '!', fail: '✗' };
// What a check found: a mark, the name, the finding and, under it, how to fix it (`code` in back quotes)
const fixHtml = (f) => esc(f).replace(/`([^`]+)`/g, '<code>$1</code>');
const checkRows = (items) => items.map((i) => `<tr class="check-${esc(i.level)}"><td>${CHECK_MARKS[i.level]}</td><td>${esc(i.name)}</td>
  <td>${esc(i.text)}${i.fix ? `<div class="check-fix">${fixHtml(i.fix)}</div>` : ''}</td></tr>`).join('');
const checkReport = (r) => (!r ? '' : r.hosts.map((h) => `<div class="check-host"><div class="check-host-name">${esc(h.address)} <span class="muted">${esc(h.role)}</span></div>
  <table class="check"><tbody>${checkRows(h.items)}</tbody></table></div>`).join(''));
// The state of a check in the head of its block: what it found, that it runs, or that it has not run
function checkBadge(r, running, none = 'not checked') {
  if (running) return '<span class="badge running">checking…</span>';
  if (!r) return `<span class="badge no-change">${none}</span>`;
  const items = r.items || r.hosts.flatMap((h) => h.items);
  const fails = items.filter((i) => i.level === 'fail').length, warns = items.filter((i) => i.level === 'warn').length;
  if (!r.ok) return `<span class="badge failed">${fails ? plural(fails, 'problem') : 'failed'}</span>`;
  return `<span class="badge ${warns ? 'partial' : 'ok'}">${r.items ? 'ready' : 'passed'}${warns ? `, ${plural(warns, 'warning')}` : ''}</span>`;
}
// The head of a block of the setup: its number (a tick once it passed), its name and the state of its check
const stepHead = (n, title, sub, key, badge = '') => `<div class="step-head">${n ? `<span class="step-num" data-${key}-num>${n}</span>` : ''}<h2>${title}</h2>
  <span class="muted">${sub}</span><span class="step-state" data-${key}-badge>${badge}</span></div>`;
function paintStep(root, key, n, r, running, none) {
  const badge = $(`[data-${key}-badge]`, root), num = $(`[data-${key}-num]`, root);
  if (badge) badge.innerHTML = checkBadge(r, running, none);
  if (num) {
    num.className = `step-num${!r || running ? '' : r.ok ? ' ok' : ' bad'}`;
    num.textContent = r && !running && r.ok ? '✓' : String(n);
  }
}
// The log of Check: "[host ]$ command", "→ exit 0 in 0.3 s", "| output", "· note" (see CommandLog of the harness)
const CHECK_LOG_LINE = /^(\S+ )?([$→|·]) (.*)$/;
const checkLogLine = (l) => {
  const m = CHECK_LOG_LINE.exec(l);
  if (!m) return `<div class="cl-out">${esc(l)}</div>`;
  const host = m[1] ? `<span class="cl-host">${esc(m[1].trim())}</span> ` : '';
  if (m[2] === '|') return `<div class="cl-out">${esc(m[3])}</div>`;
  const cls = { $: 'cl-cmd', '·': 'cl-note', '→': /^exit 0 /.test(m[3]) ? 'cl-end' : 'cl-end cl-bad' }[m[2]];
  return `<div class="${cls}">${host}${esc(m[2])} ${esc(m[3])}</div>`;
};
const checkLog = (l, open) => (!l || !l.lines.length ? '' : `<details class="check-log step-log" ${open ? 'open' : ''}>
  <summary title="run where the application runs (in Docker: inside its container)">${l.running ? 'Running' : 'Log'} · ${plural(l.lines.filter((x) => (CHECK_LOG_LINE.exec(x) || [])[2] === '$').length, 'command')}</summary>
  <div class="check-log-lines mono">${l.lines.map(checkLogLine).join('')}</div></details>`);
// what the running or the last Check printed; a closed log stays closed while it grows
async function showCheckLog(form, open) {
  const box = $('[data-check-log]', form);
  const was = $('details', box);
  const l = await api.get('/api/stand/check/log');
  box.innerHTML = checkLog(l, was && open === undefined ? was.open : open !== false);
  const lines = $('.check-log-lines', box);
  if (lines && l.running) lines.scrollTop = lines.scrollHeight;
}
// Check of the hosts under their fields: what it found, then the button, what it tries and its log
const hostsCheck = (check) => `<div data-check-report>${checkReport(check)}</div>
  <div class="step-foot"><button type="button" class="small" data-stand-check>${check ? 'Check again' : 'Check hosts'}</button>
    <span class="hint">ssh, JDK, disk, memory, the network between hosts, other JVMs, clocks, swap, THP, CPU governor</span>
    <span class="step-log-box" data-check-log>${check ? '<button type="button" class="small" data-check-log-show>Log of the last Check</button>' : ''}</span></div>`;
// a stand on this machine needs no Check to start, remote hosts do
const hostsNone = (form) => ($('[name="stand.mode"]:checked', form).value === 'remote' ? 'not checked' : 'optional');
const sizesText = (s) => (s.nodeMemoryMb ? `${fmt.num(s.nodeMemoryMb)} MB a node, region ${s.fillPct} % full, threads ×${s.threadsFactor}`
  : s.threadsFactor !== 1 ? `the sizes of the scenarios, threads ×${s.threadsFactor}` : 'the sizes of the scenarios');
const recommendation = (r) => (!r ? '' : `${r.recommended && r.recommended.nodeMemoryMb
  ? `<button type="button" class="small" data-sizes-use='${esc(JSON.stringify(r.recommended))}'>Use ${esc(sizesText(r.recommended))}</button>` : ''}
  ${r.sizesWarning ? `<div class="check-tight">${esc(r.sizesWarning)}</div>` : ''}`);
const sizesFields = (s, check) => `<div class="form-grid">
    <label>Memory of a node, MB <input name="nodeMemoryMb" type="number" min="0" step="256" value="${s.nodeMemoryMb || ''}" placeholder="as in the scenarios"></label>
    <label>Data region filled, % <input name="fillPct" type="number" min="0" max="80" value="${s.fillPct || ''}" placeholder="40"></label>
    <label>Threads, factor <input name="threadsFactor" type="number" min="0.25" step="0.25" value="${s.threadsFactor}"></label>
  </div>
  <div data-sizes-recommended style="margin-top:6px">${recommendation(check)}</div>
  <div class="muted small-text">Memory splits into the data region, the heap and a reserve; the keys and rows follow from the region. Empty memory keeps the numbers the scenarios state.</div>`;
const standOf = (form) => ({
  mode: $('[name="stand.mode"]:checked', form).value,
  hosts: [...form.querySelectorAll('[data-host]')].map((tr) => {
    const h = {};
    tr.querySelectorAll('[name]').forEach((i) => { h[i.name] = i.value.trim(); });
    h.port = Number(h.port || 22);
    return h;
  }).filter((h) => h.address),
  sizes: {
    nodeMemoryMb: Number($('[name="nodeMemoryMb"]', form).value || 0),
    fillPct: Number($('[name="fillPct"]', form).value || 0),
    threadsFactor: Number($('[name="threadsFactor"]', form).value || 1),
  },
});
function bindStandFields(form, edited = () => {}, checked = () => {}) {
  form.addEventListener('input', edited);
  form.addEventListener('change', () => {
    const mode = $('[name="stand.mode"]:checked', form).value;
    $('[data-if-remote]', form).hidden = mode !== 'remote';
    $('[data-mode-text]', form).textContent = STAND_MODES.find(([k]) => k === mode)[2];
    edited();
  });
  form.addEventListener('click', (e) => {
    const remove = e.target.closest('[data-host-remove]');
    if (remove) remove.closest('tr').remove();
    if (e.target.closest('[data-host-add]')) $('.hosts tbody', form).insertAdjacentHTML('beforeend', hostRow());
    if (remove || e.target.closest('[data-host-add]')) edited();
    const use = e.target.closest('[data-sizes-use]');
    if (use) {
      const r = JSON.parse(use.dataset.sizesUse);
      Object.entries(r).forEach(([k, v]) => { $(`[name="${k}"]`, form).value = v; });
      edited();
    }
    if (e.target.closest('[data-check-log-show]')) guarded(() => showCheckLog(form, true));
    const run = e.target.closest('[data-stand-check]');
    if (run) {
      run.disabled = true;
      run.textContent = 'Checking…';
      $('[data-check-report]', form).innerHTML = '';
      $('[data-check-log]', form).innerHTML = '';
      paintStep(form, 'hosts', 2, null, true);
      let passed = false, r = null;
      // the log as it grows; a failed tick is retried by the next one
      const timer = setInterval(() => showCheckLog(form).catch(() => {}), 1000);
      guarded(async () => {
        try {
          r = await api.post('/api/stand/check', standOf(form));
          passed = r.ok;
          $('[data-check-report]', form).innerHTML = checkReport(r);
          $('[data-sizes-recommended]', form).innerHTML = recommendation(r);
        }
        finally {
          clearInterval(timer);
          run.disabled = false;
          run.textContent = r ? 'Check again' : 'Check hosts';
          paintStep(form, 'hosts', 2, r, false, hostsNone(form));
          checked(r); // null: it did not run, so nothing is checked
          await showCheckLog(form, !passed).catch(() => {});
        }
      });
    }
  });
}
const standSummary = (s) => `${s.mode === 'local' ? '<div>This machine: all JVMs run next to the application.</div>'
  : `<table><thead><tr>${HOST_INPUTS.map(([, h]) => `<th>${h}</th>`).join('')}<th>Role</th></tr></thead>
    <tbody>${s.hosts.map((h) => `<tr>${HOST_INPUTS.map(([k]) => `<td>${esc(h[k] ?? '')}</td>`).join('')}<td>${esc(h.role)}</td></tr>`).join('')}</tbody></table>`}
  <div style="margin-top:8px">Scenarios: ${esc(sizesText(s.sizes))}</div>`;
const loadReport = (l) => (!l ? '' : `<table class="hosts-result"><thead><tr><th>Threads</th><th class="num">ops/s</th><th class="num">p99 µs</th></tr></thead>
  <tbody>${l.steps.map((s) => `<tr class="${s.threadsFactor === l.threadsFactor ? 'verdict-metric' : ''}"><td>×${s.threadsFactor}</td><td class="num">${fmt.num(s.throughput)}</td><td class="num">${fmt.num(s.p99Us)}</td></tr>`).join('')}</tbody></table>
  <button type="button" class="small" data-sizes-use='${esc(JSON.stringify({ threadsFactor: l.threadsFactor }))}'>Use ×${l.threadsFactor}</button>
  <span class="muted small-text"><a href="#/jobs/${l.jobId}">job #${l.jobId}</a> · ${fmt.date(l.at)}</span>`);

// Check of this machine under the repository: what builds the commits, then the button and its log
const appCheck = (r, running, error) => `<table class="check tools"><tbody>${r ? checkRows(r.items) : ''}</tbody></table>
  ${!r && running ? '<div class="hint">Asking the repository, Java, Maven, git and the disk…</div>' : ''}
  ${error && !running ? `<div class="error small-text">${esc(error)}</div>` : ''}
  <div class="step-foot"><button type="button" class="small" data-app-check-run ${running ? 'disabled' : ''}>${running ? 'Checking…' : r ? 'Check again' : 'Check'}</button>
    <span class="hint"><code>java</code>, <code>mvn</code>, <code>maven.*</code>: in <code>config/ignite-firewatch.properties</code> or <code>IGNITE_FIREWATCH_*</code>, a restart applies them</span>
    <span class="step-log-box">${r ? checkLog({ running: false, lines: r.log }, false) : ''}</span></div>`;

// The first page of a new stand: what this machine builds, then where the JVMs run. Nothing is cloned or measured
// before it is done, and the header stays hidden
let setupPending = false;
views.setup = {
  async render() {
    const [saved, settings] = await Promise.all([api.get('/api/stand'), api.get('/api/settings')]);
    const seen = saved.check && saved.check.checked;
    const stand = !saved.hosts.length && seen && seen.length ? { ...saved, mode: 'remote', hosts: seen } : saved; // a reload keeps the hosts Check saw
    this.check = saved.check;
    return `<h1>Set up the stand</h1>
      <div class="muted" style="margin-bottom:14px">First what this machine builds, then where the JVMs run. The stand is fixed by its first finished run: results of different stands must not mix.</div>
      <form id="setup">
        <div class="card step">${stepHead(1, 'Application', 'builds every commit on this machine', 'app')}
          <div class="repo-grid">
            <label>Git URL or path<input name="repo.url" value="${esc(settings['repo.url'])}" autocomplete="off" spellcheck="false" required></label>
            <label>Branch<input name="repo.branch" value="${esc(settings['repo.branch'])}" autocomplete="off" spellcheck="false" required></label>
            <label>Release to compare with<input name="release.tag" value="${esc(settings['release.tag'] || '')}" placeholder="the highest X.Y.Z tag" autocomplete="off" spellcheck="false"></label>
          </div>
          <div class="hint">${REPO_PATH_HINT}</div>
          <div data-app-check>${appCheck(null, true)}</div></div>
        <div class="card step">${stepHead(2, 'Hosts', 'where the server nodes and the loader run', 'hosts')}
          ${standFields(stand)}${hostsCheck(stand.check)}</div>
        <div class="card step">${stepHead(3, 'Scenario sizes', 'optional: empty keeps the numbers the scenarios state', 'sizes')}
          ${sizesFields(stand.sizes, stand.check)}</div>
        <div class="start"><button class="primary" data-start disabled>Start the stand</button><span class="hint" data-start-why></span></div>
      </form>`;
  },
  bind(root) {
    const form = $('#setup', root);
    // what keeps the stand from starting, or what comes after the start
    const blocker = () => {
      if (this.appRunning) return 'Step 1 is checking this machine…';
      if (!this.app) return 'Step 1 could not check this machine: check it again.';
      if (!this.app.ok) return 'Fix what step 1 marks ✗.';
      if (standOf(form).mode === 'remote' && !(this.check && this.check.ok)) return this.check ? 'Fix what step 2 marks ✗.' : 'Remote hosts have to pass Check first.';
      return null;
    };
    const paintStart = () => {
      const why = blocker();
      $('[data-start]', form).disabled = !!why;
      $('[data-start-why]', form).textContent = why || 'The mirror is cloned next: a few minutes for a large repository.';
    };
    const paintHosts = () => { paintStep(form, 'hosts', 2, this.check, false, hostsNone(form)); paintStart(); };
    const paintApp = () => {
      $('[data-app-check]', form).innerHTML = appCheck(this.app, this.appRunning, this.appError);
      paintStep(form, 'app', 1, this.app, this.appRunning);
      paintStart();
    };
    // the repository of the form, saved or not; a later answer wins over an earlier one
    let asked = 0;
    const checkApp = () => {
      const n = ++asked, fd = new FormData(form);
      this.appRunning = true;
      this.app = null;
      this.appError = null;
      paintApp();
      api.post('/api/app/check', { url: String(fd.get('repo.url')).trim(), branch: String(fd.get('repo.branch')).trim() })
        .then((r) => { if (n === asked) this.app = r; }, (e) => { if (n === asked) this.appError = `Check of this machine did not run: ${e.message}`; })
        .finally(() => { if (n === asked) { this.appRunning = false; paintApp(); } });
    };
    const dropStale = () => {
      const now = standOf(form);
      if (this.check && hostsKey(this.check.checked || []) !== hostsKey(now.mode === 'remote' ? now.hosts : [])) {
        this.check = null;
        $('[data-check-report]', form).innerHTML = '';
        $('[data-sizes-recommended]', form).innerHTML = '';
      }
      paintHosts();
    };
    bindStandFields(form, dropStale, (r) => { this.check = r; paintHosts(); });
    form.addEventListener('click', (e) => { if (e.target.closest('[data-app-check-run]')) checkApp(); });
    ['repo.url', 'repo.branch'].forEach((n) => $(`[name="${n}"]`, form).addEventListener('change', checkApp));
    dropStale();
    checkApp();
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      if (blocker()) return;
      const fd = new FormData(form), stand = standOf(form);
      guarded(async () => {
        await api.put('/api/settings', { 'repo.url': fd.get('repo.url'), 'repo.branch': fd.get('repo.branch'), 'release.tag': fd.get('release.tag') });
        await api.put('/api/stand', stand);
        setupPending = false;
        $('header nav').hidden = false;
        $('.search').hidden = false;
        location.hash = '#/dashboard';
      });
    });
  },
};

views.settings = {
  poll: 6000,
  async render() {
    const storage = await api.get('/api/storage').catch(() => null); // a walk over the runs: the page does not poll
    const [settings, health, scenarios, builds, stand] = await Promise.all([api.get('/api/settings'), api.get('/api/health'), api.get('/api/scenarios'), api.get('/api/builds?limit=20'), api.get('/api/stand')]);
    const noises = (await Promise.all(scenarios.map((x) => api.get(`/api/noise?scenario=${encodeURIComponent(x.name)}`).catch(() => null)))).filter(Boolean);
    const rel = await api.get(`/api/release?scenario=${encodeURIComponent(scenarios[0].name)}`).catch(() => ({}));
    const w = health.watcher, s = health.system;
    const log = await api.get('/api/log?lines=60');
    const tab = this.tab || SETTINGS_TABS[0][0];
    const autoMode = w.autorunEnabled ? settings['autorun.mode'] : 'off';
    // a limit of records: 0 keeps them all, so the field says "all" and the count next to it is plainly a count
    const limit = (key, name, hint, all) => `<div class="limit-row"><label for="${key}">${name}</label><input id="${key}" name="${key}" type="number" min="0" value="${all && settings[key] === '0' ? '' : esc(settings[key] ?? '')}" ${all ? 'placeholder="all"' : ''}><span class="muted small-text">${hint}</span></div>`;
    this.counts = health.counts;
    const chosen = (settings['autorun.scenario'] || '').split(',').map((x) => x.trim()).filter(Boolean);
    this.picked = new Set(chosen);
    const pickedOf = (p) => { const l = (settings[`${p}.scenarios`] || '').split(',').map((x) => x.trim()).filter(Boolean); return new Set(l.length ? l : scenarios.map((x) => x.name)); }; // empty = all
    this.aaPicked = pickedOf('calibration');
    this.masterPicked = pickedOf('master');
    const nMode = settings['notify.mode'] || 'off';
    const nChannel = settings['notify.telegramChatId'] ? 'Telegram' : settings['notify.webhookUrl'] ? 'webhook' : 'no channel';
    return `
      <h1>Settings</h1>
      ${standState(health, stand, builds, noises)}
      <div class="seg" role="tablist">${SETTINGS_TABS.map(([k, name]) => `<button type="button" data-settings-tab="${k}" class="${k === tab ? 'on' : ''}">${name}</button>`).join('')}</div>
      <section data-tab="repository" ${tab === 'repository' ? '' : 'hidden'}><div class="card">
        <form id="repository">
          <label>Git URL or path</label>
          <input name="repo.url" value="${esc(settings['repo.url'])}" autocomplete="off" required>
          <div class="muted small-text">${REPO_PATH_HINT}</div>
          <label>Main branch</label>
          <input name="repo.branch" value="${esc(settings['repo.branch'])}" autocomplete="off" required>
          <div class="actions"><button class="primary">Save</button></div>
          <div class="muted small-text">Both are checked before saving, and the queue has to be empty. After a change the stand follows the new head: runs of the old branch stay in the database but leave Commits and Timeline.
            Every poll fetches from a path as from a URL: a fresh copy put in its place arrives by itself.</div>
        </form>
      </div>
      <div class="card">${stepHead(null, 'Builds', 'what builds every commit on this machine', 'app', checkBadge(this.appCheck, this.appRunning))}
        <div data-app-check>${appCheck(this.appCheck, this.appRunning, this.appError)}</div></div>
      <form id="tickets" class="card">
        <h2 style="margin-top:0">Ticket links <span class="muted">turn tickets and PR numbers in commit subjects into links</span></h2>
        <table class="ticket-keys"><thead><tr><th>Key</th><th>Ticket URL</th><th>Pull request URL</th><th></th></tr></thead>
          <tbody>${(ticketLinks.size ? [...ticketLinks] : [['', { url: '', pr: '' }]]).map(([key, l]) => ticketRow(key, l)).join('')}</tbody></table>
        <div class="actions"><button type="button" class="small" data-ticket-add>Add key</button></div>
        <div class="muted small-text">The ticket or the number goes at the end of its URL: IGNITE-123 leads to the ticket URL + IGNITE-123, a PR of a commit with that key to the pull request URL + the number. An empty pull request URL follows the Git URL above.</div>
        <div class="actions"><button class="primary">Save</button></div>
      </form></section>
      <section data-tab="automation" ${tab === 'automation' ? '' : 'hidden'}>
        <form id="settings" class="card">
          <h2 style="margin-top:0">On new commits <span class="muted">Measures the branch as it moves.</span></h2>
          <div class="mode-list">${AUTORUN_MODES.map(([k, name, text]) => `<label class="mode-opt"><input type="radio" name="autorun.mode" value="${k}" ${k === autoMode ? 'checked' : ''}><span><b>${name}</b><div class="muted">${text}</div></span></label>`).join('')}</div>
          <div data-if-autorun ${autoMode === 'off' ? 'hidden' : ''}>
            <div class="mode-row" data-segments ${autoMode === 'segments' ? '' : 'hidden'}><span class="mode-opt">Keep fresh</span>${keepField('autorun.keepDays', settings['autorun.keepDays'] || '1')}</div>
            ${scenarioPicker(scenarios, this.picked)}
            <label>Only commits touching (globs; empty = every commit)</label>
            <textarea name="autorun.pathFilter" rows="2" style="width:100%">${esc(settings['autorun.pathFilter'])}</textarea>
            <div class="form-grid"><label>Poll interval, s <input name="watch.pollSec" type="number" value="${esc(settings['watch.pollSec'])}"></label></div>
          </div>
          <div class="actions"><button class="primary">Save</button></div>
        </form>
        ${scheduleForm('calibration', 'A/A', 'Measures the noise of the stand, so the thresholds follow it.', settings, scenarios, this.aaPicked)}
        ${scheduleForm('master', 'master vs release', 'Keeps the dashboard headline fresh.', settings, scenarios, this.masterPicked)}
        <div class="muted small-text">Keep fresh: past that age a scenario goes once the stand has been idle for 2 hours, behind everything else. At twice that age, or with fewer than 3 A/A for its thresholds, it goes ahead of the queue. Nothing starts while the stand is closed for maintenance. Results of A/A are on <a href="#/noise">Noise</a>.</div>
      </section>
      <section data-tab="release" ${tab === 'release' ? '' : 'hidden'}><div class="card">
        <form id="release">
          <label>What the dashboard compares master with</label>
          <input name="release.tag" value="${esc(settings['release.tag'] || '')}" placeholder="the highest X.Y.Z tag" autocomplete="off">
          <div class="muted" style="margin-top:6px">Empty: the highest X.Y.Z tag, on its own after every release. A mask such as <code>2.18.*</code>: the highest tag that fits. Or any tag, sha or branch.</div>
          <div style="margin-top:6px">Now: ${rel.tag ? `<b>${esc(rel.tag)}</b> <span class="mono muted">${fmt.sha(rel.tagSha)}</span>` : '<span class="muted">nothing fits</span>'}</div>
          <div class="actions"><button class="primary">Save</button></div>
          <div class="muted small-text">After a change press Run all on the dashboard: runs against the previous release no longer count.</div>
        </form>
      </div></section>
      <section data-tab="notify" ${tab === 'notify' ? '' : 'hidden'}><div class="card">
        <div class="muted">${esc(nMode)}${nMode !== 'off' ? ` · ${esc(nChannel)}` : ''}</div>
        <form id="notify">
              <label>When</label>
              <select name="notify.mode"><option value="off" ${nMode === 'off' ? 'selected' : ''}>off</option><option value="regression" ${nMode === 'regression' ? 'selected' : ''}>regressions and failed auto jobs</option><option value="all" ${nMode === 'all' ? 'selected' : ''}>every finished job</option></select>
              <label>Telegram bot token</label>
              <input name="notify.telegramToken" type="password" value="${esc(settings['notify.telegramToken'] || '')}" autocomplete="off" placeholder="123456:ABC…">
              <label>Telegram chat id</label>
              <input name="notify.telegramChatId" value="${esc(settings['notify.telegramChatId'] || '')}" placeholder="-1001234567890">
              <label>Webhook URL (JSON {"text": …})</label>
              <input name="notify.webhookUrl" value="${esc(settings['notify.webhookUrl'] || '')}" placeholder="https://hooks.slack.com/services/…">
              <label>Base URL for links</label>
              <input name="notify.baseUrl" value="${esc(settings['notify.baseUrl'] || '')}" placeholder="http://stand:8090">
              <div class="actions actions-inline"><button class="primary">Save</button><button type="button" id="notify-test">Send test</button></div>
            </form>
      </div></section>
      <section data-tab="limits" ${tab === 'limits' ? '' : 'hidden'}><div class="card">
        <form id="limits">
          ${limit('retention.keepComparisons', 'Comparisons to keep', `Stored now: ${health.counts.comparisons}${storage ? `, ≈ ${fmt.bytes(storage.comparisons)}` : ''}. With a number, older ones go with their jobs and files, an active one stays.`, true)}
          ${limit('retention.keepBisects', 'Ended bisects to keep', `Stored now: ${health.counts.bisects}${storage ? `, ≈ ${fmt.bytes(storage.bisects)} in the database` : ''}. With a number, the older records go, their steps stay as ordinary runs.`, true)}
          ${limit('retention.keepJfrJobs', 'Heavy files: last N jobs', `JFR and work directories of older jobs go · ${storage ? `now ≈ ${fmt.bytes(storage.heavy)} of ${fmt.bytes(storage.runs)} in runs` : `runs take ${fmt.num(s.runsMb)} MB`}. Results, series and logs stay.`)}
          ${limit('retention.artifactsGb', 'Build cache, GB', `Least recently used builds go above this · now ${fmt.num(s.artifactsMb / 1024, 1)} GB, ${fmt.num(Math.min(s.dataFreeMb, s.cacheFreeMb) / 1024, 1)} GB free on disk.`)}
          ${limit('autorun.maxBacklog', 'Waiting auto-run jobs', 'A new commit is skipped while this many auto jobs wait.')}
          <div class="actions"><button class="primary">Save</button></div>
        </form>
        <div class="muted small-text">The history of the branch has no limit: Timeline, repeats and noise floors rest on it.</div>
      </div></section>
      <section data-tab="stand" ${tab === 'stand' ? '' : 'hidden'}><div class="card">
        ${stand.frozen ? `${standSummary(stand)}<div class="muted small-text" style="margin-top:8px">Fixed by the first finished run: results of different stands must not mix. Another stand is another instance with its own data directory.</div>`
          : `<form id="stand">${stepHead(null, 'Hosts', 'where the server nodes and the loader run', 'hosts',
                checkBadge(stand.check, false, (this.standDraft || stand).mode === 'remote' ? 'not checked' : 'optional'))}
              ${standFields(this.standDraft ? { ...stand, ...this.standDraft } : stand)}${hostsCheck(stand.check)}
              <h2>Scenario sizes</h2>${sizesFields((this.standDraft || stand).sizes, stand.check)}
              <div class="load-block"><div class="actions"><button type="button" class="small" id="measure-load">Measure load</button></div>
                <div class="muted small-text">Runs the main scenario with ×1, ×2 and ×4 threads, about a minute each, until the throughput stops growing. These runs stay out of the history and do not fix the stand.</div>
                ${loadReport(stand.load)}</div>
              <div class="actions"><button class="primary">Save</button></div>
              <div class="muted small-text">This can change until the first run finishes.</div></form>`}
        ${stand.mode === 'remote' ? '<div class="actions"><button type="button" class="small" id="sweep-hosts" title="stops what a killed phase left on the hosts: its JVMs and directories">Clean hosts</button></div>' : ''}
      </div></section>
      <section data-tab="system" ${tab === 'system' ? '' : 'hidden'}>
        <h2 style="margin-top:0">Builds <span class="muted">last ${builds.length} · ${fmt.num(s.artifactsMb)} MB of artifacts${builds.some((b) => b.status === 'failed') ? ' · <span class="error">has failures</span>' : ''}</span></h2>
        ${health.maven ? `<div class="muted small-text" style="margin-bottom:6px">Maven: <span class="mono">${esc(health.maven)}</span> · maven.settings and maven.args of the config</div>` : ''}
        <table><thead><tr><th>Commit</th><th>Status</th><th class="num">Took</th><th class="num">Size</th><th>Started</th><th>Error</th></tr></thead>
        <tbody>${builds.map((b) => `<tr><td class="mono"><a href="#/commits/${b.sha}">${fmt.sha(b.sha)}</a>${b.fake ? ' <span class="muted">(fake)</span>' : ''}</td><td>${badge(b.status)}</td>
          <td class="num">${b.durationSec != null ? Math.round(b.durationSec) + ' s' : '–'}</td><td class="num">${fmt.bytes(b.sizeBytes)}</td><td>${fmt.ago(b.startedAt)}</td><td class="error">${esc(b.error || '')}</td></tr>`).join('') || '<tr><td colspan="6" class="muted">none</td></tr>'}</tbody></table>
        <h2>Controller log <span class="muted">${esc(health.version)} · up ${fmt.dur(new Date(Date.now() - health.uptimeSec * 1000).toISOString())} · ${esc(s.jdk)}</span></h2>
        <pre class="log">${esc(log.map((e) => `${e.at} ${e.level} ${e.msg}`).join('\n'))}</pre>
      </section>`;
  },
  bind(root) {
    // the sections switch in place: unsaved input survives it
    // Builds of the Application tab: checked when the tab first shows and on its button; the answer is painted into the
    // page as it is now, which a poll may have rendered anew meanwhile
    const checkApp = () => {
      if (this.appRunning) return;
      this.appRunning = true;
      this.appError = null;
      const paint = () => {
        const box = $('[data-app-check]'), badge = $('[data-app-badge]');
        if (box) box.innerHTML = appCheck(this.appCheck, this.appRunning, this.appError);
        if (badge) badge.innerHTML = checkBadge(this.appCheck, this.appRunning);
      };
      paint();
      api.post('/api/app/check', {}).then((r) => { this.appCheck = r; }, (e) => { this.appError = `Check of this machine did not run: ${e.message}`; })
        .finally(() => { this.appRunning = false; lastHtml = null; paint(); });
    };
    const showApp = () => { if ((this.tab || SETTINGS_TABS[0][0]) === 'repository' && !this.appCheck && !this.appError) checkApp(); };
    root.addEventListener('click', (e) => { if (e.target.closest('[data-app-check-run]')) checkApp(); });
    root.querySelectorAll('button[data-settings-tab]').forEach((b) => b.addEventListener('click', () => {
      this.tab = b.dataset.settingsTab;
      root.querySelectorAll('button[data-settings-tab]').forEach((x) => x.classList.toggle('on', x === b));
      root.querySelectorAll('section[data-tab]').forEach((x) => { x.hidden = x.dataset.tab !== this.tab; });
      showApp();
    }));
    showApp();
    bindScenarioPicker($('#settings', root), this.picked, { builds: 1, per: 'a run' });
    [['calibration', this.aaPicked], ['master', this.masterPicked]].forEach(([p, picked]) => {
      const f = $(`#schedule-${p}`, root), sync = () => {
        const off = f.querySelector(`[name="${p}.mode"]:checked`).value === 'off';
        f.querySelectorAll('[data-if-on]').forEach((x) => { x.hidden = off; });
      };
      bindScenarioPicker(f, picked, { builds: 1, per: 'a run' });
      f.addEventListener('change', sync);
      sync();
      f.addEventListener('submit', (e) => {
        e.preventDefault();
        if (!picked.size) { toast('choose at least one scenario', true); return; }
        const all = [...f.querySelectorAll('[data-sc]')].map((i) => i.dataset.sc), fd = new FormData(f);
        const d = { [`${p}.mode`]: fd.get(`${p}.mode`), [`${p}.keepDays`]: fd.get(`${p}.keepDays`),
          [`${p}.scenarios`]: picked.size === all.length ? '' : all.filter((n) => picked.has(n)).join(',') }; // all: a new scenario joins by itself
        guarded(async () => { await api.put('/api/settings', d); toast('schedule saved'); refresh(); });
      });
    });
    $('#settings', root).addEventListener('submit', (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      const d = Object.fromEntries(fd);
      d['autorun.scenario'] = [...e.target.querySelectorAll('[data-sc]')].map((i) => i.dataset.sc).filter((n) => this.picked.has(n)).join(',');
      d['autorun.enabled'] = String(d['autorun.mode'] !== 'off');
      if (d['autorun.mode'] === 'off') delete d['autorun.mode']; // the choice stays for the next time
      guarded(async () => { await api.put('/api/settings', d); toast('settings saved'); refresh(); });
    });
    root.querySelectorAll('[name="autorun.mode"]').forEach((r) => r.addEventListener('change', () => {
      const m = $('[name="autorun.mode"]:checked', root).value;
      $('[data-if-autorun]', root).hidden = m === 'off';
      $('[data-segments]', root).hidden = m !== 'segments';
    }));
    $('#repository', root).addEventListener('submit', (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(e.target));
      guarded(async () => {
        const now = await api.get('/api/settings');
        if ((d['repo.url'] !== now['repo.url'] || d['repo.branch'] !== now['repo.branch'])
          && !(await modal('Follow another repository?', `<div><span class="mono">${esc(d['repo.branch'])}</span> of <span class="mono">${esc(d['repo.url'])}</span>. Runs of the current branch leave Commits and Timeline.</div>`, 'Follow'))) return;
        await api.put('/api/settings', d);
        toast('repository saved');
        this.appCheck = null; // the builds are checked again against the new repository
        this.appError = null;
        refresh();
      });
    });
    const tickets = $('#tickets', root);
    tickets.addEventListener('click', (e) => {
      const remove = e.target.closest('[data-ticket-remove]');
      if (remove) remove.closest('tr').remove();
      if (e.target.closest('[data-ticket-add]')) $('tbody', tickets).insertAdjacentHTML('beforeend', ticketRow());
    });
    tickets.addEventListener('submit', (e) => {
      e.preventDefault();
      const lines = [...tickets.querySelectorAll('[data-ticket]')].map((tr) => ['key', 'url', 'pr'].map((n) => $(`[name=${n}]`, tr).value.trim()))
        .filter((f) => f.some(Boolean)).map(([key, url, pr]) => `${key}=${url}${pr ? ` ${pr}` : ''}`);
      guarded(async () => { await api.put('/api/settings', { 'links.tickets': lines.join('\n') }); toast('ticket links saved'); await api.get('/api/settings'); lastHtml = null; refresh(); });
    });
    $('#release', root).addEventListener('submit', (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(e.target));
      guarded(async () => { await api.put('/api/settings', d); toast('release saved'); refresh(); });
    });
    const standForm = $('#stand', root);
    if (standForm) {
      bindStandFields(standForm, () => { this.standDraft = standOf(standForm); }); // the page repaints while people type
      standForm.addEventListener('submit', (e) => {
        e.preventDefault();
        guarded(async () => { await api.put('/api/stand', standOf(standForm)); this.standDraft = null; toast('stand saved'); refresh(); });
      });
      $('#measure-load', root).addEventListener('click', () => guarded(async () => {
        const j = await api.post('/api/stand/measure');
        location.hash = `#/jobs/${j.id}`;
      }));
    }
    const sweep = $('#sweep-hosts', root);
    if (sweep) sweep.addEventListener('click', () => guarded(async () => {
      if (!(await modal('Clean the hosts?', '<div>Stops the JVMs of this stand on the hosts and removes the directories of its phases.</div>', 'Clean', { danger: true }))) return;
      const r = await api.post('/api/stand/sweep');
      toast(r.ok ? 'hosts cleaned' : 'some hosts did not answer: see the log', !r.ok);
    }));
    $('#limits', root).addEventListener('submit', (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(e.target));
      for (const key of ['retention.keepComparisons', 'retention.keepBisects']) d[key] = d[key] || '0'; // an empty field keeps them all
      const over = (total, keep) => (Number(keep) > 0 ? Math.max(0, total - Number(keep)) : 0);
      const c = over(this.counts.comparisons, d['retention.keepComparisons']), b = over(this.counts.bisects, d['retention.keepBisects']);
      guarded(async () => {
        const what = [c && `${plural(c, 'old comparison')} with jobs and files`, b && plural(b, 'old bisect')].filter(Boolean).join(' and ');
        if (what && !(await modal('Delete old records?', `<div>${what} will be deleted. This cannot be undone.</div>`, 'Save and delete'))) return;
        await api.put('/api/settings', d);
        toast('limits saved');
        refresh();
      });
    });
    $('#notify', root).addEventListener('submit', (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(e.target));
      guarded(async () => { await api.put('/api/settings', d); toast('notification settings saved'); refresh(); });
    });
    $('#notify-test', root).addEventListener('click', () => guarded(async () => {
      const r = await api.post('/api/notify/test');
      await new Promise((res) => setTimeout(res, 2500));
      const st = await api.get('/api/notify/status');
      toast(`test sent via ${r.attempted}; result: ${st.lastResult}`, r.attempted === 'no channel configured' || /failed|HTTP/.test(st.lastResult));
    }));
    // the page does not poll, and the stand may have changed since it was drawn: the dialog tells what is true now
    $('#maintenance', root).addEventListener('click', (e) => guarded(async () => {
      const h = await api.get('/api/health'), cur = h.worker.job, q = h.jobs.queued || 0;
      if (h.worker.paused !== (e.target.dataset.paused === '1')) { toast('the stand has changed since this page was opened: showing it now', true); refresh(); return; }
      const waits = `${plural(q, 'queued job')}${h.bisects ? ` and ${plural(h.bisects, 'bisect')}` : ''}`;
      if (h.worker.paused) {
        if (!(await modal('Reopen the stand?', `<div>${q ? `${waits} go on, one job at a time.` : 'The queue is empty: new jobs start as they come.'}</div>`, 'Reopen'))) return;
        await api.post('/api/worker/resume', {});
        toast('the stand is open');
      }
      else {
        if (!(await modal('Close the stand for maintenance?', `<div>${cur ? `Job ${jobLink(cur)} finishes, then nothing new starts.` : 'Nothing new starts.'} ${waits} wait${q === 1 && !h.bisects ? 's' : ''} and go on after Reopen. Nothing is lost.</div>`, 'Close for maintenance'))) return;
        await api.post('/api/worker/pause', {});
        toast(cur ? `closing for maintenance: job #${cur.id} finishes, nothing new starts` : 'closed for maintenance');
      }
      refresh();
    }));
    const poll = $('#poll', root), kill = $('#kill', root);
    if (poll) poll.addEventListener('click', () => guarded(async () => { await api.post('/api/watch/poll'); toast('poll requested'); setTimeout(refresh, 1500); }));
    if (kill) kill.addEventListener('click', () => guarded(async () => { const r = await api.post('/api/kill-all'); toast(`killed ${r.killed.length} JVM(s)`); refresh(); }));
  },
};

// A line of the details of a cell: a metric of the master run against what was found, or a run newer than it
function attentionLine(l, busy) {
  const delta =valDelta(l.metric, null, 0, l.deltaPct, l.verdict, l.thresholdPct).replace(/^–\s*/, '');
  const bisect = l.bisectId ? `<a href="#/bisects/${l.bisectId}">bisect #${l.bisectId}</a>` : '';
  const narrow = l.narrowSpan ? `narrowed to ${plural(l.narrowSpan, 'commit')}: <a class="mono" href="#/jobs/${l.jobId}">${fmt.sha(l.narrowBase)}…${fmt.sha(l.narrowSha)}</a> ${fmt.pct(l.narrowPct)}` : '';
  const note = { narrowed: narrow, running: `${bisect}${l.etaSec ? ` · done ${etaStart(new Date(Date.now() + l.etaSec * 1000).toISOString())}` : ''}`, stopped: bisect,
    inconclusive: `${bisect}: too close to the noise for a quick search${narrow ? ` · ${narrow}` : ''}`,
    spread: `${bisect}: spread over small changes${narrow ? ` · ${narrow}` : ''}`, lost: `${bisect}: nothing reproduced${narrow ? ` · ${narrow}` : ''}`,
    partial: `rest ${fmt.pct(l.restPct)}${narrow ? ` · ${narrow}` : ''}`,
    new: `<a class="mono" href="#/jobs/${l.jobId}">${l.span > 1 ? `${fmt.sha(l.baseSha)}…` : ''}${fmt.sha(l.sha)}</a>${l.span > 1 ? ` · ${plural(l.span, 'commit')}` : ''}` }[l.status] || '';
  return `<div class="att-line"><span class="badge ${esc(l.status)}"${hint(l.status)}>${esc(ATT_STATES[l.status][1].toLowerCase())}</span>
      ${esc(METRIC_NAMES[l.metric])} ${delta} <span class="muted">${note}</span> ${attentionAction(l, busy)}</div>
    ${l.commits.map((c) => `<div class="att-found"><a class="mono" href="#/commits/${esc(c.sha)}">${fmt.sha(c.sha)}</a> ${fmt.pct(c.deltaPct)} <span class="muted">${esc(c.subject || '')}</span></div>`).join('')}`;
}
// Dashboard headline: master vs the last release, a row per scenario, what is left to do on top. The colour of a Δ says how bad it
// is, the mark what is left to do about it: a regression stays red when its commit is found. A click opens what is known about a
// marked Δ; with nothing known it opens Timeline, coloured by that metric. A group of the legend leads to its lines in Result.
// A stand before its first fetch is done: the way to its first measurement, the clone with what git says of it. A failed
// fetch is the one error here: the next poll tries again, the button at once
function startingCard(w, stand) {
  const f = w.fetch, failed = !f && !w.polling && w.lastError;
  const step = (n, state, title, body = '') => `<div class="start-step ${state}"><span class="step-num ${state === 'done' ? 'ok' : state}">${state === 'done' ? '✓' : state === 'bad' ? '✗' : n}</span>
    <div class="start-body"><b>${title}</b>${body}</div></div>`;
  const where = stand.mode === 'remote' ? `${plural(stand.hosts.length, 'remote host')} over ssh` : 'this machine';
  const clone = failed ? `<div class="error">${esc(w.lastError)}</div><div class="muted">The next poll tries again${w.lastPollAt ? ` in ${fmt.len(Math.max(0, (new Date(w.lastPollAt).getTime() + Number(w.pollSec) * 1000 - Date.now()) / 1000))}` : ''}. <button type="button" class="small" data-poll-now>Try now</button></div>`
    : !f ? '<div class="muted">about to start…</div>'
    : `<div class="progress"><div style="width:${f.pct || 0}%"></div></div>
      <div class="muted">${f.stage ? `${esc(f.stage)} ${f.pct} %${f.detail ? ` · ${esc(f.detail)}` : ''}` : 'connecting…'} · ${fmt.dur(f.startedAt)}</div>`;
  return `<div class="card starting"><div class="step-head"><h2>${w.lastFetchAt ? 'Fetching the new repository' : 'Starting the stand'}</h2>
      <span class="step-state"><span class="badge ${failed ? 'failed' : 'running'}">${failed ? 'fetch failed' : 'starting'}</span></span></div>
    <div class="muted">The first fetch copies the repository: a few minutes for Ignite. Nothing is measured before it is done; this page follows it.</div>
    <div class="start-steps">
      ${step(1, 'done', 'Stand set up', `<div class="muted">${where}</div>`)}
      ${step(2, failed ? 'bad' : 'now', 'Clone the repository', `<div class="mono small-text">${esc(w.repoUrl)}</div>${clone}`)}
      ${step(3, 'next', `Read the history of ${esc(w.branch)} and the release tags`, '<div class="muted">seconds</div>')}
      ${step(4, 'next', 'Measure', `<div class="muted">${w.autorunEnabled ? `auto-run is on: every new commit of ${esc(w.branch)} gets measured`
        : 'auto-run is off: runs start from Commits and Compare, or turn it on in Settings → Automation'}</div>`)}
    </div></div>`;
}
function masterCard(rels, a, branch, autorun, open, showCalm) {
  // tag, unreleased commits and coverage come from the main auto-run scenario (the tag is the same for all)
  const r = rels.find((x) => x && x.tag && x.scenario === autorun[0]) || rels.find((x) => x && x.tag) || rels[0];
  if (!r || r.error) return `<div class="card error">release info unavailable: ${esc((r && r.error) || '')}</div>`;
  if (!r.tag) return '<div class="card muted">No release tag X.Y.Z in the repository: set one in Settings → release.tag.</div>';
  const cols = METRIC_COLS.slice(0, 4), width = cols.length + 4;
  const metric = (x, k) => x.job && x.job.comparison && x.job.comparison.metrics && x.job.comparison.metrics[k];
  const rowOf = (x) => a.rows.find((y) => y.scenario === x.scenario) || { lines: [] };
  const linesOf = (x) => rowOf(x).lines;
  const timeline =(x, k) => `#/timeline?scenario=${encodeURIComponent(x.scenario)}&metric=${k}${x.job ? `&job=${x.job.id}` : ''}`;
  // a run with no verdict and nothing left to do folds: a regression stays in sight even when all of it is explained
  const settled = (x) => x.job && !x.active && !MOVED.has(x.job.verdict) && linesOf(x).every((l) => l.status === 'explained');
  const explained = (x) => settled(x) && linesOf(x).length > 0;
  const unchanged = (x) => settled(x) && !linesOf(x).length && cols.every(([k]) => { const m = metric(x, k); return !m || m.deltaPct == null || !deltaClass(k, m.deltaPct, m.thresholdPct, m.verdict); });
  const folds = (x) => explained(x) || unchanged(x);
  const whenOf = (x) => (x.job ? `${fmt.ago(x.job.finishedAt)}${x.behind ? `, ${term('behind', `${x.behind} behind`)}` : ''}` : '');
  // most rows were measured together: the header says when, a row only when it differs
  const times = rels.map(whenOf).filter(Boolean);
  const usual = times.sort((x, y) => times.filter((t) => t === y).length - times.filter((t) => t === x).length)[0];
  const cell = (x, k) => {
    const m = metric(x, k), l = linesOf(x).find((y) => y.metric === k); // the most worrying line of the metric comes first
    const shown = m && m.deltaPct != null ? m : l;
    if (!shown) return '<td class="num muted">–<b class="att-mark"></b></td>';
    const chip = `<span class="dlt ${deltaClass(k, shown.deltaPct, shown.thresholdPct, shown.verdict)}">${fmt.pct(shown.deltaPct)}</span>`;
    const levels = `threshold ±${fmt.num(shown.thresholdPct, 1)}%${shown.verdict && shown.verdict !== 'no-change' ? ` · ${esc(shown.verdict)}` : ''}`;
    if (!l) return `<td class="num"><a class="rel-delta" href="${timeline(x, k)}" title="${levels} · open on Timeline">${chip}<b class="att-mark"></b></a></td>`;
    const id = `${x.scenario}|${k}`, [group, name] = ATT_STATES[l.status], worse = HIGHER_BETTER.has(k) ? l.deltaPct < 0 : l.deltaPct > 0;
    return `<td class="num"><button class="att-cell att-${group} ${worse ? 'worse' : 'better'} ${id === open ? 'on' : ''}" data-att="${esc(id)}"
      title="${esc(`${name}: ${HELP[l.status]}`)} · ${levels} · click for details">${chip}<b class="att-mark">${ATT_GROUPS[group][0]}</b></button></td>`;
  };
  const details = (x) => cols.filter(([k]) => linesOf(x).some((l) => l.metric === k)).map(([k]) => `<tr class="att-detail" data-att-for="${esc(`${x.scenario}|${k}`)}" ${`${x.scenario}|${k}` === open ? '' : 'hidden'}>
    <td colspan="${width}">${linesOf(x).filter((l) => l.metric === k).map((l) => attentionLine(l, rowOf(x).activeBisect)).join('')}<div class="att-foot"><a href="${timeline(x, k)}">Open on Timeline</a></div></td></tr>`).join('');
  const place = (x) => { const i = a.rows.findIndex((y) => y.scenario === x.scenario); return i < 0 ? a.rows.length : i; };
  const rows = [...rels].sort((x, y) => place(x) - place(y)).map((x) => {
    const j = x.job, hide = folds(x) && !showCalm;
    const when = !j ? '<span class="muted">not measured</span>' : whenOf(x) === usual ? '' : `<span class="muted">${whenOf(x)}</span>`;
    const act = x.active ? `${jobLink(x.active)} ${badge(x.active.status)}` : `<button class="small" data-release="${esc(x.scenario)}">Run</button>`;
    return `<tr class="${folds(x) ? 'rel-calm' : ''}" ${hide ? 'hidden' : ''}><td><b>${esc(scName(x.scenario))}</b></td>${cols.map(([k]) => cell(x, k)).join('')}
      <td>${j ? `<a href="#/jobs/${j.id}">${badge(j.verdict)}</a>` : ''}</td><td>${when}</td><td class="row-action">${act}</td></tr>${details(x)}`;
  }).join('');
  const calm = rels.filter(folds), noChange = calm.filter(unchanged).length, doneRows = calm.length - noChange;
  const calmText = noChange && doneRows ? `${plural(noChange, 'scenario')} without a change, ${doneRows} fully explained` : noChange ? `${plural(noChange, 'scenario')} without a change` : `${plural(doneRows, 'scenario')} fully explained`;
  const lines = a.rows.flatMap((x) => x.lines);
  const legend = Object.entries(ATT_GROUPS).map(([g, [mark, name]]) => [g, mark, name, lines.filter((l) => ATT_STATES[l.status][0] === g).length]).filter((k) => k[3])
    .map(([g, mark, name, n]) => `<button class="att-key" data-att-group="${g}"${hint(g)}><b>${mark}</b> ${n} ${name.toLowerCase()}</button>`).join('');
  const cov = r.coverage || {}, picked = (r.cherryPicked || []).length;
  const coverage = `Not in ${r.tag}: ${r.unreleased}. On ${scName(r.scenario)} ${cov.single ?? 0} measured one by one, ${cov.segment ?? 0} inside segments, ${cov.none ?? 0} not measured.`;
  return `
    <div class="card release">
      <div class="release-head">
        <div><span class="release-title">${esc(branch)} vs ${esc(r.tag)}</span>
          <span class="muted"><span class="term" title="${esc(coverage)}">${r.unreleased + picked} commits</span> since ${esc(r.tag)}${picked ? ` (<span class="term" title="already in ${esc(r.tag)}: they do not add to Δ">${picked} cherry-picked</span>)` : ''} · ${fmtDay(r.fromDate)} – ${fmtDay(r.headDate)}${usual ? ` · measured ${usual}` : ''}</span></div>
        <div class="release-tools">${openBisects(a).length ? '<button class="small" data-bisect-open title="choose the scenarios and metrics first">Bisect deviations</button>' : ''}<button class="small" id="release-all">Run all</button></div>
      </div>
      ${legend ? `<div class="att-legend">${legend}</div>` : ''}
      <table class="release-table"><thead><tr><th>Scenario</th>${cols.map(metricHead).join('')}<th>Verdict</th><th></th><th></th></tr></thead>
        <tbody>${rows}${calm.length ? calmLine('data-rel-calm', width, showCalm, calmText, calm.map((x) => scName(x.scenario))) : ''}</tbody></table>
    </div>`;
}
views.dashboard = {
  // a starting stand moves by the second, and the page follows it to the end of its first fetch
  get poll() { return this.starting ? 2000 : 10000; },
  live() { return this.starting; },
  async render() {
    const [settings, scenarios, health] = await Promise.all([api.get('/api/settings'), api.get('/api/scenarios'), api.get('/api/health')]);
    // before the first fetch there is nothing to compare or to look at: the page follows the start
    this.starting = !health.watcher.mirrorReady;
    if (this.starting) return `<h1>Dashboard</h1>${startingCard(health.watcher, await api.get('/api/stand'))}`;
    const scenario = settings['autorun.scenario'].split(',')[0].trim();
    const autoScenarios = (settings['autorun.scenario'] || '').split(',').map((x) => x.trim()).filter(Boolean);
    // every scenario gets the nightly A/A, so the tile checks all of them, the same as the Noise tab
    const noises = await Promise.all(scenarios.map((x) => x.name).map((sc) => api.get(`/api/noise?scenario=${encodeURIComponent(sc)}`).then((n) => ({ sc, n })).catch(() => null)));
    const rels = await Promise.all(scenarios.map((s) => api.get(`/api/release?scenario=${encodeURIComponent(s.name)}`).catch((e) => ({ scenario: s.name, error: e.message }))));
    // the check of master against what was found is the heavy part of the page: once a minute while the rest polls every 10 s
    if (!this.attention || Date.now() - this.attention.at > 60000)
      this.attention = { at: Date.now(), data: await api.get('/api/attention') };
    const attention = this.attention.data;
    const [history, commits, active] = await Promise.all([
      api.get('/api/jobs?filter=history&limit=60'),
      api.get(`/api/commits?limit=1&scenario=${encodeURIComponent(scenario)}`),
      api.get('/api/jobs?filter=active&limit=1'),
    ]);
    const w = health.watcher, cur = health.worker.job;
    // failed runs of the branch not followed by a good one
    const branchJob = (j) => j.source !== 'calibration' && j.source !== 'compare' && j.source !== 'release';
    const failed = history.filter((j) => (j.status === 'failed' || j.status === 'interrupted') && branchJob(j) && j.shaB
      && !history.some((x) => x.id > j.id && x.shaB === j.shaB && x.scenario === j.scenario && x.status === 'done'));
    const failedLines = failed.slice(0, 5).map((j) => `<div class="att-line att-failed">${badge(j.status)} ${esc(scName(j.scenario))} <a class="mono" href="#/commits/${j.shaB}">${fmt.sha(j.shaB)}</a>
      <span class="muted">${esc((j.error || '').slice(0, 80))}</span> ${moreRunBtn(j.id)}</div>`).join('');

    // noise status: the last A/A of every scenario must stay inside its floor on the verdict metrics
    const verdictKeys = ['throughput', 'p99Us', 'serverCpuUsPerOp'];
    // a scenario without A/A has the default floor: nothing is wrong with it yet, a new stand has only those
    const noiseIssues = [], noAa = [], calTimes = [];
    for (const x of noises.filter(Boolean)) {
      const last = x.n.calibrations[0];
      if (!last) { noAa.push(scName(x.sc)); continue; }
      calTimes.push(last.finishedAt);
      const over = verdictKeys.filter((k) => last.deltaPct[k] != null && Math.abs(last.deltaPct[k]) > (x.n.floors[k] ?? Infinity));
      if (over.length) noiseIssues.push(`${scName(x.sc)}: last A/A over the floor on ${over.join(', ')}`);
    }
    const lastCal = calTimes.sort().pop();
    const head = commits[0];
    const drains = health.queueDrainsAt;
    const tile = (label, side, body) => `<div class="tile"><div class="tile-label"><span>${label}</span><span class="tile-side">${side}</span></div>${body}</div>`;
    const worker = health.worker.paused
      ? { side: cur ? `finishing ${jobLink(cur)}` : 'queued jobs wait', body: `<div><span class="badge maintenance">${cur ? 'closing for maintenance' : 'closed for maintenance'}</span></div>` }
      : cur ? workerTile(active.find((j) => j.id === cur.id) || cur) : { side: '', body: '<div>idle</div>' };
    const stand = `
      <div class="tiles">
        ${tile('Worker', worker.side, worker.body)}
        ${tile('Queue', health.worker.paused ? '' : health.bisectsDoneAt ? `done ${etaStart(health.bisectsDoneAt)}` : drains ? `drains ${etaStart(drains)}` : '',
          `<div><a href="#/queue">${health.jobs.queued || 0} waiting</a>${health.bisects ? ` · <a href="#/bisects">${plural(health.bisects, 'bisect')}</a>` : ''}</div>`)}
        ${tile('Last commit', head ? fmt.ago(head.committedAt) : '', head ? `<div title="${esc(head.subject)}"><a class="mono" href="#/commits/${head.sha}">${fmt.sha(head.sha)}</a> ${esc(head.subject)}</div>` : '<div>–</div>')}
        ${tile('<a href="#/noise">Noise</a>', lastCal ? `A/A ${fmt.ago(lastCal)}` : '', `<div>${noiseIssues.length ? `<b class="bad-text" title="${esc(noiseIssues.join('; '))}">check: ${noiseIssues.map((x) => esc(x.split(':')[0])).join(', ')}</b>`
          : noAa.length ? `${calTimes.length ? 'ok · ' : ''}<span class="muted" title="${esc(`no A/A yet: ${noAa.join(', ')}`)}">${calTimes.length ? `${noAa.length} without A/A` : 'no A/A yet: default floors'}</span>` : 'ok'}</div>`)}
        ${tile('<a href="#/settings">Auto-run</a>', '', `<div>${w.autorunEnabled ? 'on' : '<b>off</b>'}</div>`)}
      </div>`;

    return `
      <h1>Dashboard</h1>
      ${stand}
      ${masterCard(rels, attention, w.branch, autoScenarios, this.attOpen, this.calmOpen)}
      ${failedLines ? `<div class="card"><div class="tile-label">Failed, not run again</div>${failedLines}</div>` : ''}
      ${attentionResult(attention)}`;
  },
  bind(root) {
    const release = async (list) => {
      const r = await api.get(`/api/release?scenario=${encodeURIComponent(list[0])}`).catch(() => ({}));
      if (!(await confirmLaunch({ title: list.length > 1 ? `Run master vs ${r.tag || 'the release'}: every scenario` : `Run ${scName(list[0])} vs ${r.tag || 'the release'}`,
        scenario: list[0], jobs: list.length, shas: [r.tagSha, r.head], extraBuilds: r.head ? 0 : 1,
        what: `master head vs <b>${esc(r.tag || 'release')}</b>: ${list.map((x) => esc(scName(x))).join(', ')}`, note: 'the dashboard headline; one ABBA job per scenario' }))) return;
      for (const sc of list) await api.post('/api/release', { scenario: sc }).catch((e) => toast(`${sc}: ${e.message}`, true));
      toast(`${list.length} release job(s) queued`);
      refresh();
    };
    const all = $('#release-all', root);
    if (all) all.addEventListener('click', () => guarded(async () => release((await api.get('/api/scenarios')).map((s) => s.name))));
    root.addEventListener('click', (e) => {
      const b = e.target.closest('button[data-release]');
      if (b) guarded(() => release([b.dataset.release]));
      if (e.target.closest('button[data-poll-now]')) guarded(async () => { await api.post('/api/watch/poll'); refresh(); });
    });
    bindJobActions(root, this);
    // the matrix unfolds in place: the data is on the page already
    const unfold = () => {
      root.querySelectorAll('tr.att-detail').forEach((t) => { t.hidden = t.dataset.attFor !== this.attOpen; });
      root.querySelectorAll('button[data-att]').forEach((x) => x.classList.toggle('on', x.dataset.att === this.attOpen));
      root.querySelectorAll('tr.rel-calm').forEach((t) => { t.hidden = !this.calmOpen; });
      const chev = $('[data-rel-calm] .chev', root);
      if (chev) chev.textContent = this.calmOpen ? '▾' : '▸';
    };
    root.addEventListener('click', (e) => {
      const b = e.target.closest('button[data-more]');
      if (b) guarded(() => moreRun(b.dataset.more));
      const sp = e.target.closest('button[data-split]');
      if (sp) guarded(() => splitJob(sp.dataset.split, sp.dataset.mode));
      const c = e.target.closest('button[data-att]');
      if (c) { this.attOpen = this.attOpen === c.dataset.att ? null : c.dataset.att; unfold(); }
      if (e.target.closest('[data-rel-calm]')) { this.calmOpen = !this.calmOpen; if (!this.calmOpen) this.attOpen = null; unfold(); }
      const g = e.target.closest('button[data-att-group]');
      if (g) {
        const parts = [...root.querySelectorAll(`[data-group="${g.dataset.attGroup}"]`)];
        if (parts.length) parts[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
        parts.forEach((x) => { x.classList.add('res-flash'); x.addEventListener('animationend', () => x.classList.remove('res-flash'), { once: true }); });
      }
      if (e.target.closest('button[data-bisect-open]')) guarded(() => startOpenBisects(this));
    });
  },
};

// ------------------------------------------------------------------ router
// Pages are re-rendered only when their content changed, and polled only when they are "live":
// Queue and a running job; every other page refreshes on navigation, a change of its own controls or F5.
let currentView = null, currentParams = null, timer = null, rendering = false, lastHtml = null;
function isLive(view) {
  return typeof view.live === 'function' ? !!view.live() : !!view.live;
}
function schedule() {
  if (timer) clearInterval(timer);
  timer = null;
  if (currentView && currentView.poll) timer = setInterval(() => { if (document.visibilityState === 'visible' && isLive(currentView)) refresh(); }, currentView.poll);
}
function route() {
  const m = location.hash.match(/^#\/([a-z]+)(?:\/([0-9a-fA-F]+))?/);
  const name = setupPending ? 'setup' : m && m[1] === 'jobs' && m[2] ? 'job' : m && m[1] === 'commits' && m[2] ? 'commit' : m && m[1] === 'bisects' && m[2] ? 'bisect'
    : m && m[1] === 'comparisons' && m[2] ? 'comparison'
    : m && views[m[1]] && m[1] !== 'setup' ? m[1] : 'dashboard';
  const params = { id: m && m[2] };
  document.querySelectorAll('header nav a').forEach((a) => a.classList.toggle('active', a.dataset.view === name || (name === 'job' && a.dataset.view === 'queue') || (name === 'bisect' && a.dataset.view === 'bisects') || (name === 'commit' && a.dataset.view === 'commits') || (name === 'comparison' && a.dataset.view === 'compare')));
  currentView = views[name];
  currentParams = params;
  lastHtml = null;
  document.body.classList.add('loading'); // the old page stays until the new one is ready: say so
  refresh(true);
  schedule();
}
export async function refresh(first) {
  if (rendering) return;
  rendering = true;
  const view = currentView, params = currentParams;
  try {
    if (!scTitles.size) await Promise.all([api.get('/api/scenarios'), api.get('/api/settings')]).catch(() => null); // names and links before the first page
    const html = await view.render(params);
    if (view !== currentView) return;
    const active = document.activeElement;
    if (!first && active && active.closest('#app') && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.tagName === 'SELECT')) return;
    if (html !== lastHtml) {
      const y = window.scrollY;
      // a fresh host per render: listeners a view attaches to it die with the old markup instead of piling up
      const host = document.createElement('div');
      host.className = 'view-host';
      host.innerHTML = html;
      app.replaceChildren(host);
      lastHtml = html;
      if (view.bind) view.bind(host);
      window.scrollTo(0, first ? 0 : y); // a new page opens from its top, a refreshed one stays where it was
    }
    updateQueueCount();
    if (window.benchCharts) window.benchCharts(view === views.timeline ? 'timeline' : view === views.job ? 'job' : null, params);
    schedule();
  } catch (e) {
    if (first) app.innerHTML = `<div class="card error">${esc(e.message)}</div>`;
    // a failed background poll keeps the last good page; the next poll or navigation tries again
  } finally {
    rendering = false;
    if (view !== currentView || params !== currentParams) refresh(true); // the page changed while this one was loading
    else document.body.classList.remove('loading');
  }
}
// header: number of queued jobs next to Queue (hidden when empty), refreshed on every render and every 20 s
async function updateQueueCount() {
  try {
    const h = await api.get('/api/health');
    const n = ((h.jobs && h.jobs.queued) || 0) + ((h.jobs && h.jobs.running) || 0), el = $('#queue-count'), bisects = $('#bisect-count'); // waiting + the one running
    if (el) { el.textContent = n ? String(n) : ''; el.style.display = n ? '' : 'none'; }
    if (bisects) { bisects.textContent = h.bisects ? String(h.bisects) : ''; bisects.style.display = h.bisects ? '' : 'none'; }
  } catch (e) { /* keep the last value */ }
}
setInterval(() => { if (document.visibilityState === 'visible') updateQueueCount(); }, 20000);
window.addEventListener('hashchange', route);
// The box in the header: a sha, a ticket, a PR, the number of a job or a bisection, words of a subject. "/" jumps to it.
function bindSearch() {
  const box = $('#search'), list = $('#search-results');
  let hits = [], at = 0, asked = 0;
  const close = () => { list.hidden = true; hits = []; };
  const show = () => {
    list.innerHTML = hits.length ? hits.map((h, i) => `<a class="${i === at ? 'on' : ''}" href="${esc(h.href)}"><span class="search-kind">${esc(h.title)}</span> <span class="muted">${esc(h.note || '')}</span></a>`).join('')
      : '<div class="muted">nothing found</div>';
    list.hidden = false;
  };
  const ask = async () => {
    const q = box.value.trim(), me = ++asked;
    if (!q) { close(); return; }
    const found = await api.get(`/api/search?q=${encodeURIComponent(q)}&limit=10`).catch(() => []);
    if (me !== asked) return; // a newer letter is on its way
    hits = found;
    at = 0;
    show();
  };
  let timer = null;
  box.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(ask, 180); });
  box.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { box.blur(); close(); }
    if (!hits.length) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); at = (at + (e.key === 'ArrowDown' ? 1 : hits.length - 1)) % hits.length; show(); }
    if (e.key === 'Enter') { location.hash = hits[at].href; box.value = ''; box.blur(); close(); }
  });
  box.addEventListener('blur', () => setTimeout(close, 150)); // a click on a result lands first
  document.addEventListener('keydown', (e) => {
    const t = e.target.tagName;
    if (e.key === '/' && t !== 'INPUT' && t !== 'TEXTAREA' && t !== 'SELECT') { e.preventDefault(); box.focus(); }
  });
}
bindSearch();
// a focused form field blocks re-rendering (so polling does not close an open list); a picked value must apply at once,
// so drop the focus from a select before the view's own change handler calls refresh()
document.addEventListener('change', (e) => {
  if ((e.target.tagName === 'SELECT' || e.target.type === 'checkbox') && e.target.closest('#app') && !e.target.closest('form')) e.target.blur();
}, true);
api.get('/api/stand').then((s) => { setupPending = s.needsSetup; }).catch(() => null).finally(() => {
  $('header nav').hidden = setupPending;
  $('.search').hidden = setupPending;
  route();
});
