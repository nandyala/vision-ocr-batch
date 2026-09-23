import { api, state, esc, icon, pageHead, toast, ensureReviewer, humanize } from '../core.js';

const SUGGEST = {
  date: { normalizers: ['norm.whitespace', 'norm.date'], validators: ['val.isoDate'] },
  number: { normalizers: ['norm.amount'], validators: [] },
  currency: { normalizers: ['norm.amount'], validators: [] },
  integer: { normalizers: ['norm.digits'], validators: [] },
  signature: { normalizers: ['norm.signature'], validators: ['val.signed'] },
  selectionMark: { normalizers: [], validators: [] },
  string: { normalizers: ['norm.whitespace'], validators: [] }
};

export async function mount(el, ctx) {
  const mode = ctx.params.mode || 'new';            // new | edit | copy
  const catalog = await api('/api/config/catalog');
  let spec = {
    docType: '', description: '', enabled: true, modelId: '', classifierLabels: [], minClassifyConfidence: 0.8,
    minDocumentConfidence: 0.6, includeUnmappedFields: true, defaultMinConfidence: 0, fields: []
  };
  const dropped = [];
  if (ctx.params.docType) {
    const t = await api('/api/config/doc-types/' + encodeURIComponent(ctx.params.docType));
    spec = fromDescribe(t, dropped);
    if (mode === 'copy') { spec.docType = t.docType + '_V2'; spec.classifierLabels = []; spec.description = (t.description || '') + ' (copy)'; }
  }
  let step = 0, xml = '', models = null;

  const title = mode === 'edit' ? 'Edit ' + spec.docType : mode === 'copy' ? 'New document type (copy of ' + ctx.params.docType + ')' : 'New document type';
  el.innerHTML = pageHead(title,
    'Point the pipeline at a trained Azure model and decide which fields matter, how they are cleaned up and when a document needs a human. ' +
    'Saved as an XML file in the <code>doctypes</code> folder and active immediately.', '',
    '<a href="#/config">Configuration</a> <span>/</span> <span>Document types</span>') +
    (dropped.length ? '<div class="banner banner--info" style="margin-bottom:16px">' + icon('info', 20) + '<div>Not copied (only available in the built-in XML): ' + esc(dropped.join(', ')) + '</div></div>' : '') +
    '<div class="wizard-steps" id="steps"></div><div id="body"></div>' +
    '<div class="card actionbar" style="margin-top:20px;border-radius:var(--radius)"><span class="actionbar__note" id="note"></span>' +
    '<button class="btn btn--ghost" id="back" type="button">' + icon('arrowL', 16) + 'Back</button>' +
    '<button class="btn btn--primary" id="next" type="button">Next' + icon('arrowR', 16) + '</button></div>';
  const body = el.querySelector('#body');

  const STEPS = ['Basics', 'Fields & rules', 'Review & save'];
  function renderSteps() {
    el.querySelector('#steps').innerHTML = STEPS.map((s, i) => '<div class="wizard-step' + (i === step ? ' is-active' : '') + '" data-step="' + i + '">Step ' + (i + 1) + '<b>' + s + '</b></div>').join('');
    el.querySelector('#back').disabled = step === 0;
    el.querySelector('#next').innerHTML = step === 2 ? icon('check', 16) + (mode === 'edit' ? 'Save changes' : 'Create document type') : 'Next' + icon('arrowR', 16);
    el.querySelector('#note').textContent = step === 1 ? spec.fields.length + ' field' + (spec.fields.length === 1 ? '' : 's') + ' with rules' : '';
  }

  function render() {
    renderSteps();
    if (step === 0) body.innerHTML = basicsHtml();
    else if (step === 1) body.innerHTML = fieldsHtml();
    else body.innerHTML = reviewHtml();
  }

  // ---------------------------------------------------------------- step 1
  function basicsHtml() {
    return '<section class="card fade-in"><div class="card__body"><div class="form-grid">' +
      '<label class="field-label">Name <span class="hint">capital letters, digits, _ (e.g. DISPUTE_FORM). Also the input sub-folder name.</span>' +
      '<input class="input mono" data-k="docType" value="' + esc(spec.docType) + '"' + (mode === 'edit' ? ' disabled' : '') + ' maxlength="50" placeholder="DISPUTE_FORM"></label>' +
      '<label class="field-label">Description<input class="input" data-k="description" value="' + esc(spec.description) + '" maxlength="200" placeholder="Card dispute form"></label>' +
      '<label class="field-label">Azure model id <span class="hint">the custom extraction model trained in Document Intelligence Studio</span>' +
      '<span class="row" style="flex-wrap:nowrap"><input class="input mono" data-k="modelId" list="models" value="' + esc(spec.modelId) + '" maxlength="64" placeholder="dispute-neural-v1">' +
      '<button class="btn btn--secondary btn--sm" type="button" id="browse">' + icon('layers', 15) + 'Browse</button></span><datalist id="models"></datalist><span class="hint" id="models-msg"></span></label>' +
      '<label class="field-label">Classifier labels <span class="hint">class names in the Azure classifier, comma separated (optional)</span>' +
      '<input class="input mono" data-k="classifierLabels" value="' + esc(spec.classifierLabels.join(', ')) + '" placeholder="dispute_form"></label>' +
      '<label class="field-label">Min. classification confidence (%)<input class="input" type="number" min="0" max="100" data-k="minClassifyConfidence" data-pct value="' + Math.round(spec.minClassifyConfidence * 100) + '"></label>' +
      '<label class="field-label">Min. document confidence (%) <span class="hint">below this the whole document goes to review</span><input class="input" type="number" min="0" max="100" data-k="minDocumentConfidence" data-pct value="' + Math.round(spec.minDocumentConfidence * 100) + '"></label>' +
      '<label class="field-label">Default field confidence (%) <span class="hint">for fields without their own rule; 0 = no check</span><input class="input" type="number" min="0" max="100" data-k="defaultMinConfidence" data-pct value="' + Math.round(spec.defaultMinConfidence * 100) + '"></label>' +
      '<div class="field-label">Options<label class="toggle"><input type="checkbox" data-k="enabled"' + (spec.enabled ? ' checked' : '') + '>Enabled</label>' +
      '<label class="toggle"><input type="checkbox" data-k="includeUnmappedFields"' + (spec.includeUnmappedFields ? ' checked' : '') + '>Also keep fields without rules</label></div>' +
      '</div></div></section>';
  }

  // ---------------------------------------------------------------- step 2
  function fieldsHtml() {
    const multi = (list, sel, i, k) => '<div class="multi">' + list.map(c => '<label title="' + esc(c.id) + '"><input type="checkbox" data-i="' + i + '" data-list="' + k + '" value="' + esc(c.id) + '"' +
      (sel.includes(c.id) ? ' checked' : '') + '>' + esc(c.label) + '</label>').join('') + '</div>';
    return '<section class="card fade-in"><div class="card__head"><h2>Fields with rules</h2>' +
      '<span class="muted small">Fields not listed are still stored when “keep fields without rules” is on.</span><span class="spacer"></span>' +
      '<button class="btn btn--secondary btn--sm" type="button" id="import">' + icon('download', 15) + 'Import from Azure model</button>' +
      '<button class="btn btn--primary btn--sm" type="button" id="add">' + icon('plus', 15) + 'Add field</button></div>' +
      (spec.fields.length ? '<div class="table-wrap"><table class="designer-fields"><thead><tr><th style="width:17%">Azure field</th><th style="width:14%">Stored as</th><th>Req.</th><th style="width:80px">Min. conf. %</th><th>Clean-up</th><th>Checks</th><th></th></tr></thead><tbody>' +
        spec.fields.map((f, i) => '<tr><td><input class="input mono" data-i="' + i + '" data-f="azureField" value="' + esc(f.azureField) + '" placeholder="CustomerName"></td>' +
          '<td><input class="input mono" data-i="' + i + '" data-f="canonicalField" value="' + esc(f.canonicalField || '') + '" placeholder="' + esc(camel(f.azureField) || 'auto') + '"></td>' +
          '<td style="text-align:center"><input type="checkbox" data-i="' + i + '" data-f="required"' + (f.required ? ' checked' : '') + ' style="accent-color:var(--g-600);width:18px;height:18px;margin-top:8px"></td>' +
          '<td><input class="input" type="number" min="0" max="100" data-i="' + i + '" data-f="minConfidence" value="' + Math.round((f.minConfidence || 0) * 100) + '"></td>' +
          '<td>' + multi(catalog.normalizers, f.normalizers, i, 'normalizers') + '</td>' +
          '<td>' + multi(catalog.validators, f.validators, i, 'validators') +
          '<div class="row" style="margin-top:6px;gap:6px;flex-wrap:nowrap"><input class="input mono" data-i="' + i + '" data-f="regex" value="' + esc(f.regex || '') + '" placeholder="pattern, e.g. \\d{4,17}" title="Value must match this regular expression">' +
          '<input class="input" data-i="' + i + '" data-f="allowedValues" value="' + esc((f.allowedValues || []).join(', ')) + '" placeholder="allowed values, comma separated"></div></td>' +
          '<td><button class="icon-btn" type="button" data-remove="' + i + '" aria-label="Remove field">' + icon('x') + '</button></td></tr>').join('') +
        '</tbody></table></div>' : '<div class="empty"><p>No field rules yet. <b>Import from Azure model</b> reads the field list of the trained model.</p></div>') + '</section>';
  }

  // ---------------------------------------------------------------- step 3
  function reviewHtml() {
    return '<div class="grid grid--1-2 fade-in"><section class="card"><div class="card__head"><h2>Summary</h2></div><div class="card__body"><dl class="kv">' +
      '<dt>Name</dt><dd class="mono">' + esc(spec.docType) + '</dd><dt>Model</dt><dd class="mono">' + esc(spec.modelId) + '</dd>' +
      '<dt>Fields with rules</dt><dd>' + spec.fields.length + ' (' + spec.fields.filter(f => f.required).length + ' required)</dd>' +
      '<dt>Upload folder</dt><dd class="mono small">input/' + esc(spec.docType) + '/</dd><dt>SQL view</dt><dd class="mono small">ocr.v_doc_' + esc(spec.docType.toLowerCase()) + '</dd></dl>' +
      '<div class="banner banner--info" style="margin-top:16px">' + icon('info', 20) + '<div>Active in this app right away (upload with this type). The batch job on the server picks it up at its next start from the <code>doctypes</code> folder.</div></div></div></section>' +
      '<section class="card"><div class="card__head"><h2>Generated XML</h2><span class="muted small">same format as the built-in doc types</span></div><div class="card__body">' +
      (xml ? '<pre class="code">' + esc(xml) + '</pre>' : '<div class="skeleton" style="height:320px"></div>') + '</div></section></div>';
  }

  // ---------------------------------------------------------------- events
  body.addEventListener('input', onInput);
  body.addEventListener('change', onInput);
  function onInput(e) {
    const t = e.target;
    if (t.dataset.k) {
      const k = t.dataset.k;
      if (t.type === 'checkbox') spec[k] = t.checked;
      else if (t.dataset.pct !== undefined) spec[k] = Math.max(0, Math.min(100, Number(t.value) || 0)) / 100;
      else if (k === 'classifierLabels') spec[k] = t.value.split(',').map(s => s.trim()).filter(Boolean);
      else if (k === 'docType') { spec[k] = t.value.toUpperCase().replace(/[^A-Z0-9_]/g, '_'); if (t.value !== spec[k]) t.value = spec[k]; }
      else spec[k] = t.value;
    } else if (t.dataset.i !== undefined) {
      const f = spec.fields[Number(t.dataset.i)];
      if (t.dataset.list) {
        const list = f[t.dataset.list];
        const idx = list.indexOf(t.value);
        if (t.checked && idx < 0) list.push(t.value); else if (!t.checked && idx >= 0) list.splice(idx, 1);
      } else if (t.dataset.f === 'required') f.required = t.checked;
      else if (t.dataset.f === 'minConfidence') f.minConfidence = Math.max(0, Math.min(100, Number(t.value) || 0)) / 100;
      else if (t.dataset.f === 'allowedValues') f.allowedValues = t.value.split(',').map(s => s.trim()).filter(Boolean);
      else {
        f[t.dataset.f] = t.value;
        if (t.dataset.f === 'azureField') { const c = t.closest('tr').querySelector('[data-f="canonicalField"]'); if (c) c.placeholder = camel(t.value) || 'auto'; }
      }
    }
  }
  body.addEventListener('click', async e => {
    if (e.target.closest('#add')) { spec.fields.push(newField('')); render(); body.querySelector('tbody tr:last-child input').focus(); }
    const rm = e.target.closest('[data-remove]');
    if (rm) { spec.fields.splice(Number(rm.dataset.remove), 1); render(); }
    if (e.target.closest('#import')) importFields();
    if (e.target.closest('#browse')) browseModels();
  });
  el.querySelector('#steps').addEventListener('click', e => { const s = e.target.closest('[data-step]'); if (s) goStep(Number(s.dataset.step)); });
  el.querySelector('#back').addEventListener('click', () => goStep(step - 1));
  el.querySelector('#next').addEventListener('click', () => step < 2 ? goStep(step + 1) : saveIt());

  async function goStep(n) {
    if (n < 0 || n > 2) return;
    if (n > 0 && (!spec.docType || !spec.modelId)) { toast('Enter a name and the Azure model id first', 'error'); step = 0; render(); return; }
    step = n;
    if (step === 2) { xml = ''; render(); await preview(); } else render();
  }

  async function preview() {
    try {
      xml = (await api('/api/config/doc-types/preview', { method: 'POST', body: clean() })).xml;
      if (step === 2) body.innerHTML = reviewHtml();
    } catch (e) {
      toast(e.message, 'error');
      body.innerHTML = '<div class="banner banner--danger">' + icon('alert', 22) + '<div><div class="banner__title">Please fix this first</div>' + esc(e.message) + '</div></div>';
    }
  }

  async function saveIt() {
    if (!(await ensureReviewer())) return;
    const btn = el.querySelector('#next');
    btn.disabled = true;
    try {
      const saved = await api('/api/config/doc-types' + (mode === 'edit' ? '/' + encodeURIComponent(spec.docType) : ''), { method: mode === 'edit' ? 'PUT' : 'POST', body: clean() });
      toast(saved.docType + (mode === 'edit' ? ' updated' : ' created') + ' - ready for uploads', 'ok', { href: '#/upload', text: 'Upload' });
      try { state.meta = await api('/api/meta'); } catch (e) { /* ignore */ }
      location.hash = '#/config/doc-types/' + encodeURIComponent(saved.docType);
    } catch (e) {
      toast(e.message, 'error');
      btn.disabled = false;
    }
  }

  async function browseModels() {
    const msg = body.querySelector('#models-msg');
    msg.textContent = 'Loading models from Azure…';
    try {
      models = models || await api('/api/config/models');
      const ids = Object.keys(models);
      body.querySelector('#models').innerHTML = ids.map(id => '<option value="' + esc(id) + '">' + esc(models[id] || '') + '</option>').join('');
      msg.textContent = ids.length ? ids.length + ' custom model(s) found - pick one from the list' : 'No custom models on this resource';
      body.querySelector('[data-k="modelId"]').focus();
    } catch (e) { msg.textContent = 'Could not list models: ' + e.message; }
  }

  async function importFields() {
    if (!spec.modelId) { toast('Enter the Azure model id in step 1 first', 'error'); return; }
    try {
      const fields = await api('/api/config/models/' + encodeURIComponent(spec.modelId) + '/fields');
      const have = new Set(spec.fields.map(f => f.azureField));
      let added = 0;
      Object.entries(fields).forEach(([name, type]) => {
        if (have.has(name)) return;
        const s = SUGGEST[type] || SUGGEST.string;
        const f = newField(name);
        f.normalizers = s.normalizers.filter(id => catalog.normalizers.some(c => c.id === id));
        f.validators = s.validators.filter(id => catalog.validators.some(c => c.id === id));
        if (/routing|aba/i.test(name) && catalog.validators.some(c => c.id === 'val.abaRouting')) { f.normalizers = ['norm.digits']; f.validators = ['val.abaRouting']; }
        if (type === 'signature' || /signature/i.test(name)) f.minConfidence = 0;
        spec.fields.push(f); added++;
      });
      toast(added ? added + ' field(s) imported from ' + spec.modelId + ' - review the suggested rules' : 'All fields of the model are already listed', 'ok');
      render();
    } catch (e) { toast('Could not read the model: ' + e.message, 'error'); }
  }

  function clean() {
    return Object.assign({}, spec, {
      fields: spec.fields.filter(f => f.azureField && f.azureField.trim()).map(f => ({
        azureField: f.azureField.trim(), canonicalField: (f.canonicalField || '').trim() || null, required: !!f.required, minConfidence: f.minConfidence || 0,
        normalizers: f.normalizers, validators: f.validators, regex: (f.regex || '').trim() || null, regexMessage: f.regex ? (f.regexMessage || 'BAD_FORMAT') : null,
        allowedValues: f.allowedValues && f.allowedValues.length ? f.allowedValues : null, allowedMessage: f.allowedValues && f.allowedValues.length ? (f.allowedMessage || 'NOT_AN_ALLOWED_VALUE') : null
      }))
    });
  }

  render();
  return {};
}

