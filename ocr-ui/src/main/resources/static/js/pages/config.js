import { api, esc, icon, pageHead, docTypeLabel, num, pct, empty } from '../core.js';

export async function mount(el, ctx) {
  let tab = ctx.params.tab || 'types';
  el.innerHTML = pageHead('Configuration',
    'Document types define which Azure model reads a document, which fields matter, how values are cleaned up and which checks send a document to review.',
    '<a class="btn btn--primary" href="#/config/doc-types/new">' + icon('plus') + 'New document type</a>') +
    '<div class="tabs" style="margin:-6px 0 20px;padding:0" role="tablist">' +
    '<button class="tab" role="tab" data-t="types">Document types</button><button class="tab" role="tab" data-t="settings">System settings</button></div><div id="c"></div>';
  const box = el.querySelector('#c');

  async function show() {
    el.querySelectorAll('[data-t]').forEach(b => b.setAttribute('aria-selected', String(b.dataset.t === tab)));
    history.replaceState(null, '', tab === 'settings' ? '#/config/settings' : '#/config');
    box.innerHTML = '<div class="skeleton" style="height:160px"></div>';
    if (tab === 'types') {
      const types = await api('/api/config/doc-types');
      box.innerHTML = '<div class="dt-cards fade-in">' + types.map(t =>
        '<article class="card dt-card" data-href="#/config/doc-types/' + encodeURIComponent(t.docType) + '" tabindex="0">' +
        '<div class="dt-card__title"><h3>' + esc(t.docType) + '</h3>' +
        (t.source === 'custom' ? '<span class="badge badge--violet badge--plain">Created in UI</span>' : '<span class="badge badge--neutral badge--plain">Built-in</span>') +
        (t.enabled ? '' : '<span class="badge badge--warning">Disabled</span>') + '</div>' +
        '<div class="muted">' + esc(t.description || docTypeLabel(t.docType)) + '</div>' +
        '<div class="row small"><span class="tag tag--mono">' + icon('sparkle', 13) + ' ' + esc(t.modelId || '–') + '</span>' +
        (t.classifierLabels || []).map(l => '<span class="tag tag--mono">' + esc(l) + '</span>').join('') + '</div>' +
        '<div class="dt-card__stats"><span><b>' + num(t.fields.length) + '</b>fields with rules</span><span><b>' + num(t.documents) + '</b>documents</span>' +
        '<span><b>' + pct(t.minDocumentConfidence) + '</b>min. confidence</span></div></article>').join('') +
        '<a class="card dt-card dt-card--new" href="#/config/doc-types/new">' + icon('plus', 28) + 'New document type</a></div>';
    } else {
      const s = await api('/api/config/settings');
      box.innerHTML = '<p class="muted" style="margin-top:0">Read-only. Settings come from <code>ocr-batch/src/main/resources/application.properties</code>, overridden by <code>application-local.properties</code>, system properties and environment variables. Secrets are never shown.</p>' +
        '<div class="grid grid--2 fade-in">' + Object.entries(s).map(([group, values]) =>
          '<section class="card settings-group"><div class="card__head"><h2>' + esc(group) + '</h2></div><div class="card__body"><dl class="kv">' +
          Object.entries(values).map(([k, v]) => '<dt>' + esc(k) + '</dt><dd>' + (String(v || '').startsWith('ERROR') ? '<span style="color:var(--danger)">' + esc(v) + '</span>' : esc(v == null ? '–' : v)) + '</dd>').join('') +
          '</dl></div></section>').join('') + '</div>';
    }
  }
  el.querySelectorAll('[data-t]').forEach(b => b.addEventListener('click', () => { tab = b.dataset.t; show().catch(err); }));
  function err(e) { box.innerHTML = '<div class="banner banner--danger">' + esc(e.message) + '</div>'; }
  await show().catch(err);
  return {};
}
