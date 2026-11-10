// Charts for Firewatch (uPlot): commit trends on the dashboard, overlaid per-second series on the job page.
// app.js calls window.benchCharts(view, params) after every render; charts are rebuilt only when their data changed.
(function () {
  'use strict';
  const cache = { key: null, hash: null, node: null, plots: [] };
  let hovered = null; // with a synced cursor only the plot under the mouse shows its tooltip
  const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const fmtNum = (v, d = 0) => v == null || Number.isNaN(v) ? '–' : Number(v).toLocaleString('en-US', { maximumFractionDigits: d, minimumFractionDigits: d });
  const fmtPct = (v, d = 1) => v == null ? '–' : (v > 0 ? '+' : '') + Number(v).toFixed(d) + '%';
  const shortSha = (s) => (s || '').slice(0, 7);
  // Axis tick label: k/M for large values, otherwise just enough decimals to tell neighbouring ticks apart.
  const decimalsFor = (step, v) => step > 0 ? Math.max(0, Math.min(4, -Math.floor(Math.log10(step) + 1e-9))) : (Number.isInteger(v) ? 0 : 2);
  const axisLabel = (v, step) => {
    const scale = Math.abs(v) >= 1e6 ? 1e6 : Math.abs(v) >= 1e3 ? 1e3 : 1;
    const suffix = scale === 1e6 ? 'M' : scale === 1e3 ? 'k' : '';
    return (v / scale).toFixed(decimalsFor(step / scale, v / scale)) + suffix;
  };
  const axisValues = (u, splits) => {
    const step = splits.length > 1 ? Math.abs(splits[1] - splits[0]) : 0;
    return splits.map((v) => axisLabel(v, step));
  };

  // dashboard: one row per metric — Δ vs parent on the left, level with the phase range on the right
  const ROWS = [
    { key: 'throughput', title: 'Throughput', unit: 'ops/s', digits: 0, higherBetter: true },
    { key: 'serverCpuUsPerOp', title: 'Server CPU per op', unit: 'µs', digits: 2, higherBetter: false },
    { key: 'p99Us', title: 'Latency p99', unit: 'µs', digits: 0, higherBetter: false },
    { key: 'allocKbPerOp', title: 'Allocation per op', unit: 'KB', digits: 2, higherBetter: false },
  ];
  const BAND_WINDOW = 9; // rolling median over this many measured commits is the centre of the noise band
  const PHASE_SERIES = [
    { key: 'ops', title: 'Throughput', unit: 'ops/s', digits: 0, main: true },
    { key: 'p99Us', title: 'Latency p99', unit: 'µs', digits: 0, main: true },
    { key: 'serverCpuPct', title: 'Server JVM CPU', unit: '%', digits: 1, main: true },
    { key: 'p50Us', title: 'Latency p50', unit: 'µs', digits: 0 },
    { key: 'gcPauseMs', title: 'GC pause per second', unit: 'ms', digits: 1 },
    { key: 'allocMb', title: 'Allocation per second', unit: 'MB', digits: 1 },
    { key: 'heapUsedMb', title: 'Heap used', unit: 'MB', digits: 0 },
    { key: 'machineCpuPct', title: 'Machine CPU', unit: '%', digits: 1 },
  ];
  const PHASE_COLORS = { A1: '#2563eb', A2: '#7cb0ff', B1: '#ea580c', B2: '#fdab6b', S: '#2563eb' };

  function theme() {
    return { fg: css('--fg'), muted: css('--muted'), line: css('--line'), accent: css('--accent'), ok: css('--ok'), bad: css('--bad') };
  }

  function destroy() {
    cache.plots.forEach((p) => { try { p.destroy(); } catch (e) { /* ignore */ } });
    cache.plots = [];
    cache.node = null;
  }

  async function getJson(path) {
    const res = await fetch(path);
    const text = await res.text();
    const data = text ? JSON.parse(text) : null;
    if (!res.ok) throw new Error((data && data.error) || res.statusText);
    return data;
  }

  window.benchCharts = async function (view, params) {
    const holder = document.getElementById('charts');
    if (!holder || (view !== 'timeline' && view !== 'job')) { if (!holder) destroy(); return; }
    const box = holder.closest('details');
    if (box && !box.open) return; // folded "Charts" on Timeline: nothing to draw until it is opened
    const key = view === 'timeline' ? `timeline:${holder.dataset.scenario}:${holder.dataset.from}:${holder.dataset.to}` : `job:${params.id}`;
    let data;
    try {
      data = view === 'timeline'
        ? await Promise.all([
          getJson(`/api/trend?scenario=${encodeURIComponent(holder.dataset.scenario)}&limit=${holder.dataset.limit || 60}`),
          getJson(`/api/noise?scenario=${encodeURIComponent(holder.dataset.scenario)}`),
        ]).then(([points, noise]) => {
          // Timeline zoom: keep the points whose commit lies in the selected range of commit numbers
          const from = Number(holder.dataset.from || -Infinity), to = Number(holder.dataset.to || Infinity);
          return { points: points.filter((p) => p.seq == null || (p.seq >= from && p.seq <= to)), floors: noise.floors || {} };
        })
        : await getJson(`/api/jobs/${params.id}/series`);
    } catch (e) {
      holder.innerHTML = `<div class="muted">charts unavailable: ${esc(e.message)}</div>`;
      return;
    }
    const current = document.getElementById('charts');
    if (!current) return;
    const hash = JSON.stringify(data) + theme().fg;
    if (cache.key === key && cache.hash === hash && cache.node) {
      if (cache.node !== current) current.replaceWith(cache.node); // page re-rendered, data unchanged: keep the plots
      return;
    }
    destroy();
    cache.key = key;
    cache.hash = hash;
    cache.node = current;
    current.innerHTML = '';
    if (view === 'timeline') renderTrends(current, data.points, data.floors); else renderPhases(current, data);
  };

  // ------------------------------------------------------------------ shared
  function chartBox(holder, title, subtitle) {
    const box = document.createElement('div');
    box.className = 'chart';
    box.innerHTML = `<h3>${esc(title)} ${subtitle ? `<span class="muted">${esc(subtitle)}</span>` : ''}</h3><div class="plot"></div>`;
    holder.appendChild(box);
    return box.querySelector('.plot');
  }

  function baseOpts(width, t, unit, digits) {
    return {
      width, height: 210,
      cursor: { drag: { x: false, y: false }, points: { size: 7 } },
      axes: [
        { stroke: t.muted, grid: { stroke: t.line, width: 1 }, ticks: { stroke: t.line, width: 1 }, font: '11px sans-serif' },
        { stroke: t.muted, grid: { stroke: t.line, width: 1 }, ticks: { stroke: t.line, width: 1 }, font: '11px sans-serif', size: 56,
          values: axisValues, label: unit, labelFont: '11px sans-serif', labelSize: 14 },
      ],
      scales: { x: { time: false } },
    };
  }

  function observeWidth(holder) {
    if (holder.__ro) return;
    holder.__ro = new ResizeObserver(() => {
      cache.plots.forEach((p) => {
        const w = p.root.parentElement ? p.root.parentElement.clientWidth : 0;
        if (w > 50 && Math.abs(w - p.width) > 4) p.setSize({ width: w, height: p.height });
      });
    });
    holder.__ro.observe(holder);
  }

  function tooltip(u) {
    let tip = u.over.querySelector('.chart-tip');
    if (!tip) {
      tip = document.createElement('div');
      tip.className = 'chart-tip';
      tip.style.display = 'none';
      u.over.appendChild(tip);
    }
    return tip;
  }

  // ------------------------------------------------------------------ dashboard
  function rollingMedian(values, win) {
    return values.map((_, i) => {
      const w = values.slice(Math.max(0, i - win + 1), i + 1).filter((v) => v != null).sort((a, b) => a - b);
      return w.length ? w[Math.floor(w.length / 2)] : null;
    });
  }

  function renderTrends(holder, points, floors) {
    holder.classList.add('chart-grid', 'two-col');
    if (!points.length) {
      holder.innerHTML = '<div class="muted">No finished jobs for this scenario yet: trends appear after the first run.</div>';
      return;
    }
    const t = theme();
    // x = commit number on the branch: a segment spans its commits, gaps between measured commits stay visible
    const xs = points.map((p, i) => (p.seq != null ? p.seq : i));
    const baseX = (p, i) => (p.baseSeq != null ? p.baseSeq : xs[i] - 1);
    const xMin = Math.min(...points.map(baseX)), xMax = Math.max(...xs);
    const width = Math.max(320, Math.floor((holder.clientWidth || 900) / (holder.clientWidth > 900 ? 2 : 1)) - 40);
    const xAxis = (opts) => {
      opts.axes[0].values = (u, splits) => splits.map((v) => (Number.isInteger(v) ? `#${v}` : ''));
      opts.axes[0].incrs = [1, 2, 5, 10, 20, 50, 100, 200, 500];
      opts.axes[0].space = 64;
      opts.scales.x = { time: false, range: () => [xMin - 0.5, xMax + 0.5] };
      opts.legend = { show: false };
    };
    const clickable = (u) => {
      u.over.style.cursor = 'pointer';
      u.over.addEventListener('mouseenter', () => { hovered = u; });
      u.over.addEventListener('mouseleave', () => { if (hovered === u) hovered = null; tooltip(u).style.display = 'none'; });
      u.over.addEventListener('click', () => { const i = u.cursor.idx; if (i != null && points[i]) location.hash = `#/commits/${points[i].sha}`; });
      cache.plots.push(u);
    };
    const vline = (u, ctx, i, v1, v2, color, w) => {
      if (v1 == null || v2 == null) return;
      const x = u.valToPos(xs[i], 'x', true), y1 = u.valToPos(v1, 'y', true), y2 = u.valToPos(v2, 'y', true);
      ctx.strokeStyle = color; ctx.lineWidth = w * devicePixelRatio; ctx.lineCap = 'round';
      ctx.beginPath(); ctx.moveTo(x, y1); ctx.lineTo(x, y2); ctx.stroke();
    };

    // all dashboard charts share one cursor: hovering a commit anywhere highlights it in every chart
    const sync = (opts) => { opts.cursor.sync = { key: 'dashboard-trend' }; };

    // levels: dot = B (pooled runs: ring), bar = min..max over all phases (red when wider than the noise floor), faint band = rolling median ± floor
    const levelChart = (m) => {
      const values = points.map((p) => p.values[m.key] ?? null);
      if (values.every((v) => v == null)) { chartBox(holder, m.title, 'no data yet'); return; }
      const verdicts = points.map((p) => p.metricVerdict[m.key] || 'no-change');
      const floor = floors[m.key];
      const med = rollingMedian(values, BAND_WINDOW);
      const hi = med.map((v) => (v == null || floor == null ? null : v * (1 + floor / 100)));
      const lo = med.map((v) => (v == null || floor == null ? null : v * (1 - floor / 100)));
      const plotEl = chartBox(holder, m.title, `${m.unit} · bar = range of the run's phases (red: wider than ${floor != null ? `±${floor.toFixed(1)}%` : 'the floor'})`);
      const opts = baseOpts(width, t, m.unit, m.digits);
      xAxis(opts);
      sync(opts);
      opts.hooks = {
        setCursor: [(u) => trendTip(u, points, m, m.key)],
        drawSeries: [(u, si) => {
          if (si !== 3) return; // under the dots, over the band
          const ctx = u.ctx; ctx.save();
          points.forEach((p, i) => {
            const mn = p.phaseMin[m.key], mx = p.phaseMax[m.key], v = values[i];
            if (mn == null || mx == null || !v) return;
            const wide = floor != null && (mx - mn) / v * 100 > floor;
            vline(u, ctx, i, mn, mx, wide ? t.bad : t.muted, 3);
          });
          ctx.restore();
        }],
      };
      const mark = (pred) => values.map((x, i) => (pred(i) ? x : null));
      opts.series = [
        {},
        { label: 'band high', stroke: 'transparent', points: { show: false } },
        { label: 'band low', stroke: 'transparent', points: { show: false } },
        { label: m.title, stroke: t.accent + '88', width: 1, points: { show: true, size: 6, fill: t.accent } },
        { label: 'several runs', stroke: t.accent, width: 2, points: { show: true, size: 11, fill: 'transparent' }, paths: () => null },
        { label: 'regression', stroke: t.bad, points: { show: true, size: 9, fill: t.bad }, paths: () => null },
        { label: 'improvement', stroke: t.ok, points: { show: true, size: 9, fill: t.ok }, paths: () => null },
      ];
      opts.bands = [{ series: [1, 2], fill: t.muted + '14' }];
      const all = values.concat(hi, lo, points.map((p) => p.phaseMin[m.key]), points.map((p) => p.phaseMax[m.key])).filter((v) => v != null);
      opts.scales.y = { range: () => padRange(Math.min(...all), Math.max(...all)) };
      const data = [xs, hi, lo, values, mark((i) => (points[i].runs || 1) > 1), mark((i) => verdicts[i] === 'regression'), mark((i) => verdicts[i] === 'improvement')];
      clickable(new uPlot(opts, data, plotEl));
    };
    // Timeline: the map shows Δ; the charts are the levels with the phase range of every run (stand health)
    for (const m of ROWS) levelChart(m);
    observeWidth(holder);
  }

  function padRange(min, max) {
    if (min === max) return [min * 0.9, max * 1.1 || 1];
    const pad = (max - min) * 0.15;
    return [min - pad < 0 && min >= 0 ? 0 : min - pad, max + pad];
  }

  function trendTip(u, points, m, metric) {
    const tip = tooltip(u);
    const i = u.cursor.idx;
    if (i == null || !points[i] || (hovered && hovered !== u)) { tip.style.display = 'none'; return; }
    const p = points[i];
    let line;
    if (metric == null) {
      line = `start <b>${esc(fmtNum(p.values.serverStartMs))} ms</b> (${esc(fmtPct(p.deltaPct.serverStartMs))}) · preload <b>${esc(fmtNum(p.values.preloadMs))} ms</b> (${esc(fmtPct(p.deltaPct.preloadMs))})`;
    } else {
      const delta = p.deltaPct[metric] != null ? ` · Δ ${fmtPct(p.deltaPct[metric])} vs ${(p.span || 1) > 1 ? 'segment start' : 'parent'}` : '';
      const v = p.metricVerdict[metric] || '';
      line = `${esc(m.title)}: <b>${esc(fmtNum(p.values[metric], m.digits))} ${esc(m.unit)}</b>${esc(delta)}${v ? ` · ${esc(v)}` : ''}`;
    }
    tip.innerHTML = `<b>${esc(shortSha(p.sha))}</b> ${esc((p.subject || '').slice(0, 70))}<br>` +
      `<span class="tip-muted">${esc(p.author || '')} · ${esc((p.committedAt || '').slice(0, 16).replace('T', ' '))}</span><br>` +
      `${line}<br>${(p.span || 1) > 1 ? `<span class="tip-muted">segment of ${p.span} commits since ${esc(shortSha(p.baseSha))}</span><br>` : ''}<span class="tip-muted">${(p.runs || 1) > 1 ? `${p.runs} runs pooled · ` : ''}click to open the commit</span>`;
    tip.style.display = 'block';
    const left = u.cursor.left, top = u.cursor.top;
    const w = u.over.clientWidth;
    tip.style.left = (left > w / 2 ? left - tip.offsetWidth - 12 : left + 12) + 'px';
    tip.style.top = Math.max(0, top - 10) + 'px';
  }

  // ------------------------------------------------------------------ job page
  function renderPhases(holder, bundle) {
    holder.classList.add('chart-grid');
    const runs = bundle.runs.filter((r) => r.series && r.series.t && r.series.t.length);
    if (!runs.length) {
      holder.innerHTML = '<div class="muted">Per-second series appear here when the first phase finishes.</div>';
      return;
    }
    const t = theme();
    const n = Math.max(...runs.map((r) => r.series.t.length));
    const xs = Array.from({ length: n }, (_, i) => i);
    const measureStart = runs[0].series.measureStartIndex || 0;
    const width = Math.max(320, Math.floor((holder.clientWidth || 900) / (holder.clientWidth > 900 ? 2 : 1)) - 40);
    // one legend over all the charts: it tells the phases apart and shows the values of the chart under the mouse
    const legend = document.createElement('div');
    legend.className = 'chart-legend';
    legend.innerHTML = runs.map((r) => `<span><i class="${r.phase.endsWith('2') ? 'dashed' : ''}" style="color:${PHASE_COLORS[r.phase] || t.accent}"></i>${esc(r.phase)} <span class="mono">${esc(shortSha(r.sha))}</span> <b data-phase="${esc(r.phase)}"></b></span>`).join('')
      + `${measureStart ? `<span>grey: warmup ${measureStart} s</span>` : ''}<span data-at></span>`;
    holder.appendChild(legend);
    const showValues = (u, m) => {
      const i = u.cursor.idx;
      runs.forEach((r, k) => { legend.querySelector(`[data-phase="${r.phase}"]`).textContent = i == null ? '' : fmtNum(u.data[k + 1][i], m.digits); });
      legend.querySelector('[data-at]').textContent = i == null ? '' : `${m.title}, ${i} s`;
    };
    for (const m of PHASE_SERIES) {
      const cols = runs.map((r) => {
        const arr = r.series[m.key] || [];
        return xs.map((i) => (arr[i] == null ? null : arr[i]));
      });
      if (cols.every((c) => c.every((v) => v == null))) continue;
      const plotEl = chartBox(holder, m.title, m.unit);
      if (!m.main) plotEl.parentElement.classList.add('chart-more');
      const opts = baseOpts(width, t, m.unit, m.digits);
      opts.legend = { show: false };
      opts.axes[0].label = 's since load start';
      opts.axes[0].labelFont = '11px sans-serif';
      opts.axes[0].labelSize = 14;
      opts.cursor.sync = { key: `job-${bundle.jobId}` };
      // the scale follows the measure window: a start-up spike of the warmup would flatten everything else
      const measured = cols.flatMap((c) => c.slice(measureStart)).filter((v) => v != null);
      opts.scales.y = { range: (u, min, max) => (measured.length ? padRange(Math.min(...measured), Math.max(...measured)) : padRange(min, max)) };
      opts.series = [{ label: 'time', value: (u, v) => v == null ? '' : v + ' s' }].concat(runs.map((r) => ({
        label: `${r.phase} ${shortSha(r.sha)}`,
        stroke: PHASE_COLORS[r.phase] || t.accent,
        width: r.phase.endsWith('2') ? 1 : 1.5,
        dash: r.phase.endsWith('2') ? [4, 3] : undefined,
        points: { show: false },
        spanGaps: false,
        value: (u, v) => v == null ? '–' : fmtNum(v, m.digits),
      })));
      opts.hooks = {
        setCursor: [(u) => { if (hovered === u) showValues(u, m); }],
        drawClear: [(u) => {
          if (!measureStart) return;
          const ctx = u.ctx;
          const x0 = u.valToPos(0, 'x', true), x1 = u.valToPos(measureStart, 'x', true);
          ctx.save();
          ctx.fillStyle = t.muted + '22';
          ctx.fillRect(x0, u.bbox.top, x1 - x0, u.bbox.height);
          ctx.restore();
        }],
      };
      const plot = new uPlot(opts, [xs].concat(cols), plotEl);
      plot.over.addEventListener('mouseenter', () => { hovered = plot; });
      plot.over.addEventListener('mouseleave', () => { if (hovered === plot) hovered = null; });
      cache.plots.push(plot);
    }
    observeWidth(holder);
  }
})();
