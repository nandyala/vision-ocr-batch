import {
  api, state, esc, icon, badge, ring, meter, pct, humanize, docTypeLabel, fmtDate, ago, duration, bytes, toast, dialog,
  ensureReviewer, reasonItems, reasonLabel, isSensitive, mask, abaValid, jsonHtml, PROCESSING, STAGES, REASONS, STATUS, empty
} from '../core.js';

export async function mount(el, ctx) {
  const id = Number(ctx.params.id);
  const inQueue = ctx.query.queue === '1';
  const S = { detail: null, key: '', tab: 'fields', editing: null, revealed: new Set(), page: 0, pages: 1, zoom: false, azure: null, queue: [] };

  el.innerHTML = '<div id="d-head"></div><div id="d-steps"></div><div id="d-banner"></div>' +
    '<div class="workspace"><section class="card viewer" id="d-viewer"></section><section class="card panel" id="d-panel">' +
    '<div class="skeleton" style="height:48px;margin:16px"></div>'.repeat(6) + '</section></div>';
  const $h = el.querySelector('#d-head'), $s = el.querySelector('#d-steps'), $b = el.querySelector('#d-banner');
  const $v = el.querySelector('#d-viewer'), $p = el.querySelector('#d-panel');

  // ---------------------------------------------------------------- data
  async function load(force) {
    const d = await api('/api/documents/' + id);
    const k = JSON.stringify(d);
    if (!force && k === S.key) return;
    const first = !S.detail;
    S.detail = d; S.key = k;
    if (first && ['tif', 'tiff'].includes(d.document.file_type) && d.document.file_available) {
      api('/api/documents/' + id + '/pages').then(r => { S.pages = r.pages || 1; renderViewer(); }).catch(() => {});
    }
    if (inQueue) S.queue = await api('/api/review-queue?limit=500').catch(() => []);
    renderHead(); renderSteps(); renderBanner();
    if (first) renderViewer();
    if (!S.editing || force) renderPanel();
  }

  // ---------------------------------------------------------------- header
  function renderHead() {
    const doc = S.detail.document;
    const qi = S.queue.findIndex(q => q.id === id);
    const prev = qi > 0 ? S.queue[qi - 1] : null, next = qi >= 0 && qi < S.queue.length - 1 ? S.queue[qi + 1] : null;
    $h.innerHTML = '<div class="crumbs">' + (inQueue ? '<a href="#/review">Review queue</a>' : '<a href="#/documents">Documents</a>') +
      ' <span>/</span> <span>#' + doc.id + '</span></div>' +
      '<div class="doc-head"><h1 class="doc-head__title" title="' + esc(doc.file_name) + '">' + esc(doc.file_name) + '</h1>' +
      '<div class="doc-head__meta">' + (doc.doc_type ? '<span class="tag tag--green" title="' + esc(S.detail.docTypeDescription || '') + '">' + esc(docTypeLabel(doc.doc_type)) + '</span>' : '') +
      badge(doc.status, PROCESSING.includes(doc.status) ? 'badge--pulse' : '') + ring(doc.doc_confidence) + '</div>' +
      (inQueue && qi >= 0 ? '<div class="doc-head__nav"><span class="queue-progress">' + (qi + 1) + ' of ' + S.queue.length + ' in queue</span>' +
        '<a class="btn btn--ghost btn--sm' + (prev ? '' : '" aria-disabled="true" style="pointer-events:none;opacity:.4') + '" href="' + (prev ? '#/documents/' + prev.id + '?queue=1' : '#') + '">' + icon('arrowL', 16) + 'Previous</a>' +
        '<a class="btn btn--ghost btn--sm' + (next ? '' : '" aria-disabled="true" style="pointer-events:none;opacity:.4') + '" href="' + (next ? '#/documents/' + next.id + '?queue=1' : '#') + '">Next' + icon('arrowR', 16) + '</a></div>' : '') +
      '</div>';
  }

  function renderSteps() {
    const doc = S.detail.document;
    const steps = ['Received', 'Classified', 'Extracted', 'Validated', 'Decision'];
    const failIdx = { INGEST: 0, CLASSIFY: 1, EXTRACT: 2, MAP: 3 }[doc.failed_stage];
    const reviewed = (S.detail.history || []).some(h => h.to_status === 'REVIEW');
    let done = 0, active = -1, warn = -1, error = -1;
    switch (doc.status) {
      case 'NEW': done = 1; active = 1; break;
      case 'CLASSIFIED': done = 2; active = 2; break;
      case 'EXTRACTED': done = 3; active = 3; break;
      case 'COMPLETED': done = 5; steps[4] = reviewed ? 'Approved' : 'Auto-approved'; break;
      case 'REVIEW': done = 4; warn = 4; steps[4] = 'Needs review'; break;
      case 'ERROR': done = failIdx == null ? 1 : failIdx; warn = done; steps[done] += ' (retrying)'; break;
      case 'FAILED': done = failIdx == null ? 1 : failIdx; error = done; steps[done] += ' (failed)'; break;
    }
    $s.innerHTML = '<ol class="stepper" aria-label="Processing progress">' + steps.map((s, i) => {
      let cls = 'step', mark = String(i + 1);
      if (i === error) { cls += ' is-error'; mark = '!'; }
      else if (i === warn) { cls += ' is-warn'; mark = '!'; }
      else if (i < done) { cls += ' is-done'; mark = '✓'; }
      else if (i === active) cls += ' is-active';
      return '<li class="' + cls + '"' + (i === active ? ' aria-current="step"' : '') + '><span class="step__dot">' + mark + '</span><span>' + esc(s) + '</span></li>';
    }).join('') + '</ol>';
  }

  function renderBanner() {
    const doc = S.detail.document, fields = S.detail.fields || [];
    let h = '';
    if (doc.status === 'REVIEW') {
      const items = reasonItems(doc.review_reasons, fields);
      const open = items.filter(i => !i.resolved).length;
      h = '<div class="banner banner--warn">' + icon('alert', 22) + '<div><div class="banner__title">' +
        (open ? open + ' item' + (open > 1 ? 's' : '') + ' to check before approval' : 'All flagged items handled - ready to approve') + '</div><ul>' +
        items.map(i => '<li>' + (i.resolved ? '<s>' + i.text + '</s> <span class="badge badge--success badge--plain">handled</span>' : i.text) + '</li>').join('') + '</ul></div></div>';
    } else if (doc.status === 'COMPLETED') {
      const reviewed = (S.detail.history || []).some(x => x.to_status === 'REVIEW');
      const nCorr = fields.filter(f => f.correction_reason && f.correction_reason !== 'CONFIRMED').length;
      const approval = (S.detail.history || []).slice().reverse().find(x => x.stage === 'REVIEW' && x.to_status === 'COMPLETED');
      h = '<div class="banner banner--ok">' + icon('check', 22) + '<div><div class="banner__title">' +
        (reviewed ? 'Approved' + (approval ? ' by ' + esc(approval.changed_by) + ' ' + esc(ago(approval.changed_at)) : ' after review') : 'Processed straight through - no human touch') + '</div>' +
        (reviewed ? (nCorr ? nCorr + ' field' + (nCorr > 1 ? 's were' : ' was') + ' corrected. Downstream systems receive the corrected values.' : 'Approved without changes.')
          : 'Every field passed the confidence thresholds and validation rules.') + '</div></div>';
    } else if (doc.status === 'ERROR') {
      h = '<div class="banner banner--info">' + icon('refresh', 22) + '<div><div class="banner__title">Temporary problem - retried automatically</div>Attempt ' +
        esc(doc.retry_count) + ' failed during ' + esc(STAGES[doc.failed_stage] || doc.failed_stage) + '. Next try ' + esc(fmtDate(doc.next_retry_at)) +
        '.<br><span class="small">' + esc(doc.last_error || '') + '</span></div></div>';
    } else if (doc.status === 'FAILED') {
      h = '<div class="banner banner--danger">' + icon('alert', 22) + '<div><div class="banner__title">Processing failed during ' +
        esc(STAGES[doc.failed_stage] || doc.failed_stage || 'processing') + '</div>' + esc(doc.last_error || 'See the timeline for details.') +
        '<br><span class="small">Fix the cause (e.g. model id, key, network), then use <b>Re-run</b> below.</span></div></div>';
    } else if (PROCESSING.includes(doc.status)) {
      h = '<div class="banner banner--info">' + '<span class="spinner spinner--sm" style="margin-top:3px"></span>' + '<div><div class="banner__title">Processing…</div>' +
        esc(STATUS[doc.status].hint) + '. This page updates by itself.</div></div>';
    }
    $b.innerHTML = h ? '<div style="margin-bottom:16px">' + h + '</div>' : '';
  }

  // ---------------------------------------------------------------- viewer
  function renderViewer() {
    const doc = S.detail.document;
    const base = '/api/documents/' + id + '/file';
    let bar, body;
    if (!doc.file_available) {
      bar = '<span>Original document</span>';
      body = empty('Preview not available', 'The original file is no longer in the input folder on this machine.', 'file');
    } else if (doc.file_type === 'pdf') {
      bar = '<span>' + icon('file', 16) + ' PDF · ' + esc(bytes(doc.file_size)) + '</span><span class="spacer"></span>' +
        '<a class="btn btn--ghost btn--sm" href="' + base + '" target="_blank" rel="noopener">' + icon('external', 15) + 'Open</a>';
      body = '<iframe class="viewer__frame" title="Original document" src="' + base + '#view=FitH"></iframe>';
    } else {
      const multi = S.pages > 1;
      bar = '<span>' + icon('file', 16) + ' ' + esc(String(doc.file_type).toUpperCase()) + ' · ' + esc(bytes(doc.file_size)) + (multi ? ' · page ' + (S.page + 1) + ' of ' + S.pages : '') + '</span>' +
        '<span class="spacer"></span>' +
        (multi ? '<button class="icon-btn" data-v="page" data-d="-1" aria-label="Previous page"' + (S.page === 0 ? ' disabled' : '') + '>' + icon('arrowL') + '</button>' +
          '<button class="icon-btn" data-v="page" data-d="1" aria-label="Next page"' + (S.page >= S.pages - 1 ? ' disabled' : '') + '>' + icon('arrowR') + '</button>' : '') +
        '<button class="btn btn--ghost btn--sm" data-v="zoom" type="button">' + icon('zoom', 15) + (S.zoom ? 'Fit' : 'Zoom') + '</button>';
      body = '<img class="viewer__img' + (S.zoom ? ' is-zoomed' : '') + '" data-v="zoom" style="cursor:' + (S.zoom ? 'zoom-out' : 'zoom-in') + '" alt="Page ' + (S.page + 1) + ' of the original document" src="' + base + '?page=' + S.page + '">';
    }
    $v.innerHTML = '<div class="viewer__bar">' + bar + '</div><div class="viewer__body">' + body + '</div>';
  }
  $v.addEventListener('click', e => {
    const b = e.target.closest('[data-v]');
    if (!b) return;
    if (b.dataset.v === 'zoom') S.zoom = !S.zoom;
    if (b.dataset.v === 'page') S.page = Math.max(0, Math.min(S.pages - 1, S.page + Number(b.dataset.d)));
    renderViewer();
  });

  // ---------------------------------------------------------------- panel
  function renderPanel() {
    const d = S.detail, doc = d.document, fields = d.fields || [];
    const scroll = window.scrollY;
    const tabs = [['fields', 'Extracted data', fields.length], ['timeline', 'Timeline', (d.history || []).length], ['azure', 'Azure results', (d.azureCalls || []).length], ['details', 'Details', null]];
    let body;
    if (S.tab === 'fields') body = PROCESSING.includes(doc.status) && !fields.length ? processingHtml(doc) : fieldsHtml(doc, fields);
    else if (S.tab === 'timeline') body = timelineHtml(d);
    else if (S.tab === 'azure') body = azureHtml(d);
    else body = detailsHtml(d);
    $p.innerHTML = '<div class="tabs" role="tablist">' + tabs.map(t => '<button class="tab" role="tab" type="button" data-tab="' + t[0] + '" aria-selected="' + (S.tab === t[0]) + '">' +
      esc(t[1]) + (t[2] != null ? '<span class="tab__count">' + t[2] + '</span>' : '') + '</button>').join('') + '</div>' +
      '<div class="panel__body">' + body + '</div>' + actionbarHtml(doc, fields);
    window.scrollTo(0, scroll);
    if (S.editing) {
      const inp = $p.querySelector('#edit-value');
      if (inp) { inp.focus(); inp.select(); hint(); }
    }
  }

  function processingHtml(doc) {
    const msg = { NEW: 'Identifying the document type…', CLASSIFIED: 'Reading the fields with the custom AI model…', EXTRACTED: 'Validating the extracted values…' };
    return '<div class="processing"><div class="spinner"></div><h3>' + esc(msg[doc.status] || 'Processing…') + '</h3>' +
      '<p class="muted">Usually 10–30 seconds. Results appear here automatically.</p>' +
      '<div style="max-width:440px;margin:24px auto 0">' + '<div class="skeleton" style="height:64px;margin-top:10px"></div>'.repeat(4) + '</div></div>';
  }

  function fieldsHtml(doc, fields) {
    if (!fields.length) return empty('No fields extracted', doc.status === 'FAILED' ? 'The document failed before extraction.' : 'The model returned no fields for this document.', 'docs');
    const conf = fields.filter(f => f.configured === true || f.configured === 1);
    const other = fields.filter(f => !(f.configured === true || f.configured === 1));
    const flagged = fields.filter(f => isIssue(f)).length;
    let h = '<div class="row small muted" style="margin:12px 4px 0">' + fields.length + ' fields · ' + (flagged ? '<span style="color:var(--warn);font-weight:600">' + flagged + ' flagged</span>' : 'none flagged') +
      ' · ' + fields.filter(f => f.correction_reason && f.correction_reason !== 'CONFIRMED').length + ' corrected</div>';
    if (conf.length) h += '<div class="section-label">Business fields</div><ul class="fields">' + conf.map(f => fieldHtml(doc, f)).join('') + '</ul>';
    if (other.length) h += '<div class="section-label">Other fields found by the model</div><ul class="fields">' + other.map(f => fieldHtml(doc, f)).join('') + '</ul>';
    return h;
  }

  function isIssue(f) { return !f.correction_reason && f.field_status && f.field_status !== 'OK'; }

  function fieldHtml(doc, f) {
    const corrected = !!f.correction_reason && f.correction_reason !== 'CONFIRMED';
    const confirmed = f.correction_reason === 'CONFIRMED';
    const value = f.correction_reason ? f.corrected_value : f.field_value;
    const issue = isIssue(f);
    const sensitive = isSensitive(f.field_name) && value;
    const shown = sensitive && !S.revealed.has(f.field_name) ? mask(value) : value;
    const mono = /account|routing|number|date|amount|zip|postal|phone/i.test(f.field_name);
    const statusText = { MISSING: 'Not found', LOW_CONFIDENCE: 'Low confidence', INVALID: 'Failed check' }[f.field_status];
    const editable = !PROCESSING.includes(doc.status);
    const editing = S.editing === f.field_name;
    let h = '<li class="fld' + (issue ? ' is-issue' : '') + (corrected ? ' is-corrected' : '') + (confirmed ? ' is-confirmed' : '') + (editing ? ' is-editing' : '') + '" data-field="' + esc(f.field_name) + '">' +
      '<div class="fld__top"><span class="fld__label">' + esc(humanize(f.field_name)) +
      (f.azure_field && f.azure_field !== f.field_name ? '<span class="fld__azure">' + esc(f.azure_field) + '</span>' : '') + '</span>' +
      (corrected ? '<span class="badge badge--success">Corrected</span>' : confirmed ? '<span class="badge badge--success badge--plain">' + icon('check', 13) + ' Confirmed</span>'
        : issue ? '<span class="badge badge--warning">' + esc(statusText || f.field_status) + '</span>' : '') + '</div>' +
      '<div class="fld__value' + (mono ? ' is-mono' : '') + (value == null || value === '' ? ' is-empty' : '') + '">' + (value == null || value === '' ? 'Empty' : esc(shown)) +
      (sensitive ? '<button class="icon-btn" type="button" data-a="reveal" title="' + (S.revealed.has(f.field_name) ? 'Hide' : 'Show') + ' value" aria-label="' +
        (S.revealed.has(f.field_name) ? 'Hide' : 'Show') + ' value">' + icon(S.revealed.has(f.field_name) ? 'eyeoff' : 'eye', 17) + '</button>' : '') + '</div>';
    if (f.correction_reason) {
      const orig = f.original_value == null || f.original_value === '' ? 'empty' : (sensitive && !S.revealed.has(f.field_name) ? mask(f.original_value) : f.original_value);
      h += '<div class="fld__was">' + (corrected ? 'Model read <s>' + esc(orig) + '</s> · ' + esc(reasonLabel(f.correction_reason)) : 'Model value confirmed as correct') +
        ' · ' + esc(f.corrected_by || '–') + ', ' + esc(ago(f.corrected_at)) + (f.correction_comment ? ' · “' + esc(f.correction_comment) + '”' : '') + '</div>';
    }
    h += '<div class="fld__foot">' + meter(f.confidence) + (issue && f.message ? '<span class="fld__msg">' + esc(humanize(f.message)) + '</span>' : '') +
      (f.correction_versions > 1 || (f.correction_versions > 0 && !f.correction_reason) ? '<button class="link-btn small" type="button" data-a="history">History (' + f.correction_versions + ')</button>' : '') +
      (editable && !editing ? '<span class="fld__actions">' +
        (f.correction_reason ? '<button class="btn btn--ghost btn--sm" type="button" data-a="revert" title="Use the model value again">' + icon('undo', 15) + 'Undo</button>' : '') +
        (issue && value ? '<button class="btn btn--ghost btn--sm" type="button" data-a="confirm" title="The value is right">' + icon('check', 15) + 'Confirm</button>' : '') +
        '<button class="btn btn--' + (issue ? 'primary' : 'secondary') + ' btn--sm" type="button" data-a="edit">' + icon('edit', 15) + (issue ? 'Fix' : 'Correct') + '</button></span>' : '') + '</div>';
    if (editing) h += editorHtml(f, value);
    return h + '</li>';
  }

  function editorHtml(f, value) {
    const def = f.field_status === 'MISSING' ? 'MISSING' : 'OCR_MISREAD';
    const long = String(value || '').length > 60;
    return '<form class="editor" data-a="save" autocomplete="off">' +
      (f.raw_value && f.raw_value !== f.field_value ? '<div class="small muted">OCR text on the page: <span class="mono">' + esc(f.raw_value) + '</span></div>' : '') +
      '<label class="field-label">Correct value' + (long ? '<textarea class="textarea" id="edit-value" name="value" maxlength="4000">' + esc(value || '') + '</textarea>'
        : '<input class="input' + (isSensitive(f.field_name) ? ' mono' : '') + '" id="edit-value" name="value" maxlength="4000" value="' + esc(value || '') + '">') + '</label>' +
      '<div class="field-label">Why was it wrong?<div class="reason-pills">' + REASONS.map(r =>
        '<label class="reason-pill" title="' + esc(r.help) + '"><input type="radio" name="reason" value="' + r.id + '"' + (r.id === def ? ' checked' : '') + '>' + esc(r.label) + '</label>').join('') + '</div></div>' +
      '<label class="field-label">Comment <span class="hint">optional - helps the next model training</span><input class="input" name="comment" maxlength="1000" placeholder="e.g. handwritten 7 read as 1"></label>' +
      '<div class="editor__hint" id="edit-hint" hidden></div>' +
      '<div class="editor__actions"><span class="small muted" style="margin-right:auto;align-self:center">Enter to save · Esc to cancel</span>' +
      '<button class="btn btn--ghost btn--sm" type="button" data-a="cancel">Cancel</button>' +
      '<button class="btn btn--primary btn--sm" type="submit">' + icon('check', 15) + 'Save correction</button></div></form>';
  }

  function hint() {
    const box = $p.querySelector('#edit-hint'), inp = $p.querySelector('#edit-value');
    if (!box || !inp) return;
    let msg = '';
    if (/routing|aba/i.test(S.editing) && inp.value && !abaValid(inp.value)) msg = 'Not a valid 9-digit US routing number (checksum fails). You can still save it.';
    box.textContent = msg; box.hidden = !msg;
  }

  function timelineHtml(d) {
    const h = (d.history || []).slice().reverse();
    if (!h.length) return empty('No history yet', '', 'clock');
    return '<ol class="timeline">' + h.map(e => {
      const tone = e.to_status === 'FAILED' ? 'danger' : (e.to_status === 'REVIEW' || e.to_status === 'ERROR') && e.from_status !== e.to_status ? 'warn'
        : e.from_status === e.to_status ? 'info' : '';
      const change = e.from_status && e.from_status !== e.to_status ? badge(e.from_status) + '<span class="muted">→</span>' + badge(e.to_status) : !e.from_status ? badge(e.to_status) : '';
      return '<li class="tl' + (tone ? ' tl--' + tone : '') + '"><div class="tl__head"><span class="tl__stage">' + esc(STAGES[e.stage] || humanize(e.stage)) + '</span>' + change +
        '<span class="tl__time" title="' + esc(fmtDate(e.changed_at, true)) + '">' + esc(fmtDate(e.changed_at)) + '</span></div>' +
        (e.note ? '<div class="tl__note">' + esc(e.note) + '</div>' : '') + '<div class="tl__by">' + icon('user', 12) + ' ' + esc(e.changed_by || 'system') + '</div></li>';
    }).join('') + '</ol>';
  }

  function azureHtml(d) {
    const calls = d.azureCalls || [];
    if (!calls.length) return empty('No Azure calls yet', 'Classification and extraction results appear here.', 'layers');
    let h = '<p class="small muted" style="margin:14px 4px">Every call to Azure Document Intelligence is kept (<code>ocr.doc_azure_result</code>). Re-validation reuses the stored result - no new Azure cost.</p>' +
      '<div class="table-wrap"><table class="table table--compact"><thead><tr><th>Call</th><th>Model</th><th>Confidence</th><th>Duration</th><th>When</th><th></th></tr></thead><tbody>' +
      calls.slice().reverse().map(c => '<tr><td>' + esc(c.operation === 'EXTRACT' ? 'Extraction' : 'Classification') + (c.is_current ? ' <span class="badge badge--success badge--plain">current</span>' : '') +
        '</td><td class="mono small">' + esc(c.model_id) + '</td><td>' + meter(c.doc_confidence) + '</td><td class="nowrap">' + esc(duration(c.duration_ms)) +
        '</td><td class="nowrap">' + esc(fmtDate(c.created_at)) + '</td><td class="nowrap right"><button class="btn btn--ghost btn--sm" data-a="azure" data-rid="' + c.id + '">' + icon('code', 15) + 'Fields</button>' +
        (c.has_full_json ? '<button class="btn btn--ghost btn--sm" data-a="azure-full" data-rid="' + c.id + '">Full</button>' : '') + '</td></tr>').join('') + '</tbody></table></div>';
    if (S.azure) h += '<div class="section-label">' + esc(S.azure.title) + '</div><pre class="code">' + jsonHtml(S.azure.json) + '</pre>';
    return h;
  }

  function detailsHtml(d) {
    const doc = d.document;
    const row = (k, v) => '<dt>' + esc(k) + '</dt><dd>' + (v == null || v === '' ? '<span class="muted">–</span>' : esc(v)) + '</dd>';
    let h = '<dl class="kv" style="margin-top:16px">' + row('Document ID', '#' + doc.id) + row('File', doc.file_name) + row('Size', bytes(doc.file_size)) +
      row('Document type', docTypeLabel(doc.doc_type)) + row('Classifier label', doc.classifier_label) +
      row('Classification confidence', doc.classify_confidence == null ? null : pct(doc.classify_confidence)) + row('Pages used', doc.pages) +
      row('Extraction model', doc.model_id) + row('Document confidence', doc.doc_confidence == null ? null : pct(doc.doc_confidence)) +
      row('Received', fmtDate(doc.created_at, true)) + row('Last update', fmtDate(doc.updated_at, true)) + row('Retry attempts', doc.retry_count) + '</dl>';
    if (doc.extracted_json) h += '<div class="section-label">Final values sent downstream (JSON)</div><pre class="code">' + jsonHtml(doc.extracted_json) + '</pre>' +
      '<p class="small muted">Stored in <code>ocr.doc_job.extracted_json</code> (model values). Reviewer corrections are applied on top in <code>ocr.v_doc_field_final</code>.</p>';
    const rr = d.reprocessRequests || [];
    if (rr.length) h += '<div class="section-label">Reprocess requests</div><div class="table-wrap"><table class="table table--compact"><thead><tr><th>From</th><th>By</th><th>When</th><th>State</th></tr></thead><tbody>' +
      rr.map(r => '<tr><td>' + esc(STAGES[r.from_stage] || r.from_stage) + '</td><td>' + esc(r.requested_by) + '</td><td class="nowrap">' + esc(fmtDate(r.requested_at)) +
        '</td><td>' + esc(r.state) + (r.message ? ' <span class="muted small">' + esc(r.message) + '</span>' : '') + '</td></tr>').join('') + '</tbody></table></div>';
    const errs = d.errors || [];
    if (errs.length) h += '<div class="section-label">Errors</div><ol class="timeline">' + errs.slice().reverse().map(e =>
      '<li class="tl tl--danger"><div class="tl__head"><span class="tl__stage">' + esc(STAGES[e.stage] || e.stage) + '</span><span class="tag">attempt ' + esc(e.attempt_no) + '</span>' +
      (e.http_status ? '<span class="tag">HTTP ' + esc(e.http_status) + '</span>' : '') + '<span class="badge badge--' + (e.retryable ? 'info">retryable' : 'danger">not retryable') + '</span>' +
      '<span class="tl__time">' + esc(fmtDate(e.created_at)) + '</span></div><div class="tl__note">' + esc(e.error_message) + '</div></li>').join('') + '</ol>';
    return h;
  }

  function actionbarHtml(doc, fields) {
    if (PROCESSING.includes(doc.status)) return '<div class="actionbar"><span class="actionbar__note">Actions are available when processing is finished.</span></div>';
    const nCorr = fields.filter(f => f.correction_reason && f.correction_reason !== 'CONFIRMED').length;
    let note, buttons = '';
    if (doc.status === 'REVIEW') {
      const open = reasonItems(doc.review_reasons, fields).filter(i => !i.resolved).length;
      note = (open ? '<b style="color:var(--warn)">' + open + ' open</b>' : '<b style="color:var(--g-600)">Ready to approve</b>') + (nCorr ? ' · ' + nCorr + ' corrected' : '');
      buttons = btn('reprocess', 'MAP', 'Re-validate', 'refresh', 'Run the validation rules again on the stored result (no Azure cost)') +
        btn('reprocess', 'EXTRACT', 'Re-extract', 'sparkle', 'Send the document to the AI model again') +
        '<button class="btn btn--primary" type="button" data-a="approve">' + icon('shield', 17) + 'Approve</button>';
    } else if (doc.status === 'COMPLETED') {
      note = nCorr ? nCorr + ' correction' + (nCorr > 1 ? 's' : '') + ' applied' : 'Corrections stay in place if the document is reprocessed';
      buttons = btn('reprocess', 'MAP', 'Re-validate', 'refresh', 'Run the validation rules again (no Azure cost)') +
        btn('reprocess', 'EXTRACT', 'Re-extract', 'sparkle', 'Send the document to the AI model again') +
        '<button class="btn btn--secondary" type="button" data-a="reopen">' + icon('review', 17) + 'Send back to review</button>';
    } else {
      note = doc.status === 'ERROR' ? 'Retried automatically - or run it again now' : 'Needs an operator';
      buttons = btn('reprocess', 'CLASSIFY', 'Re-run from start', 'refresh', 'Classify, extract and validate again') +
        '<button class="btn btn--primary" type="button" data-a="reprocess" data-stage="EXTRACT">' + icon('sparkle', 17) + 'Re-run extraction</button>';
    }
    return '<div class="actionbar"><span class="actionbar__note">' + note + '</span>' + buttons + '</div>';
  }
  function btn(a, stage, label, ic, title) {
    return '<button class="btn btn--ghost btn--sm" type="button" data-a="' + a + '" data-stage="' + stage + '" title="' + esc(title) + '">' + icon(ic, 15) + esc(label) + '</button>';
  }

  // ---------------------------------------------------------------- actions
  async function act(path, body, okMsg) {
    try {
      const d = await api('/api/documents/' + id + '/' + path, { method: 'POST', body: body || {} });
      S.detail = d; S.key = JSON.stringify(d); S.editing = null;
      renderHead(); renderSteps(); renderBanner(); renderPanel();
      if (okMsg) toast(okMsg, 'ok');
      return d;
    } catch (e) { toast(e.message, 'error'); return null; }
  }

  $p.addEventListener('click', async e => {
    const t = e.target.closest('[data-tab]');
    if (t) { S.tab = t.dataset.tab; S.editing = null; renderPanel(); return; }
    const a = e.target.closest('[data-a]');
    if (!a || a.tagName === 'FORM') return;
    const li = a.closest('[data-field]'), field = li && li.dataset.field;
    const f = field && S.detail.fields.find(x => x.field_name === field);
    switch (a.dataset.a) {
      case 'edit': S.editing = field; renderPanel(); break;
      case 'cancel': S.editing = null; renderPanel(); break;
      case 'reveal': S.revealed.has(field) ? S.revealed.delete(field) : S.revealed.add(field); renderPanel(); break;
      case 'confirm':
        if (!(await ensureReviewer())) return;
        act('corrections', { field, value: f.field_value, reason: 'CONFIRMED', comment: null }, humanize(field) + ' confirmed');
        break;
      case 'revert':
        if (!(await ensureReviewer())) return;
        act('corrections/revert', { field }, 'Correction undone - the model value applies again');
        break;
      case 'history': showHistory(field); break;
      case 'approve': approve(); break;
      case 'reopen': reopen(); break;
      case 'reprocess': reprocess(a.dataset.stage); break;
      case 'azure': case 'azure-full':
        try {
          const full = a.dataset.a === 'azure-full';
          const json = await api('/api/documents/' + id + '/azure/' + a.dataset.rid + (full ? '?full=true' : ''));
          S.azure = { title: (full ? 'Full Azure response' : 'Fields returned by Azure') + ' · call #' + a.dataset.rid, json };
          renderPanel();
        } catch (err) { toast(err.message, 'error'); }
        break;
    }
  });
  $p.addEventListener('submit', async e => {
    if (!e.target.matches('form[data-a="save"]')) return;
    e.preventDefault();
    if (!(await ensureReviewer())) return;
    const fd = new FormData(e.target);
    const field = S.editing, f = S.detail.fields.find(x => x.field_name === field);
    const value = String(fd.get('value') || '').trim();
    const before = f.correction_reason ? f.corrected_value : f.field_value;
    if (value === (before || '')) { toast('The value is unchanged. Change it, or use Confirm if the model value is right.'); return; }
    act('corrections', { field, value, reason: fd.get('reason'), comment: fd.get('comment') || null }, humanize(field) + ' corrected');
  });
  $p.addEventListener('input', e => { if (e.target.id === 'edit-value') hint(); });
  $p.addEventListener('keydown', e => {
    if (e.key === 'Escape' && S.editing) { S.editing = null; renderPanel(); }
  });

  async function showHistory(field) {
    try {
      const rows = await api('/api/documents/' + id + '/corrections?field=' + encodeURIComponent(field));
      const sens = isSensitive(field) && !S.revealed.has(field);
      await dialog({
        title: 'Correction history · ' + humanize(field), ok: false, wide: true,
        body: '<p class="small muted">Every change is kept in <code>ocr.doc_field_correction</code>. Only the active row is used; older rows stay for audit and training.</p>' +
          '<div class="table-wrap"><table class="table table--compact"><thead><tr><th>When</th><th>By</th><th>Model value</th><th>Corrected to</th><th>Reason</th><th>State</th></tr></thead><tbody>' +
          rows.map(r => '<tr><td class="nowrap">' + esc(fmtDate(r.corrected_at)) + '</td><td>' + esc(r.corrected_by) + '</td><td class="mono small">' + esc(sens ? mask(r.original_value) : (r.original_value || '–')) +
            '</td><td class="mono small">' + esc(sens ? mask(r.corrected_value) : (r.corrected_value || '–')) + '</td><td>' + esc(reasonLabel(r.reason)) + (r.comment_text ? '<div class="small muted">' + esc(r.comment_text) + '</div>' : '') +
            '</td><td>' + (r.active ? '<span class="badge badge--success">active</span>' : '<span class="badge badge--neutral">replaced</span>') + '</td></tr>').join('') + '</tbody></table></div>'
      });
    } catch (e) { toast(e.message, 'error'); }
  }

  async function approve() {
    if (!(await ensureReviewer())) return;
    const d = S.detail;
    const open = reasonItems(d.document.review_reasons, d.fields).filter(i => !i.resolved);
    const nCorr = d.fields.filter(f => f.correction_reason && f.correction_reason !== 'CONFIRMED').length;
    const r = await dialog({
      title: 'Approve this document?', ok: 'Approve',
      body: (open.length ? '<div class="banner banner--warn" style="margin-bottom:8px">' + icon('alert', 20) + '<div><div class="banner__title">Still open</div>Approving confirms the values shown for:<ul>' +
        open.map(i => '<li>' + i.text + '</li>').join('') + '</ul></div></div>'
        : '<p>All flagged items were handled' + (nCorr ? ' (' + nCorr + ' corrected)' : '') + '. Downstream systems will receive the values as shown.</p>') +
        '<label class="field-label">Note <span class="hint">optional</span><textarea class="textarea" name="note" maxlength="500" placeholder="e.g. verified against the signed original"></textarea></label>'
    });
    if (!r) return;
    const done = await act('approve', { note: r.note || null }, 'Document approved');
    if (done && inQueue) {
      const q = await api('/api/review-queue?limit=500').catch(() => []);
      const next = q.find(x => x.id !== id);
      if (next) { toast('Next document in the queue'); location.hash = '#/documents/' + next.id + '?queue=1'; }
      else { toast('Review queue is empty - well done!', 'ok'); location.hash = '#/review'; }
    }
  }

  async function reopen() {
    if (!(await ensureReviewer())) return;
    const r = await dialog({ title: 'Send back to review?', ok: 'Send to review',
      body: '<p>The document goes back into the review queue. Its values stay as they are until someone approves it again.</p>' +
        '<label class="field-label">Why? <span class="hint">shown to the reviewer</span><input class="input" name="note" maxlength="300" placeholder="e.g. spot check: account number looks wrong"></label>' });
    if (r) act('reopen', { note: r.note || null }, 'Sent back to review');
  }

  async function reprocess(stage) {
    if (!(await ensureReviewer())) return;
    const text = {
      MAP: 'Runs the validation rules again on the stored Azure result. <b>No Azure cost.</b> Useful after a rule change.',
      EXTRACT: 'Sends the document to the AI model again (the model configured now). Uses Azure capacity.',
      CLASSIFY: 'Starts again from classification: classify, extract and validate.'
    }[stage];
    const r = await dialog({ title: { MAP: 'Re-validate', EXTRACT: 'Re-extract', CLASSIFY: 'Re-run from start' }[stage] + ' this document?', ok: 'Start',
      body: '<p>' + text + '</p><p class="small muted">Reviewer corrections are kept and still override the new model values.</p>' +
        '<label class="field-label">Reason <span class="hint">optional</span><input class="input" name="reason" maxlength="300"></label>' });
    if (!r) return;
    if (await act('reprocess', { fromStage: stage, reason: r.reason || null }, 'Queued - processing starts now')) S.tab = 'fields';
  }

  document.addEventListener('job-finished', onJobFinished);
  function onJobFinished() { load(false).catch(() => {}); }

  try { await load(true); } catch (e) {
    el.innerHTML = '<div class="crumbs"><a href="#/documents">Documents</a></div>' + '<div class="card">' + empty('Document not found', esc(e.message), 'docs', '<a class="btn btn--secondary" href="#/documents">Back to documents</a>') + '</div>';
    return {};
  }
  return {
    tick: () => load(false).catch(() => {}),
    unmount: () => document.removeEventListener('job-finished', onJobFinished)
  };
}
