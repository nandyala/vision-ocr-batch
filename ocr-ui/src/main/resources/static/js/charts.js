/* Small dependency-free SVG charts. */
import { esc, num } from './core.js';

/**
 * Stacked columns. data: [{label, values: {key: n}}], series: [{key, label, color}]
 */
export function stackedBars(data, series, opts = {}) {
  const W = opts.width || 640, H = opts.height || 220, padL = 34, padB = 26, padT = 10, padR = 8;
  const totals = data.map(d => series.reduce((s, x) => s + (d.values[x.key] || 0), 0));
  const { max, step: tick } = niceScale(Math.max(1, ...totals));
  const iw = W - padL - padR, ih = H - padT - padB;
  const bw = Math.min(38, iw / Math.max(1, data.length) * 0.62);
  const step = iw / Math.max(1, data.length);
  let s = '<svg class="chart" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="' + esc(opts.label || 'Chart') + '">';
  for (let i = 0; i <= 4; i++) {
    const y = padT + ih - ih * i / 4;
    s += '<line class="grid-line" x1="' + padL + '" x2="' + (W - padR) + '" y1="' + y + '" y2="' + y + '"/>' +
      '<text x="' + (padL - 6) + '" y="' + (y + 4) + '" text-anchor="end">' + num(tick * i) + '</text>';
  }
  data.forEach((d, i) => {
    const x = padL + step * i + (step - bw) / 2;
    let y = padT + ih;
    series.forEach(se => {
      const v = d.values[se.key] || 0;
      if (!v) return;
      const h = ih * v / max;
      y -= h;
      s += '<rect class="bar" x="' + x.toFixed(1) + '" y="' + y.toFixed(1) + '" width="' + bw.toFixed(1) + '" height="' + Math.max(1, h).toFixed(1) +
        '" rx="2" fill="' + se.color + '"><title>' + esc(d.label + ' · ' + se.label + ': ' + v) + '</title></rect>';
    });
    const every = Math.ceil(data.length / 10);
    if (i % every === 0 || i === data.length - 1) {
      s += '<text x="' + (x + bw / 2).toFixed(1) + '" y="' + (H - 8) + '" text-anchor="middle">' + esc(d.label) + '</text>';
    }
  });
  s += '</svg>';
  if (opts.legend !== false) {
    s += '<div class="legend">' + series.map(se => '<span class="legend__item"><span class="legend__swatch" style="background:' + se.color + '"></span>' + esc(se.label) + '</span>').join('') + '</div>';
  }
  return s;
}

/** Donut. segments: [{label, value, color}] */
export function donut(segments, center, sub) {
  const total = segments.reduce((s, x) => s + x.value, 0);
  const r = 62, c = 2 * Math.PI * r;
  let off = 0;
  let s = '<div class="donut-wrap"><svg viewBox="0 0 168 168" role="img" aria-label="' + esc(center + ' ' + (sub || '')) + '">' +
    '<circle cx="84" cy="84" r="' + r + '" fill="none" stroke="var(--line-2)" stroke-width="20"/>';
  segments.forEach(seg => {
    if (!seg.value || !total) return;
    const len = c * seg.value / total;
    s += '<circle cx="84" cy="84" r="' + r + '" fill="none" stroke="' + seg.color + '" stroke-width="20" stroke-dasharray="' +
      Math.max(0, len - 1.5).toFixed(2) + ' ' + c.toFixed(2) + '" stroke-dashoffset="' + (-off).toFixed(2) + '" transform="rotate(-90 84 84)"><title>' +
      esc(seg.label + ': ' + seg.value) + '</title></circle>';
    off += len;
  });
  s += '<text x="84" y="82" text-anchor="middle" style="font-size:26px;fill:var(--ink);font-weight:500">' + esc(center) + '</text>' +
    '<text x="84" y="102" text-anchor="middle" style="font-size:11.5px">' + esc(sub || '') + '</text></svg>';
  s += '<div class="donut-legend">' + segments.map(seg =>
    '<div class="donut-legend__row"><span class="legend__swatch" style="background:' + seg.color + '"></span>' + esc(seg.label) +
    '<b>' + num(seg.value) + '</b></div>').join('') + '</div></div>';
  return s;
}

/** Horizontal bars as HTML rows. rows: [{label, value, display, tone, title}] */
export function hbars(rows, max) {
  const m = max || Math.max(1, ...rows.map(r => r.value));
  return rows.map(r => '<div class="bar-row" title="' + esc(r.title || '') + '"><span class="ellipsis">' + r.label + '</span>' +
    '<span class="bar-row__track"><span class="bar-row__fill' + (r.tone ? ' bar-row__fill--' + r.tone : '') + '" style="width:' +
    (100 * r.value / m).toFixed(1) + '%"></span></span><span class="right nowrap" style="font-variant-numeric:tabular-nums">' +
    esc(r.display != null ? r.display : num(r.value)) + '</span></div>').join('');
}

/** Four grid steps of a whole, round size (1, 2, 3, 5, 10, 25, 50...) so axis labels are evenly spaced integers. */
function niceScale(v) {
  const raw = Math.max(1, Math.ceil(v / 4));
  const p = Math.pow(10, Math.floor(Math.log10(raw)));
  let step = 10 * p;
  for (const m of [1, 2, 2.5, 3, 4, 5, 6, 8, 10]) { if (m * p >= raw && Number.isInteger(m * p)) { step = m * p; break; } }
  return { max: step * 4, step };
}
