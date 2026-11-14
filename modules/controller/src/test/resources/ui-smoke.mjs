// UI smoke test: renders every page of app.js against a running controller and fails on the first page that throws
// (a field the API no longer has, a typo in a template). Usage: node ui-smoke.mjs <controller base URL> <app.js URL>.
// A minimal stand-in for the browser: the pages only read data and return HTML; listeners and timers never run.
const [base, appUrl] = process.argv.slice(2);
const el = () => ({
  addEventListener() {}, querySelector: () => null, querySelectorAll: () => [], appendChild() {}, replaceChildren() {},
  remove() {}, focus() {}, classList: { toggle() {}, add() {}, remove() {} }, style: {}, dataset: {},
});
globalThis.window = globalThis;
globalThis.addEventListener = () => {};
globalThis.document = { querySelector: () => el(), querySelectorAll: () => [], createElement: () => el(), addEventListener() {},
  body: el(), visibilityState: 'hidden', activeElement: null };
globalThis.location = { hash: '#/scenarios' };
globalThis.history = { replaceState() {} };
globalThis.localStorage = { getItem: () => null, setItem() {} };
globalThis.setInterval = () => 0;
const realFetch = globalThis.fetch;
globalThis.fetch = (path, opts) => realFetch(new URL(path, base), opts);
const get = async (p) => (await realFetch(new URL(p, base))).json();

const { views } = await import(appUrl);
await import(new URL('plugins.js', appUrl).href); // the section of the plugins registers its pages
const done = (await get('/api/jobs?filter=history&limit=1'))[0], active = (await get('/api/jobs?filter=active&limit=1'))[0];
const bisect = (await get('/api/bisects?limit=1'))[0];
const comparisons = await get('/api/comparisons');
const pages = [['dashboard'], ['timeline'], ['commits'], ['bisects'], ['queue'], ['noise'], ['compare'], ['scenarios'], ['settings'], ['setup'],
  ['job', { id: done && done.id }], ['job', { id: active && active.id }], ['commit', { id: done && done.shaB }],
  ['bisect', { id: bisect && bisect.id }], ...comparisons.map((c) => ['comparison', { id: c.id }]), ['plugins'],
  ...(await get('/api/plugins')).plugins.flatMap((p) => ['history', 'compare', 'scenarios', 'settings']
    .map((tab) => ['plugin', { hash: `#/plugin?name=${encodeURIComponent(p.name)}&tab=${tab}` }]))];
let failed = 0;
for (const [name, params = {}] of pages) {
  if (Object.values(params).some((v) => v == null)) { console.log(`FAIL ${name}: no data to render it with`); failed++; continue; }
  try {
    if (params.hash) location.hash = params.hash; // a page that reads its address
    const html = await views[name].render(params);
    if (typeof html !== 'string' || !html.length) throw new Error('rendered nothing');
    if (name === 'dashboard' && !html.includes('att-cell')) throw new Error('Needs attention shows no cell');
    console.log(`ok   ${name} (${html.length} chars)`);
  }
  catch (e) {
    console.log(`FAIL ${name}: ${e && e.stack || e}`);
    failed++;
  }
}
process.exit(failed ? 1 : 0);
