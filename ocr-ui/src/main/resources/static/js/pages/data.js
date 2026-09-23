import { api, esc, icon, pageHead, empty, pager, qs, docTypeLabel, humanize, badge, num, pct, isSensitive, mask, skeletonRows, ago } from '../core.js';

export async function mount(el, ctx) {
  const types = await api('/api/data');
  let docType = ctx.params.docType || (types.find(t => t.documents) || types[0] || {}).docType;
  const f = { q: ctx.query.q || '', status: ctx.query.status || '', page: 0, reveal: false };

  el.innerHTML = pageHead('Data explorer',
    'The extracted data as a table: one row per document, one column per field. Values are final - reviewer corrections applied. ' +
    'Corrected cells are <span class="tag tag--green">green</span>, flagged ones <span class="tag" style="background:var(--warn-bg);color:var(--warn)">amber</span>.',
    '<a class="btn btn--secondary" id="export" href="#">' + icon('download') + 'Export CSV</a>') +
    '<div class="chips" id="types" style="margin-bottom:16px">' + types.map(t =>
      '<button class="chip" type="button" data-type="' + esc(t.docType) + '" aria-pressed="' + (t.docType === docType) + '">' + esc(docTypeLabel(t.docType)) +
      '<span class="chip__count">' + num(t.documents || 0) + '</span></button>').join('') + '</div>' +
    '<section class="card"><div class="filterbar"><div class="search-box">' + icon('zoom', 17) +
    '<input class="input" id="q" type="search" placeholder="Search any value in this table" value="' + esc(f.q) + '"></div>' +
    '<select class="select" id="status" aria-label="Status"><option value="">All statuses</option><option value="COMPLETED">Completed</option><option value="REVIEW">Needs review</option></select>' +
    '<label class="toggle"><input type="checkbox" id="reveal">Show account numbers</label>' +
    '<span class="spacer"></span><span class="small muted" id="hint"></span></div>' +
    '<div class="table-wrap" id="grid" style="max-height:calc(100vh - 330px)">' + skeletonRows(8) + '</div><div id="pg"></div></section>';

  const grid = el.querySelector('#grid');
  if (!docType) { grid.innerHTML = empty('No data yet', 'Upload documents first.', 'grid'); return {}; }
  el.querySelector('#status').value = f.status;
  let key = '';

  async function load(force) {
    el.querySelector('#export').href = '/api/data/' + encodeURIComponent(docType) + '/export';
    const d = await api('/api/data/' + encodeURIComponent(docType) + qs({ q: f.q, status: f.status, page: f.page, size: 50 }));
    const k = JSON.stringify(d) + f.reveal;
    if (!force && k === key) return;
    key = k;
    el.querySelector('#hint').innerHTML = 'SQL: <code>' + esc(d.sqlView) + '</code>' + (d.sqlViewExists ? '' : ' (created at the next job run)');
    if (!d.rows.length) {
      grid.innerHTML = empty('No documents', f.q ? 'Nothing matches “' + esc(f.q) + '”.' : 'No ' + esc(docTypeLabel(docType)) + ' documents yet.', 'grid');
      el.querySelector('#pg').innerHTML = '';
      return;
    }
    grid.innerHTML = '<table class="table datagrid"><thead><tr><th class="sticky">Document</th><th>Status</th>' +
      d.columns.map(c => '<th class="' + (c.configured ? '' : 'is-extra') + '" title="' + esc(c.configured ? 'Configured field' : 'Returned by the model, not configured in the doc type') + '">' + esc(humanize(c.name)) + '</th>').join('') +
      '<th>Confidence</th><th>Updated</th></tr></thead><tbody>' +
      d.rows.map(r => '<tr class="is-clickable" data-href="#/documents/' + r.doc_id + '"><td class="sticky"><b>#' + r.doc_id + '</b> <span class="muted">' + esc(r.file_name) + '</span></td>' +
        '<td>' + badge(r.status) + '</td>' +
        d.columns.map(c => cell(r.values[c.name] || findCi(r.values, c.name), c.name)).join('') +
        '<td>' + pct(r.doc_confidence) + '</td><td class="muted">' + esc(ago(r.updated_at)) + '</td></tr>').join('') + '</tbody></table>';
    el.querySelector('#pg').innerHTML = pager(d.total, d.page, d.size);
  }

  function cell(v, name) {
    if (!v || v.v == null || v.v === '') return '<td class="cell--empty">–</td>';
    const shown = isSensitive(name) && !f.reveal ? mask(v.v) : v.v;
    const cls = v.src === 'CORRECTED' ? 'cell--corrected' : v.st && v.st !== 'OK' ? 'cell--flagged' : '';
    return '<td class="' + cls + '" title="' + esc(v.src === 'CORRECTED' ? 'Corrected by a reviewer' : v.st !== 'OK' ? humanize(v.st) : '') + '">' + esc(shown) + '</td>';
  }
  function findCi(values, name) {
    const k = Object.keys(values).find(x => x.toLowerCase() === name.toLowerCase());
    return k ? values[k] : null;
  }

  el.querySelector('#types').addEventListener('click', e => {
    const b = e.target.closest('[data-type]');
    if (!b) return;
    docType = b.dataset.type; f.page = 0;
    el.querySelectorAll('#types .chip').forEach(c => c.setAttribute('aria-pressed', String(c === b)));
    history.replaceState(null, '', '#/data/' + encodeURIComponent(docType));
    load(true);
  });
  let t;
  el.querySelector('#q').addEventListener('input', e => { clearTimeout(t); t = setTimeout(() => { f.q = e.target.value.trim(); f.page = 0; load(true); }, 300); });
  el.querySelector('#status').addEventListener('change', e => { f.status = e.target.value; f.page = 0; load(true); });
  el.querySelector('#reveal').addEventListener('change', e => { f.reveal = e.target.checked; load(true); });
  el.querySelector('#pg').addEventListener('click', e => {
    const b = e.target.closest('[data-page]');
    if (b && !b.disabled) { f.page = Number(b.dataset.page); load(true); }
  });

  await load(true).catch(e => { grid.innerHTML = '<div class="banner banner--danger" style="margin:16px">' + esc(e.message) + '</div>'; });
  return { tick: () => load(false).catch(() => {}) };
}
