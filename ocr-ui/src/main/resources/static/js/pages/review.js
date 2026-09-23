import { api, esc, icon, pageHead, empty, pct, ago, docTypeLabel, reasonItems, meter, num } from '../core.js';

export async function mount(el) {
  el.innerHTML = pageHead('Review queue',
    'Documents the rules could not accept automatically. Oldest first. Correct what the model got wrong, confirm what it got right, then approve.',
    '<button class="btn btn--primary" type="button" id="start" disabled>' + icon('play') + 'Start reviewing</button>') +
    '<section class="card" id="q"><div>' + '<div class="skeleton" style="height:64px;margin:16px"></div>'.repeat(4) + '</div></section>';
  const box = el.querySelector('#q');
  let items = [], key = '';

  async function load() {
    items = await api('/api/review-queue?limit=500');
    const k = JSON.stringify(items);
    if (k === key) return;
    key = k;
    el.querySelector('#start').disabled = !items.length;
    if (!items.length) {
      box.innerHTML = empty('All caught up', 'No document is waiting for review. New ones appear here as soon as the pipeline flags them.', 'check',
        '<a class="btn btn--secondary" href="#/upload">' + icon('upload') + 'Upload a document</a>');
      return;
    }
    const oldest = items[0];
    box.innerHTML = '<div class="card__head"><h2>' + num(items.length) + ' document' + (items.length > 1 ? 's' : '') + ' waiting</h2>' +
      '<span class="muted small">oldest waiting since ' + esc(ago(oldest.updated_at)) + '</span></div><ul class="queue">' +
      items.map((d, i) => {
        const reasons = reasonItems(d.review_reasons, []);
        return '<li class="queue-item" data-href="#/documents/' + d.id + '?queue=1" tabindex="0">' +
          '<span class="queue-item__pos">' + (i + 1) + '</span><div style="min-width:0">' +
          '<div class="queue-item__title ellipsis">' + esc(d.file_name) + ' <span class="muted small">#' + d.id + '</span></div>' +
          '<div class="muted small">' + esc(docTypeLabel(d.doc_type)) + ' · waiting ' + esc(ago(d.updated_at)) +
          (d.flagged_fields ? ' · ' + d.flagged_fields + ' flagged field' + (d.flagged_fields > 1 ? 's' : '') : '') +
          (d.corrections ? ' · ' + d.corrections + ' already corrected' : '') + '</div>' +
          '<div class="queue-item__reasons">' + reasons.slice(0, 5).map(r => '<span class="badge badge--warning badge--plain">' + esc(r.short) + '</span>').join('') +
          (reasons.length > 5 ? '<span class="tag">+' + (reasons.length - 5) + ' more</span>' : '') + '</div></div>' +
          '<div class="row">' + meter(d.doc_confidence) + '<span class="btn btn--secondary btn--sm">Review ' + icon('arrowR', 16) + '</span></div></li>';
      }).join('') + '</ul>';
  }

  el.querySelector('#start').addEventListener('click', () => { if (items.length) location.hash = '#/documents/' + items[0].id + '?queue=1'; });
  await load().catch(e => { box.innerHTML = '<div class="banner banner--danger">' + esc(e.message) + '</div>'; });
  return { tick: () => load().catch(() => {}) };
}
