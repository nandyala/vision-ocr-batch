import { api, esc, icon, kpi, pct, num, humanize, docTypeLabel, badge, ago, duration, pageHead, empty, state, STAGES } from '../core.js';
import { stackedBars, donut, hbars } from '../charts.js';

export async function mount(el) {
  el.innerHTML = pageHead('Overview',
    'How documents flow through the pipeline: AI extraction, automatic validation, and human review where it matters.',
    '<a class="btn btn--secondary" href="#/review">' + icon('review') + 'Review queue</a>' +
    '<a class="btn btn--primary" href="#/upload">' + icon('upload') + 'Upload document</a>') +
    '<div id="ov"></div>';
  const box = el.querySelector('#ov');
  let lastKey = '';

  async function load() {
    const d = await api('/api/overview?days=14');
    const key = JSON.stringify(d);
    if (key === lastKey) return;
    lastKey = key;
    box.innerHTML = render(d);
  }
  await load().catch(e => { box.innerHTML = '<div class="banner banner--danger">' + icon('alert', 22) + '<div>' + esc(e.message) + '</div></div>'; });
  return { tick: () => load().catch(() => {}) };
}

function render(d) {
  const k = d.kpis, by = d.byStatus || {};
  if (!k.total) {
    return '<div class="card">' + empty('No documents yet',
      'Upload a scanned form to see the pipeline in action. Results appear here within seconds.', 'upload',
      '<a class="btn btn--primary" href="#/upload">' + icon('upload') + 'Upload the first document</a>') + '</div>';
  }
  const finished = (k.completed || 0);
  const stp = finished ? k.straightThrough / finished : null;
  const accuracy = k.fieldsExtracted ? 1 - (k.corrections / k.fieldsExtracted) : null;
  const inflight = (by.NEW || 0) + (by.CLASSIFIED || 0) + (by.EXTRACTED || 0);
  const problems = (by.FAILED || 0) + (by.ERROR || 0);

  let h = '<div class="kpis fade-in">' +
    kpi({ label: 'Documents processed', value: num(k.total), iconName: 'docs', hint: num(k.uploadedToday) + ' today' + (inflight ? ' · ' + inflight + ' in progress' : ''), href: '#/documents' }) +
    kpi({ label: 'Straight-through rate', value: stp == null ? '–' : Math.round(stp * 100), unit: stp == null ? '' : '%', iconName: 'bolt', tone: 'info', hint: 'Completed with no human touch' }) +
    kpi({ label: 'Field accuracy', value: accuracy == null ? '–' : (accuracy * 100).toFixed(1), unit: accuracy == null ? '' : '%', iconName: 'target', tone: 'violet', hint: num(k.fieldsExtracted) + ' fields · ' + num(k.corrections) + ' corrected', href: '#/insights' }) +
    kpi({ label: 'Avg. time to result', value: k.avgSecondsToResult == null ? '–' : duration(k.avgSecondsToResult * 1000), iconName: 'clock', hint: 'Upload → extracted & validated' }) +
    kpi({ label: 'Needs review', value: num(by.REVIEW || 0), iconName: 'review', tone: (by.REVIEW ? 'warn' : ''), hint: by.REVIEW ? 'Waiting for a reviewer' : 'Queue is empty', href: '#/review' }) +
    kpi({ label: 'Exceptions', value: num(problems), iconName: 'alert', tone: problems ? 'danger' : '', hint: (by.ERROR || 0) + ' retrying · ' + (by.FAILED || 0) + ' failed', href: '#/operations' }) +
    '</div>';

  // daily volume
  const days = [];
  const today = new Date();
  for (let i = d.days - 1; i >= 0; i--) {
    const x = new Date(today.getFullYear(), today.getMonth(), today.getDate() - i);
    const key = x.getFullYear() + '-' + String(x.getMonth() + 1).padStart(2, '0') + '-' + String(x.getDate()).padStart(2, '0');
    days.push({ key, label: x.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }), values: {} });
  }
  (d.daily || []).forEach(r => {
    const day = days.find(x => x.key === r.day);
    if (!day) return;
    const grp = r.status === 'COMPLETED' ? 'completed' : r.status === 'REVIEW' ? 'review' : (r.status === 'FAILED' || r.status === 'ERROR') ? 'failed' : 'progress';
    day.values[grp] = (day.values[grp] || 0) + r.cnt;
  });
  const series = [
    { key: 'completed', label: 'Completed', color: 'var(--c-completed)' },
    { key: 'review', label: 'Needs review', color: 'var(--c-review)' },
    { key: 'failed', label: 'Failed / retrying', color: 'var(--c-failed)' },
    { key: 'progress', label: 'In progress', color: 'var(--c-progress)' }
  ];
  const statusSegs = [
    { label: 'Completed', value: by.COMPLETED || 0, color: 'var(--c-completed)' },
    { label: 'Needs review', value: by.REVIEW || 0, color: 'var(--c-review)' },
    { label: 'In progress', value: inflight, color: 'var(--c-progress)' },
    { label: 'Retrying', value: by.ERROR || 0, color: '#f08c3a' },
    { label: 'Failed', value: by.FAILED || 0, color: 'var(--c-failed)' }
  ];
  h += '<div class="grid grid--2-1" style="margin-bottom:20px">' +
    '<section class="card"><div class="card__head"><h2>Daily volume</h2><span class="muted small">last ' + d.days + ' days, by current outcome</span></div>' +
    '<div class="card__body">' + stackedBars(days, series, { label: 'Documents per day' }) + '</div></section>' +
    '<section class="card"><div class="card__head"><h2>Where documents are now</h2></div><div class="card__body">' +
    donut(statusSegs, num(k.total), 'documents') + '</div></section></div>';

  // confidence + flagged fields + doc types
  const buckets = Array.from({ length: 10 }, (_, i) => ({ label: (i * 10) + '%', values: { n: 0 } }));
  (d.confidence || []).forEach(r => { if (buckets[r.bucket]) buckets[r.bucket].values.n = r.cnt; });
  const conf = stackedBars(buckets.slice(3), [{ key: 'n', label: 'Documents', color: 'var(--g-600)' }], { legend: false, width: 360, height: 210, label: 'Confidence distribution' });
  const flagged = (d.flaggedFields || []);
  const statusWord = { MISSING: 'missing', LOW_CONFIDENCE: 'low confidence', INVALID: 'failed check' };
  h += '<div class="grid grid--3" style="margin-bottom:20px">' +
    '<section class="card"><div class="card__head"><h2>Model confidence</h2><span class="muted small">documents per score</span></div><div class="card__body">' + conf + '</div></section>' +
    '<section class="card"><div class="card__head"><h2>Most flagged fields</h2><a class="muted small" href="#/insights">Details →</a></div><div class="card__body">' +
    (flagged.length ? hbars(flagged.map(f => ({ label: esc(humanize(f.field_name)) + ' <span class="muted small">' + esc(statusWord[f.field_status] || f.field_status) + '</span>',
      value: f.cnt, tone: f.field_status === 'INVALID' ? 'danger' : 'warn' }))) : '<p class="muted">No field was flagged. Every value passed the rules.</p>') +
    '</div></section>' +
    '<section class="card"><div class="card__head"><h2>By document type</h2></div><div class="card__body card__body--flush"><table class="table table--compact"><thead><tr><th>Type</th><th class="num">Docs</th><th class="num">Review</th><th class="num">Avg conf.</th></tr></thead><tbody>' +
    (d.byDocType || []).map(t => '<tr class="is-clickable" data-href="' + (t.doc_type ? '#/data/' + encodeURIComponent(t.doc_type) : '#/documents?docType=NONE') + '"><td>' + (t.doc_type ? esc(docTypeLabel(t.doc_type)) : '<span class="muted">Not classified</span>') +
      '</td><td class="num">' + num(t.total) + '</td><td class="num">' + num(t.review) + '</td><td class="num">' + pct(t.avg_confidence) + '</td></tr>').join('') +
    '</tbody></table></div></section></div>';

  // activity
  h += '<section class="card"><div class="card__head"><h2>Latest activity</h2><a class="muted small" href="#/documents">All documents →</a></div><ul class="feed">' +
    (d.recent || []).map(activity).join('') + '</ul></section>';
  return h;
}

