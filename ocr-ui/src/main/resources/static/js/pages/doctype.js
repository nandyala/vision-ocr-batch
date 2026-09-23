import { api, esc, icon, pageHead, docTypeLabel, num, pct, empty, humanize } from '../core.js';

export async function mount(el, ctx) {
  const t = await api('/api/config/doc-types/' + encodeURIComponent(ctx.params.docType));
  const chip = b => b.type === 'regex' ? '<span class="tag tag--mono" title="' + esc(b.message || '') + '">matches ' + esc(b.regex) + '</span>'
    : b.type === 'allowed' ? '<span class="tag" title="' + esc(b.message || '') + '">one of: ' + esc((b.values || []).join(', ')) + '</span>'
    : '<span class="tag' + (b.shared ? ' tag--green' : '') + '" title="' + esc(b.id || '') + '">' + esc(b.label) + '</span>';

  el.innerHTML = pageHead(t.docType, esc(t.description || ''),
    (t.editable ? '<a class="btn btn--primary" href="#/config/doc-types/' + encodeURIComponent(t.docType) + '/edit">' + icon('edit') + 'Edit</a>' : '') +
    '<a class="btn btn--secondary" href="#/config/doc-types/' + encodeURIComponent(t.docType) + '/copy">' + icon('plus') + 'Copy as new type</a>' +
    '<a class="btn btn--ghost" href="#/data/' + encodeURIComponent(t.docType) + '">' + icon('grid') + 'View data</a>',
    '<a href="#/config">Configuration</a> <span>/</span> <span>Document types</span>') +
    '<div class="grid grid--1-2">' +
    '<section class="card"><div class="card__head"><h2>Settings</h2>' +
    (t.source === 'custom' ? '<span class="badge badge--violet badge--plain" style="margin-left:auto">Created in UI</span>' : '<span class="badge badge--neutral badge--plain" style="margin-left:auto">Built-in</span>') +
    '</div><div class="card__body"><dl class="kv">' +
    kv('Enabled', t.enabled ? 'Yes' : 'No') + kv('Azure model', t.modelId, true) +
    kv('Classifier labels', (t.classifierLabels || []).join(', ') || '–', true) +
    kv('Min. classification confidence', pct(t.minClassifyConfidence)) + kv('Min. document confidence', pct(t.minDocumentConfidence)) +
    kv('Keep fields without rules', t.includeUnmappedFields ? 'Yes - every field the model returns is stored' : 'No - only the fields listed') +
    kv('Default field confidence', t.defaultMinConfidence ? pct(t.defaultMinConfidence) : 'no check') +
    kv('SQL view', t.sqlView, true) + kv('Documents', num(t.documents == null ? '' : t.documents)) + kv('Defined in', t.file, true) + '</dl>' +
    ((t.crossFieldRules || []).length ? '<div class="section-label">Cross-field checks</div><ul style="margin:6px 0 0;padding-left:18px">' +
      t.crossFieldRules.map(r => '<li>' + esc(r.label) + '</li>').join('') + '</ul>' : '') +
    '</div></section>' +
    '<section class="card"><div class="card__head"><h2>Fields with rules</h2><span class="muted small">' + t.fields.length + ' configured</span></div>' +
    (t.fields.length ? '<div class="table-wrap"><table class="table"><thead><tr><th>Azure field → stored as</th><th>Required</th><th>Min. confidence</th><th>Clean-up</th><th>Checks</th></tr></thead><tbody>' +
      t.fields.map(f => '<tr><td><div class="mono small muted">' + esc(f.azureField) + '</div><b>' + esc(humanize(f.canonicalField)) + '</b> <span class="mono small muted">' + esc(f.canonicalField) + '</span></td>' +
        '<td>' + (f.required ? '<span class="badge badge--success badge--plain">required</span>' : '<span class="muted">optional</span>') + '</td>' +
        '<td>' + (f.minConfidence ? pct(f.minConfidence) : '<span class="muted">no check</span>') + '</td>' +
        '<td><div class="row" style="gap:4px">' + (f.normalizers.map(chip).join('') || '<span class="muted">–</span>') + '</div></td>' +
        '<td><div class="row" style="gap:4px">' + (f.validators.map(chip).join('') || '<span class="muted">–</span>') + '</div></td></tr>').join('') +
      '</tbody></table></div>' : empty('No field rules', 'Every field the model returns is stored as-is.', 'docs')) + '</section></div>' +
    (t.xml ? '<section class="card" style="margin-top:20px"><div class="card__head"><h2>XML definition</h2><span class="muted small">' + esc(t.file) + '</span></div><div class="card__body"><pre class="code">' + esc(t.xml) + '</pre></div></section>' : '');
  return {};
}

function kv(k, v, mono) {
  return '<dt>' + esc(k) + '</dt><dd' + (mono ? ' class="mono small"' : '') + '>' + esc(v == null || v === '' ? '–' : v) + '</dd>';
}
