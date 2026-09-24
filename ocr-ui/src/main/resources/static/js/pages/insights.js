import { api, state, esc, icon, pageHead, kpi, pct, num, humanize, docTypeLabel, fmtDate, reasonLabel, pager, qs, empty, isSensitive, mask, REASONS, displayName } from '../core.js';
import { donut, hbars } from '../charts.js';

const REASON_COLORS = { OCR_MISREAD: '#008a00', WRONG_REGION: '#1f5fa8', MISSING: '#e3a008', FORMAT: '#6b4fbb', OTHER: '#8c8c8c', CONFIRMED: '#54b848' };

export async function mount(el) {
  const types = state.meta.docTypes || [];
  const f = { docType: '', field: '', reason: '', includeInactive: false, page: 0 };
  el.innerHTML = pageHead('Model insights',
    'How well the AI model reads each field, and what reviewers corrected. Every correction in <code>ocr.doc_field_correction</code> is a labelled example for the next training round.',
    '<a class="btn btn--primary" id="export" href="/api/corrections/export">' + icon('download') + 'Export corrections for retraining</a>') +
    '<div id="top"></div>' +
    '<section class="card" style="margin-top:20px"><div class="card__head"><h2>Field accuracy</h2><span class="muted small">accuracy = values not corrected by a reviewer</span>' +
    '<select class="select select--sm" id="dt" style="width:auto;margin-left:auto"><option value="">All document types</option>' +
    types.map(t => '<option value="' + esc(t.docType) + '">' + esc(docTypeLabel(t.docType)) + '</option>').join('') + '</select></div>' +
    '<div class="table-wrap" id="quality"></div></section>' +
    '<section class="card" style="margin-top:20px"><div class="card__head"><h2>Correction log</h2><span class="muted small" id="log-count"></span></div>' +
    '<div class="filterbar"><select class="select select--sm" id="f-reason"><option value="">All reasons</option>' +
    REASONS.concat([{ id: 'CONFIRMED', label: 'Confirmed correct' }]).map(r => '<option value="' + r.id + '">' + esc(r.label) + '</option>').join('') + '</select>' +
    '<input class="input input--sm" id="f-field" placeholder="Field name" style="width:200px">' +
    '<label class="toggle"><input type="checkbox" id="f-inactive">Include replaced corrections</label></div>' +
    '<div class="table-wrap" id="log"></div><div id="log-pg"></div></section>';

  async function loadTop() {
    const [ov, stats] = await Promise.all([api('/api/overview?days=14'), api('/api/corrections/stats')]);
    const k = ov.kpis;
    const acc = k.fieldsExtracted ? 1 - k.corrections / k.fieldsExtracted : null;
    // confirmations are not corrections (they have their own KPI), so the donut total matches the Corrections KPI
    const segs = (stats.byReason || []).filter(r => r.reason !== 'CONFIRMED').map(r => ({ label: reasonLabel(r.reason), value: r.cnt, color: REASON_COLORS[r.reason] || '#8c8c8c' }));
    el.querySelector('#top').innerHTML = '<div class="kpis">' +
      kpi({ label: 'Field accuracy', value: acc == null ? '–' : (acc * 100).toFixed(1), unit: acc == null ? '' : '%', iconName: 'target', tone: 'violet', hint: num(k.fieldsExtracted) + ' fields extracted' }) +
      kpi({ label: 'Corrections', value: num(k.corrections), iconName: 'edit', tone: 'info', hint: 'Values fixed by reviewers' }) +
      kpi({ label: 'Confirmations', value: num(k.confirmations), iconName: 'check', hint: 'Flagged, but the model was right' }) +
      kpi({ label: 'Training candidates', value: num(stats.trainingCandidates), iconName: 'sparkle', tone: 'warn', hint: 'Documents with corrections not yet used for training' }) +
      '</div><div class="grid grid--3">' +
      '<section class="card"><div class="card__head"><h2>Why values were corrected</h2></div><div class="card__body">' +
      (segs.length ? donut(segs, num(segs.reduce((s, x) => s + x.value, 0)), 'corrections') : '<p class="muted">No corrections yet.</p>') + '</div></section>' +
      '<section class="card"><div class="card__head"><h2>Most corrected fields</h2></div><div class="card__body">' +
      ((stats.byField || []).length ? hbars(stats.byField.map(x => ({ label: esc(humanize(x.field_name)) + ' <span class="muted small">' + esc(docTypeLabel(x.doc_type)) + '</span>', value: x.cnt, tone: 'warn' })))
        : '<p class="muted">No corrections yet.</p>') + '</div></section>' +
      '<section class="card"><div class="card__head"><h2>Reviewers</h2></div><div class="card__body">' +
      ((stats.byReviewer || []).length ? hbars(stats.byReviewer.map(x => ({ label: esc(x.corrected_by), value: x.cnt, title: 'last ' + fmtDate(x.last_at) })))
        : '<p class="muted">Nobody has reviewed yet.</p>') + '</div></section></div>';
  }

  async function loadQuality() {
    const rows = await api('/api/insights/fields' + qs({ docType: el.querySelector('#dt').value }));
    const box = el.querySelector('#quality');
    if (!rows.length) { box.innerHTML = empty('No fields extracted yet', '', 'chart'); return; }
    box.innerHTML = '<table class="table"><thead><tr><th>Field</th><th>Type</th><th class="num">Extracted</th><th class="num">Flagged</th><th class="num">Corrected</th><th class="num">Confirmed</th><th>Avg. confidence</th><th style="min-width:220px">Accuracy</th></tr></thead><tbody>' +
      rows.map(r => {
        const acc = r.extracted ? 1 - r.corrected / r.extracted : null;
        const tone = acc == null ? '' : acc >= 0.97 ? '' : acc >= 0.9 ? 'warn' : 'danger';
        return '<tr><td><b>' + esc(humanize(r.field_name)) + '</b>' + (r.configured ? '' : ' <span class="tag">not configured</span>') + '<div class="small muted mono">' + esc(r.field_name) + '</div></td>' +
          '<td>' + esc(docTypeLabel(r.doc_type)) + '</td><td class="num">' + num(r.extracted) + '</td><td class="num">' + num(r.flagged) + '</td>' +
          '<td class="num">' + (r.corrected ? '<a href="#" data-field="' + esc(r.field_name) + '">' + num(r.corrected) + '</a>' : '0') + '</td><td class="num">' + num(r.confirmed) + '</td>' +
          '<td>' + pct(r.avg_confidence) + '</td>' +
          '<td><div class="bar-row" style="grid-template-columns:1fr 56px;padding:0"><span class="bar-row__track"><span class="bar-row__fill' + (tone ? ' bar-row__fill--' + tone : '') +
          '" style="width:' + (acc == null ? 0 : acc * 100).toFixed(1) + '%"></span></span><b class="right">' + (acc == null ? '–' : (acc * 100).toFixed(1) + '%') + '</b></div></td></tr>';
      }).join('') + '</tbody></table>';
  }

  async function loadLog() {
    const d = await api('/api/corrections' + qs({ docType: el.querySelector('#dt').value, field: f.field, reason: f.reason, includeInactive: f.includeInactive, page: f.page, size: 15 }));
    el.querySelector('#log-count').textContent = num(d.total) + ' correction' + (d.total === 1 ? '' : 's');
    const box = el.querySelector('#log');
    if (!d.items.length) { box.innerHTML = empty('No corrections', 'Corrections made in the review screen appear here.', 'edit'); el.querySelector('#log-pg').innerHTML = ''; return; }
    box.innerHTML = '<table class="table table--compact"><thead><tr><th>When</th><th>Document</th><th>Field</th><th>Model read</th><th>Corrected to</th><th>Reason</th><th>By</th><th></th></tr></thead><tbody>' +
      d.items.map(c => {
        const s = isSensitive(c.field_name);
        return '<tr class="is-clickable" data-href="#/documents/' + c.doc_id + '"><td class="nowrap">' + esc(fmtDate(c.corrected_at)) + '</td>' +
          '<td><span class="muted">#' + c.doc_id + '</span> ' + esc(displayName(c.file_name)) + '</td><td>' + esc(humanize(c.field_name)) + '</td>' +
          '<td class="mono small"><s style="color:var(--danger)">' + esc(s ? mask(c.original_value) : (c.original_value || '–')) + '</s></td>' +
          '<td class="mono small" style="color:var(--g-800);font-weight:600">' + esc(s ? mask(c.corrected_value) : (c.corrected_value || '–')) + '</td>' +
          '<td><span class="tag" style="background:' + (REASON_COLORS[c.reason] || '#888') + '1f;color:' + (REASON_COLORS[c.reason] || '#555') + '">' + esc(reasonLabel(c.reason)) + '</span>' +
          (c.comment_text ? '<div class="small muted">' + esc(c.comment_text) + '</div>' : '') + '</td><td>' + esc(c.corrected_by) + '</td>' +
          '<td>' + (c.active ? '' : '<span class="badge badge--neutral">replaced</span>') + '</td></tr>';
      }).join('') + '</tbody></table>';
    el.querySelector('#log-pg').innerHTML = pager(d.total, d.page, d.size);
  }

  el.querySelector('#dt').addEventListener('change', e => {
    el.querySelector('#export').href = '/api/corrections/export' + qs({ docType: e.target.value });
    f.page = 0; loadQuality(); loadLog();
  });
  el.querySelector('#f-reason').addEventListener('change', e => { f.reason = e.target.value; f.page = 0; loadLog(); });
  let t;
  el.querySelector('#f-field').addEventListener('input', e => { clearTimeout(t); t = setTimeout(() => { f.field = e.target.value.trim(); f.page = 0; loadLog(); }, 300); });
  el.querySelector('#f-inactive').addEventListener('change', e => { f.includeInactive = e.target.checked; f.page = 0; loadLog(); });
  el.querySelector('#log-pg').addEventListener('click', e => { const b = e.target.closest('[data-page]'); if (b && !b.disabled) { f.page = Number(b.dataset.page); loadLog(); } });
  el.querySelector('#quality').addEventListener('click', e => {
    const a = e.target.closest('a[data-field]');
    if (!a) return;
    e.preventDefault();
    f.field = a.dataset.field; f.page = 0;
    el.querySelector('#f-field').value = f.field;
    loadLog().then(() => el.querySelector('#log').scrollIntoView({ behavior: 'smooth', block: 'start' }));
  });

  await Promise.all([loadTop(), loadQuality(), loadLog()]).catch(e => {
    el.querySelector('#top').innerHTML = '<div class="banner banner--danger">' + esc(e.message) + '</div>';
  });
  return {};
}