function activity(a) {
  let ic = 'check', tone = '', title;
  const file = '<a href="#/documents/' + a.doc_id + '">' + esc(a.file_name) + '</a>';
  if (a.stage === 'REVIEW' && a.to_status === 'COMPLETED') { ic = 'shield'; title = esc(a.changed_by || 'Reviewer') + ' approved ' + file; }
  else if (a.stage === 'REVIEW' && a.from_status === a.to_status) { ic = 'edit'; tone = 'info'; title = esc(a.changed_by || 'Reviewer') + ': ' + esc(noteText(a.note) || 'updated') + ' on ' + file; }
  else if (a.to_status === 'REVIEW') { ic = 'review'; tone = 'warn'; title = file + ' needs review'; }
  else if (a.to_status === 'FAILED') { ic = 'alert'; tone = 'danger'; title = file + ' failed'; }
  else if (a.to_status === 'ERROR') { ic = 'refresh'; tone = 'warn'; title = file + ' will be retried'; }
  else if (a.to_status === 'COMPLETED') { ic = 'check'; title = file + ' completed automatically'; }
  else { ic = 'file'; tone = 'info'; title = file + ' · ' + esc(STAGES[a.stage] || a.stage) + ' ' + badge(a.to_status); }
  return '<li><span class="feed__icon' + (tone ? ' feed__icon--' + tone : '') + '">' + icon(ic, 16) + '</span><div><div class="feed__title">' + title +
    '</div><div class="feed__meta">' + esc(STAGES[a.stage] || a.stage) + (a.note && !(a.stage === 'REVIEW' && a.from_status === a.to_status) ? ' · ' + esc(a.note) : '') +
    '</div></div><span class="feed__time">' + esc(ago(a.changed_at)) + '</span></li>';
}


/** "Corrected routingNumber - comment" -> "Corrected Routing number - comment" */
export function noteText(note) {
  return String(note || '').replace(/^(Corrected|Confirmed|Correction of) ([A-Za-z0-9_.\[\]]+)/, (m, verb, f) => verb + ' ' + humanize(f).toLowerCase());
}
