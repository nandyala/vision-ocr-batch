import { api, state, esc, icon, pageHead, badge, meter, ago, fmtDate, docTypeLabel, pager, empty, qs, skeletonRows, num, STATUS } from '../core.js';

const STATUS_FILTERS = [
  { id: '', label: 'All' },
  { id: 'REVIEW', label: 'Needs review' },
  { id: 'COMPLETED', label: 'Completed' },
  { id: 'NEW,CLASSIFIED,EXTRACTED', label: 'In progress' },
  { id: 'ERROR', label: 'Retrying' },
  { id: 'FAILED', label: 'Failed' }
];
const COLS = [
  { key: 'id', label: 'ID', sort: 'id' },
  { key: 'file_name', label: 'Document', sort: 'file_name' },
  { key: 'doc_type', label: 'Type', sort: 'doc_type' },
  { key: 'status', label: 'Status', sort: 'status' },
  { key: 'doc_confidence', label: 'Confidence', sort: 'doc_confidence' },
  { key: 'corrections', label: 'Corrections' },
  { key: 'created_at', label: 'Received', sort: 'created_at' },
  { key: 'updated_at', label: 'Last update', sort: 'updated_at' }
];

export async function mount(el, ctx) {
  const f = Object.assign({ status: '', docType: '', q: '', from: '', to: '', corrected: '', sort: 'id', dir: 'desc', page: 0 }, ctx.query);
  f.page = Number(f.page) || 0;
  const types = state.meta.docTypes || [];

  el.innerHTML = pageHead('Documents', 'Every document the pipeline has seen. Search by file name, document number or any extracted value (name, account, routing number…).',
    '<a class="btn btn--primary" href="#/upload">' + icon('upload') + 'Upload</a>') +
    '<section class="card"><div class="filterbar">' +
    '<div class="search-box">' + icon('zoom', 17) + '<input class="input" id="q" type="search" placeholder="Search file name, #id or any value" value="' + esc(f.q) + '"></div>' +
    '<select class="select" id="doctype" aria-label="Document type"><option value="">All types</option>' +
    types.map(t => '<option value="' + esc(t.docType) + '"' + (f.docType === t.docType ? ' selected' : '') + '>' + esc(docTypeLabel(t.docType)) + '</option>').join('') +
    '<option value="NONE"' + (f.docType === 'NONE' ? ' selected' : '') + '>Not classified</option></select>' +
    '<label class="field-label" style="flex-direction:row;align-items:center;gap:6px">From <input class="input input--sm" type="date" id="from" value="' + esc(f.from) + '"></label>' +
    '<label class="field-label" style="flex-direction:row;align-items:center;gap:6px">To <input class="input input--sm" type="date" id="to" value="' + esc(f.to) + '"></label>' +
    '<label class="toggle"><input type="checkbox" id="corrected"' + (f.corrected ? ' checked' : '') + '>Corrected only</label>' +
    '</div><div class="filterbar" style="padding-top:10px"><div class="chips" id="status">' +
    STATUS_FILTERS.map(s => '<button class="chip" type="button" data-status="' + s.id + '" aria-pressed="' + (f.status === s.id) + '">' + esc(s.label) + '</button>').join('') +
    '</div><span class="spacer"></span><span class="muted small" id="count"></span></div>' +
    '<div class="table-wrap" id="tbl">' + skeletonRows(8) + '</div><div id="pg"></div></section>';

  const tbl = el.querySelector('#tbl');
  let key = '';

  function sync() {
    const q = qs({ status: f.status, docType: f.docType, q: f.q, from: f.from, to: f.to, corrected: f.corrected ? 'true' : '', sort: f.sort !== 'id' ? f.sort : '', dir: f.dir !== 'desc' ? f.dir : '', page: f.page || '' });
    history.replaceState(null, '', '#/documents' + q);
  }

  async function load(force) {
    const data = await api('/api/documents' + qs({ status: f.status, docType: f.docType, q: f.q, from: f.from, to: f.to, corrected: f.corrected ? 'true' : '', sort: f.sort, dir: f.dir, page: f.page, size: 25 }));
    const k = JSON.stringify(data);
    if (!force && k === key) return;
    key = k;
    el.querySelector('#count').textContent = num(data.total) + ' document' + (data.total === 1 ? '' : 's');
    if (!data.items.length) {
      tbl.innerHTML = empty('No documents found', f.q || f.status || f.docType ? 'Try another search or clear the filters.' : 'Upload a document to get started.', 'docs');
      el.querySelector('#pg').innerHTML = '';
      return;
    }
    tbl.innerHTML = '<table class="table"><thead><tr>' + COLS.map(c => c.sort
      ? '<th class="sortable' + (f.sort === c.sort ? ' is-sorted' : '') + (c.key === 'doc_confidence' || c.key === 'corrections' ? '' : '') + '" data-sort="' + c.sort + '">' + esc(c.label) +
        '<span class="sort">' + (f.sort === c.sort ? (f.dir === 'asc' ? '▲' : '▼') : '▾') + '</span></th>'
      : '<th>' + esc(c.label) + '</th>').join('') + '</tr></thead><tbody>' +
      data.items.map(d => '<tr class="is-clickable" data-href="#/documents/' + d.id + '" tabindex="0">' +
        '<td class="muted">#' + d.id + '</td>' +
        '<td class="file"><div class="ellipsis" title="' + esc(d.file_name) + '">' + esc(d.file_name) + '</div></td>' +
        '<td>' + (d.doc_type ? '<span class="tag tag--green">' + esc(docTypeLabel(d.doc_type)) + '</span>' : '<span class="muted">–</span>') + '</td>' +
        '<td>' + badge(d.status) + '</td>' +
        '<td>' + meter(d.doc_confidence) + '</td>' +
        '<td>' + (d.corrections ? '<span class="tag">' + icon('edit', 13) + ' ' + d.corrections + '</span>' : '<span class="muted">–</span>') + '</td>' +
        '<td class="nowrap" title="' + esc(fmtDate(d.created_at, true)) + '">' + esc(fmtDate(d.created_at)) + '</td>' +
        '<td class="nowrap muted">' + esc(ago(d.updated_at)) + '</td></tr>').join('') + '</tbody></table>';
    el.querySelector('#pg').innerHTML = pager(data.total, data.page, data.size);
  }

  function reload() { f.page = 0; sync(); load(true).catch(e => { tbl.innerHTML = '<div class="banner banner--danger" style="margin:16px">' + esc(e.message) + '</div>'; }); }
  let t;
  el.querySelector('#q').addEventListener('input', e => { clearTimeout(t); t = setTimeout(() => { f.q = e.target.value.trim(); reload(); }, 300); });
  el.querySelector('#doctype').addEventListener('change', e => { f.docType = e.target.value; reload(); });
  el.querySelector('#from').addEventListener('change', e => { f.from = e.target.value; reload(); });
  el.querySelector('#to').addEventListener('change', e => { f.to = e.target.value; reload(); });
  el.querySelector('#corrected').addEventListener('change', e => { f.corrected = e.target.checked; reload(); });
  el.querySelector('#status').addEventListener('click', e => {
    const b = e.target.closest('[data-status]');
    if (!b) return;
    f.status = b.dataset.status;
    el.querySelectorAll('#status .chip').forEach(c => c.setAttribute('aria-pressed', String(c === b)));
    reload();
  });
  tbl.addEventListener('click', e => {
    const th = e.target.closest('th[data-sort]');
    if (!th) return;
    if (f.sort === th.dataset.sort) f.dir = f.dir === 'asc' ? 'desc' : 'asc'; else { f.sort = th.dataset.sort; f.dir = th.dataset.sort === 'file_name' ? 'asc' : 'desc'; }
    reload();
  });
  el.querySelector('#pg').addEventListener('click', e => {
    const b = e.target.closest('[data-page]');
    if (!b || b.disabled) return;
    f.page = Number(b.dataset.page); sync(); load(true).then(() => window.scrollTo({ top: 0, behavior: 'smooth' }));
  });

  await load(true).catch(e => { tbl.innerHTML = '<div class="banner banner--danger" style="margin:16px">' + esc(e.message) + '</div>'; });
  return { tick: () => load(false).catch(() => {}) };
}