function newField(name) {
  return { azureField: name, canonicalField: '', required: false, minConfidence: 0.8, normalizers: ['norm.whitespace'], validators: [], regex: '', allowedValues: [] };
}

function camel(s) {
  const parts = String(s || '').trim().split(/[^A-Za-z0-9]+/).filter(Boolean);
  return parts.map((p, i) => i === 0 ? p.charAt(0).toLowerCase() + p.slice(1) : p.charAt(0).toUpperCase() + p.slice(1)).join('');
}

/** Existing doc type (as described by the API) -> designer spec. Beans that are not shared building blocks are dropped. */
function fromDescribe(t, dropped) {
  return {
    docType: t.docType, description: t.description || '', enabled: t.enabled, modelId: t.modelId || '', classifierLabels: t.classifierLabels || [],
    minClassifyConfidence: t.minClassifyConfidence, minDocumentConfidence: t.minDocumentConfidence, includeUnmappedFields: t.includeUnmappedFields,
    defaultMinConfidence: t.defaultMinConfidence,
    fields: t.fields.map(f => {
      const out = { azureField: f.azureField, canonicalField: f.canonicalField, required: f.required, minConfidence: f.minConfidence, normalizers: [], validators: [], regex: '', allowedValues: [] };
      f.normalizers.forEach(b => { if (b.shared) out.normalizers.push(b.id); else dropped.push(humanize(f.canonicalField) + ': ' + b.label); });
      f.validators.forEach(b => {
        if (b.shared) out.validators.push(b.id);
        else if (b.type === 'regex' && !out.regex) { out.regex = b.regex; out.regexMessage = b.message; }
        else if (b.type === 'allowed' && !out.allowedValues.length) { out.allowedValues = b.values || []; out.allowedMessage = b.message; }
        else dropped.push(humanize(f.canonicalField) + ': ' + b.label);
      });
      return out;
    })
  };
}
