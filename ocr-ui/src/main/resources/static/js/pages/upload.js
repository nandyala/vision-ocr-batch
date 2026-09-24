import { api, state, esc, icon, pageHead, badge, pct, humanize, docTypeLabel, toast, load, save, ago, bytes } from '../core.js';

/** Uploads of this browser, newest first (kept across page changes and reloads). */
let uploads = [];
try { uploads = JSON.parse(load('uploads') || '[]'); } catch (e) { uploads = []; }
function persist() { save('uploads', JSON.stringify(uploads.slice(0, 30))); }

export async function mount(el) {
  const types = (state.meta.docTypes || []).filter(t => t.enabled);
  const exts = state.meta.extensions || [];
  let docType = load('uploadDocType') || 'AUTO';
  if (docType !== 'AUTO' && !types.some(t => t.docType === docType)) docType = 'AUTO';
  const autoHint = state.meta.classifier ? 'The AI classifier decides the type'
    : 'No classifier configured: treated as ' + humanize(state.meta.defaultDocType || '');

  el.innerHTML = pageHead('Upload documents',
    'Drop scanned PDFs or images. Each file is classified, read by the custom Azure model and validated. Results appear below within seconds.',
    '<a class="btn btn--ghost" href="#/documents">' + icon('docs') + 'All documents</a>') +
    '<div class="grid grid--1-2">' +
    '<section class="card"><div class="card__head"><h2>1. Document type</h2></div><div class="card__body">' +
    '<div class="type-picker" style="grid-template-columns:1fr" role="radiogroup" aria-label="Document type">' +
    option('AUTO', 'Detect automatically', autoHint, docType) +
    types.map(t => option(t.docType, docTypeLabel(t.docType), t.description || t.docType, docType)).join('') +
    '</div><p class="muted small" style="margin:14px 2px 0">Choosing a type stores the file in <code>input/&lt;TYPE&gt;/</code>, which skips classification.</p></div></section>' +
    '<section class="card"><div class="card__head"><h2>2. Files</h2><span class="muted small">' + esc(exts.join(', ').toUpperCase()) + ' · up to ' + esc(state.meta.maxFileMb) + ' MB each</span></div><div class="card__body">' +
    '<label class="dropzone" id="dz" tabindex="0">' +
    '<input type="file" id="file" multiple hidden accept="' + esc(exts.map(e => '.' + e).join(',')) + '">' +
    '<span class="dropzone__icon">' + icon('upload', 34) + '</span>' +
    '<span class="dropzone__main">Drag &amp; drop scanned documents</span>' +
    '<span class="dropzone__sub">or <u>browse your computer</u> · several files at once are fine</span>' +
    '<span class="upload-progress" id="progress" hidden style="width:min(420px,90%);margin-top:10px"><span class="bar-row__track" style="display:block"><span class="bar-row__fill" style="width:0"></span></span>' +
    '<span class="muted small" id="progress-text"></span></span>' +
    '</label></div></section></div>' +
    '<section class="card" style="margin-top:20px"><div class="card__head"><h2>Your uploads</h2><span class="muted small" id="up-summary"></span>' +
    '<button class="btn btn--ghost btn--sm" type="button" id="clear" style="margin-left:auto">Clear list</button></div>' +
    '<ul class="uploads" id="list"></ul></section>';

  const list = el.querySelector('#list');
  const dz = el.querySelector('#dz'), input = el.querySelector('#file');

  el.querySelectorAll('input[name="doctype"]').forEach(r => r.addEventListener('change', () => { docType = r.value; save('uploadDocType', docType); }));
  input.addEventListener('change', () => { send(Array.from(input.files)); input.value = ''; });
  dz.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); } });
  ['dragenter', 'dragover'].forEach(t => dz.addEventListener(t, e => { e.preventDefault(); dz.classList.add('is-over'); }));
  ['dragleave', 'drop'].forEach(t => dz.addEventListener(t, e => { e.preventDefault(); dz.classList.remove('is-over'); }));
  dz.addEventListener('drop', e => send(Array.from(e.dataTransfer.files)));
  el.querySelector('#clear').addEventListener('click', () => { uploads = []; persist(); renderList(); });

  function send(files) {
    if (!files.length) return;
    const bad = files.filter(f => !exts.includes((f.name.split('.').pop() || '').toLowerCase()));
    if (bad.length) toast('Skipped ' + bad.map(f => f.name).join(', ') + ': unsupported type', 'error');
    files = files.filter(f => !bad.includes(f));
    if (!files.length) return;
    const fd = new FormData();
    files.forEach(f => fd.append('files', f, f.name));
    fd.append('docType', docType);
    const prog = el.querySelector('#progress'), fill = prog.querySelector('.bar-row__fill'), txt = el.querySelector('#progress-text');
    prog.hidden = false; fill.style.width = '0'; txt.textContent = 'Uploading ' + files.length + ' file' + (files.length > 1 ? 's' : '') + '…';
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/uploads');
    xhr.setRequestHeader('X-User', encodeURIComponent(state.user || ''));
    xhr.upload.onprogress = e => { if (e.lengthComputable) fill.style.width = Math.round(e.loaded * 100 / e.total) + '%'; };
    xhr.onerror = () => { prog.hidden = true; toast('Upload failed: network error', 'error'); };
    xhr.onload = () => {
      prog.hidden = true;
      let data = {};
      try { data = JSON.parse(xhr.responseText); } catch (e) { /* ignore */ }
      if (xhr.status >= 400) { toast(data.error || 'Upload failed', 'error'); return; }
      const now = new Date().toISOString();
      (data.files || []).forEach((r, i) => {
        const f = files.find(x => x.name === r.originalName) || files[i] || {};
        if (r.error) { toast(r.error, 'error'); return; }
        uploads = uploads.filter(u => u.hash !== r.hash);
        uploads.unshift({ name: r.originalName, hash: r.hash, size: f.size, at: now, docType, existing: !!r.existing, docId: r.docId || null, status: r.status || null });
      });
      persist();
      renderList();
      const n = (data.files || []).filter(r => !r.error && !r.existing).length;
      if (n) toast(n + ' file' + (n > 1 ? 's' : '') + ' uploaded. Processing started.', 'ok');
      if ((data.files || []).some(r => r.existing)) toast('Some files were already processed before - showing the existing result.');
      poll();
    };
    xhr.send(fd);
  }

  function renderList() {
    if (!uploads.length) {
      list.innerHTML = '<li class="empty"><p>Nothing uploaded from this browser yet.</p></li>';
      el.querySelector('#up-summary').textContent = '';
      return;
    }
    const done = uploads.filter(u => ['COMPLETED', 'REVIEW', 'FAILED'].includes(u.status)).length;
    el.querySelector('#up-summary').textContent = done + ' of ' + uploads.length + ' finished';
    list.innerHTML = uploads.map(item).join('');
  }

  /**
   * Refreshes every stored upload from the server by content hash - also finished ones, so the list never points at
   * documents that no longer exist (e.g. after the demo data was reset, when document ids start again at 1).
   */
  async function poll() {
    if (!uploads.length) { renderList(); return; }
    try {
      const rows = await api('/api/uploads/status', { method: 'POST', body: { hashes: uploads.map(u => u.hash) } });
      const byHash = new Map(rows.map(r => [r.file_hash, r]));
      let changed = false;
      uploads = uploads.filter(u => {
        if (byHash.has(u.hash)) return true;
        // not (yet) known to the server: keep fresh uploads that the job has not picked up yet
        const keep = !u.docId && Date.now() - new Date(u.at).getTime() < 15 * 60 * 1000;
        if (!keep) changed = true;
        return keep;
      });
      uploads.forEach(u => {
        const r = byHash.get(u.hash);
        if (!r) return;
        if (u.status !== r.status || u.docId !== r.id || u.confidence !== r.doc_confidence) {
          const finishedNow = !['COMPLETED', 'REVIEW', 'FAILED'].includes(u.status) && ['COMPLETED', 'REVIEW', 'FAILED'].includes(r.status);
          Object.assign(u, { status: r.status, docId: r.id, docType: r.doc_type || u.docType, confidence: r.doc_confidence });
          changed = true;
          if (finishedNow && reconciled) {
            const msg = r.status === 'COMPLETED' ? u.name + ' completed automatically' : r.status === 'REVIEW' ? u.name + ' needs a quick review' : u.name + ' failed';
            toast(msg, r.status === 'FAILED' ? 'error' : 'ok', { href: '#/documents/' + r.id, text: 'Open' });
          }
        }
      });
      if (changed) persist();
      if (changed || !reconciled) { reconciled = true; renderList(); }
    } catch (e) { /* retry next tick */ }
  }

  let reconciled = false;   // the list is shown only after it was checked against the server
  list.innerHTML = '<li><div class="skeleton" style="height:56px;margin:16px 20px"></div></li>';
  poll();
  return { tick: poll };
}

function option(value, title, hint, current) {
  return '<label class="type-option"><input type="radio" name="doctype" value="' + esc(value) + '"' + (value === current ? ' checked' : '') + '>' +
    '<b>' + esc(title) + '</b><span>' + esc(hint) + '</span></label>';
}

const STEPS = ['Uploaded', 'Classified', 'Extracted', 'Validated', 'Result'];
function item(u) {
  const st = u.status;
  let done = 1, active = 1, warn = -1, error = -1;
  switch (st) {
    case null: case undefined: case 'NEW': done = 1; active = 1; break;
    case 'CLASSIFIED': done = 2; active = 2; break;
    case 'EXTRACTED': done = 3; active = 3; break;
    case 'COMPLETED': done = 5; active = -1; break;
    case 'REVIEW': done = 4; active = -1; warn = 4; break;
    case 'ERROR': done = 1; active = -1; warn = 1; break;
    case 'FAILED': done = 1; active = -1; error = 1; break;
  }
  const bars = STEPS.map((s, i) => '<span class="mini-step' + (i === error ? ' is-error' : i === warn ? ' is-warn' : i < done ? ' is-done' : i === active ? ' is-active' : '') + '"></span>').join('');
  const ext = (u.name.split('.').pop() || '').toUpperCase().slice(0, 4);
  let right;
  if (st === 'COMPLETED') right = badge('COMPLETED') + ' <a class="btn btn--secondary btn--sm" href="#/documents/' + u.docId + '">View results</a>';
  else if (st === 'REVIEW') right = badge('REVIEW') + ' <a class="btn btn--primary btn--sm" href="#/documents/' + u.docId + '">Review now</a>';
  else if (st === 'FAILED') right = badge('FAILED') + ' <a class="btn btn--danger btn--sm" href="#/documents/' + u.docId + '">Details</a>';
  else if (st === 'ERROR') right = badge('ERROR') + (u.docId ? ' <a class="btn btn--ghost btn--sm" href="#/documents/' + u.docId + '">Details</a>' : '');
  else right = '<span class="row small muted"><span class="spinner spinner--sm"></span>' + esc(st ? ({ NEW: 'Classifying…', CLASSIFIED: 'Extracting fields…', EXTRACTED: 'Validating…' })[st] : 'Waiting for the pipeline…') + '</span>' +
    (u.docId ? ' <a class="btn btn--ghost btn--sm" href="#/documents/' + u.docId + '">Open</a>' : '');
  return '<li class="upload-item fade-in"><span class="upload-item__icon">' + esc(ext) + '</span><div style="min-width:0">' +
    '<div class="upload-item__name ellipsis">' + esc(u.name) + '</div>' +
    '<div class="upload-item__meta">' + esc(u.docType && u.docType !== 'AUTO' ? docTypeLabel(u.docType) : 'Auto-detect') + (u.size ? ' · ' + bytes(u.size) : '') +
    ' · ' + esc(ago(u.at, true)) + (u.confidence != null ? ' · confidence ' + pct(u.confidence) : '') + (u.docId ? ' · #' + u.docId : '') + (u.existing ? ' · already processed earlier' : '') + '</div>' +
    '<div class="mini-steps">' + bars + '</div><div class="mini-labels">' + STEPS.map(s => '<span>' + s + '</span>').join('') + '</div></div>' +
    '<div class="row" style="justify-content:flex-end">' + right + '</div></li>';
}
