/* Shared helpers: API, formatting, labels, icons, small UI components. */

export const state = {
  user: load('reviewer') || '',
  meta: { docTypes: [], extensions: ['pdf', 'tif', 'tiff', 'jpg', 'jpeg', 'png'], maxFileMb: 500 },
  job: { running: false },
  dbClockSkewMs: 0,       // database clock minus app clock (see OperationsService.dbClockSkewMs)
  reviewCount: 0
};

// ------------------------------------------------------------------ storage (per browser, best effort)
export function load(k) { try { return localStorage.getItem('docintel.' + k); } catch (e) { return null; } }
export function save(k, v) { try { localStorage.setItem('docintel.' + k, v); } catch (e) { /* ignore */ } }

// ------------------------------------------------------------------ DOM
export const $ = (sel, root) => (root || document).querySelector(sel);
export const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
export function esc(v) {
  return String(v == null ? '' : v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}
export function qs(params) {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '' && v !== false) p.set(k, v); });
  const s = p.toString();
  return s ? '?' + s : '';
}

// ------------------------------------------------------------------ API
export async function api(path, opts = {}) {
  const headers = Object.assign({ 'X-User': encodeURIComponent(state.user || '') }, opts.headers || {});
  let body = opts.body;
  if (body && typeof body === 'object' && !(body instanceof FormData) && !(body instanceof Blob)) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(path, { method: opts.method || 'GET', headers, body, cache: 'no-store' });
  } catch (e) {
    throw new Error('The server cannot be reached. Is the demo app running?');
  }
  let data = null;
  const text = await res.text();
  if (text) { try { data = JSON.parse(text); } catch (e) { data = text; } }
  if (!res.ok) throw new Error((data && data.error) || ('Request failed (' + res.status + ')'));
  return data;
}

// ------------------------------------------------------------------ formatting
export function humanize(name) {
  if (!name) return '';
  const s = String(name).replace(/\[(\d+)\]/g, ' $1').replace(/[_.-]+/g, ' ').replace(/([a-z0-9])([A-Z])/g, '$1 $2').trim();
  return (s.charAt(0).toUpperCase() + s.slice(1).toLowerCase())
    .replace(/\baba\b/g, 'ABA').replace(/\bid\b/g, 'ID').replace(/\bach\b/g, 'ACH').replace(/\bssn\b/g, 'SSN');
}
export function docTypeLabel(t) { return t ? humanize(t) : 'Not classified'; }
/**
 * Parses a timestamp. Document times (ocr.doc_*) come from the database clock; when SQL Server runs in another
 * time zone than the app, they are corrected by state.dbClockSkewMs. raw = true for app-clock times
 * (job runner, Spring Batch run records).
 */
export function toDate(v, raw) {
  if (v == null || v === '') return null;
  const d = new Date(typeof v === 'string' && /^\d{4}-\d\d-\d\d \d/.test(v) ? v.replace(' ', 'T') : v);
  if (isNaN(d.getTime())) return null;
  return raw || !state.dbClockSkewMs ? d : new Date(d.getTime() - state.dbClockSkewMs);
}
/** File name as uploaded: without the "yyyyMMdd-HHmmss-" prefix the upload adds for uniqueness. */
export function displayName(n) { return String(n == null ? '' : n).replace(/^\d{8}-\d{6}-/, ''); }
export function fmtDate(v, withYear, raw) {
  const d = toDate(v, raw);
  if (!d) return '–';
  return d.toLocaleString(undefined, Object.assign({ month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }, withYear ? { year: 'numeric' } : {}));
}
export function fmtDay(v) {
  const d = toDate(v);
  return d ? d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : '–';
}
export function ago(v, raw) {
  const d = toDate(v, raw);
  if (!d) return '';
  const s = Math.floor((Date.now() - d.getTime()) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return Math.floor(s / 60) + ' min ago';
  if (s < 86400) return Math.floor(s / 3600) + ' h ago';
  if (s < 86400 * 7) return Math.floor(s / 86400) + ' d ago';
  return fmtDate(v, false, raw);
}
/** Changes every minute - pages add it to their "did anything change?" check so "x min ago" stays current. */
export function minuteTick() { return Math.floor(Date.now() / 60000); }
export function pct(v, digits) {
  if (v == null || isNaN(v)) return '–';
  return (Number(v) * 100).toFixed(digits || 0) + '%';
}
export function num(v) { return v == null ? '–' : Number(v).toLocaleString(); }
export function duration(ms) {
  if (ms == null || ms < 0) return '–';
  const s = ms / 1000;
  if (s < 1) return Math.round(ms) + ' ms';
  if (s < 60) return s.toFixed(s < 10 ? 1 : 0) + ' s';
  const m = Math.floor(s / 60);
  if (m < 60) return m + ' min ' + Math.round(s % 60) + ' s';
  return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
}
export function bytes(n) {
  if (n == null) return '–';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}
export function isSensitive(name) { return /(account|acct).?(number|no|num)|routing|ssn|tax.?id|card.?number|iban/i.test(name || ''); }
export function mask(v) { const s = String(v || ''); return s.length <= 4 ? '••••' : '•••• ' + s.slice(-4); }
export function abaValid(v) {
  const d = String(v || '').replace(/\D/g, '');
  if (d.length !== 9) return false;
  const n = d.split('').map(Number);
  return (3 * (n[0] + n[3] + n[6]) + 7 * (n[1] + n[4] + n[7]) + (n[2] + n[5] + n[8])) % 10 === 0;
}

// ------------------------------------------------------------------ labels
export const STATUS = {
  NEW:        { label: 'Queued',       tone: 'info',    hint: 'Waiting for classification' },
  CLASSIFIED: { label: 'Classified',   tone: 'info',    hint: 'Waiting for extraction' },
  EXTRACTED:  { label: 'Extracted',    tone: 'info',    hint: 'Waiting for validation' },
  COMPLETED:  { label: 'Completed',    tone: 'success', hint: 'Done' },
  REVIEW:     { label: 'Needs review', tone: 'warning', hint: 'A reviewer must check it' },
  ERROR:      { label: 'Retrying',     tone: 'warning', hint: 'Temporary problem, retried automatically' },
  FAILED:     { label: 'Failed',       tone: 'danger',  hint: 'Needs an operator' }
};
export const PROCESSING = ['NEW', 'CLASSIFIED', 'EXTRACTED'];
export const STAGES = {
  INGEST: 'Received', CLASSIFY: 'Classification', EXTRACT: 'Extraction', MAP: 'Validation',
  REVIEW: 'Review', RECOVERY: 'Automatic retry', REPROCESS: 'Reprocess', HOUSEKEEPING: 'Housekeeping'
};
export const REASONS = [
  { id: 'OCR_MISREAD',  label: 'Misread',        help: 'Wrong characters (e.g. 7 read as 1)' },
  { id: 'WRONG_REGION', label: 'Wrong place',    help: 'Value taken from another part of the form' },
  { id: 'MISSING',      label: 'Not extracted',  help: 'The model found nothing' },
  { id: 'FORMAT',       label: 'Format',         help: 'Right value, wrong format' },
  { id: 'OTHER',        label: 'Other',          help: '' }
];
export function reasonLabel(id) {
  if (id === 'CONFIRMED') return 'Confirmed correct';
  const r = REASONS.find(x => x.id === id);
  return r ? r.label : humanize(id);
}
export function stepLabel(step) {
  const s = String(step || '').replace(/:partition\d+$/, '').replace(/WorkerStep$/, 'Step');
  return ({ viewStep: 'preparing', recoveryStep: 'retries', ingestStep: 'reading files', classifyStep: 'classifying',
    extractStep: 'extracting', mapValidateStep: 'validating', housekeepingStep: 'housekeeping', summaryStep: 'finishing' })[s] || s;
}

export function badge(status, extra) {
  const s = STATUS[status] || { label: status || '–', tone: 'neutral' };
  return '<span class="badge badge--' + s.tone + (extra ? ' ' + extra : '') + '" title="' + esc(s.hint || '') + '">' + esc(s.label) + '</span>';
}

/** Plain-English review reasons. Field reasons are marked resolved when the field was corrected/confirmed. */
export function reasonItems(reviewReasons, fields) {
  const raw = String(reviewReasons || '').split(';').map(s => s.trim()).filter(Boolean);
  const handled = new Set((fields || []).filter(f => f.correction_reason).map(f => f.field_name));
  return raw.map(r => {
    const parts = r.split(':');
    const code = parts[0], field = parts[1], rest = parts.slice(2).join(':');
    const fl = field ? '<b>' + esc(humanize(field)) + '</b>' : '';
    let text, short;
    switch (code) {
      case 'MISSING': text = fl + ' was not found on the document'; short = humanize(field) + ' missing'; break;
      case 'LOW_CONFIDENCE': text = fl + ' was read with low confidence'; short = humanize(field) + ' uncertain'; break;
      case 'INVALID': text = fl + ' failed a check' + (rest ? ' (' + esc(humanize(rest)) + ')' : ''); short = humanize(field) + ' invalid'; break;
      case 'LOW_DOC_CONFIDENCE': text = 'The model is not confident about the document as a whole'; short = 'Low document confidence'; break;
      case 'LOW_CLASSIFY_CONFIDENCE': text = 'Document type recognised with low confidence (' + pct(field) + ')'; short = 'Type uncertain'; break;
      case 'UNKNOWN_DOC_TYPE': text = 'Not a supported document type (' + esc(field) + ')'; short = 'Unknown type'; break;
      case 'MULTIPLE_DOC_TYPES': text = 'The file seems to contain several documents'; short = 'Several documents'; break;
      case 'NOT_CLASSIFIED': text = 'The document type could not be determined'; short = 'Not classified'; break;
      case 'DOC_TYPE_DISABLED': text = 'This document type is switched off'; short = 'Type disabled'; break;
      case 'NO_CLASSIFIER_CONFIGURED': text = 'No classifier is configured'; short = 'No classifier'; break;
      case 'ACCOUNT_HOLDER_NAME_MISMATCH': text = 'Customer name and bank account holder name differ'; short = 'Name mismatch'; break;
      case 'MANUAL_REVIEW': text = 'Sent back to review: ' + esc(parts.slice(1).join(':')); short = 'Manual review'; break;
      default: text = esc(humanize(code)) + (field ? ': ' + esc(humanize(field)) : ''); short = humanize(code);
    }
    const resolved = !!(field && handled.has(field) && ['MISSING', 'LOW_CONFIDENCE', 'INVALID'].includes(code));
    return { code, field, text, short, resolved };
  });
}

// ------------------------------------------------------------------ icons (inline SVG, stroke = currentColor)
const P = {
  home: '<path d="M3 11 12 4l9 7"/><path d="M5 10v10h14V10"/><path d="M10 20v-6h4v6"/>',
  upload: '<path d="M12 16V4m0 0-5 5m5-5 5 5"/><path d="M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/>',
  review: '<path d="M9 11l2 2 4-4"/><path d="M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0z"/>',
  docs: '<path d="M7 3h7l5 5v13H7z"/><path d="M14 3v5h5M10 13h6M10 17h6"/>',
  grid: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 10h18M3 15h18M9 4v16"/>',
  chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
  ops: '<path d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"/><path d="M3 12h2m14 0h2M12 3v2m0 14v2M5.6 5.6 7 7m10 10 1.4 1.4M5.6 18.4 7 17m10-10 1.4-1.4"/>',
  settings: '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0"/><circle cx="16" cy="6" r="2"/><circle cx="10" cy="12" r="2"/><circle cx="18" cy="18" r="2"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m13.5 6.5 4 4"/>',
  undo: '<path d="M9 14 4 9l5-5"/><path d="M4 9h11a5 5 0 0 1 0 10h-3"/>',
  eye: '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  eyeoff: '<path d="M3 3l18 18M10.6 5.1A10.4 10.4 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 4M6.6 6.6A17 17 0 0 0 2 12s3.6 7 10 7a9.8 9.8 0 0 0 4-.8"/>',
  refresh: '<path d="M20 11a8 8 0 0 0-14.3-4.9L4 8M4 4v4h4M4 13a8 8 0 0 0 14.3 4.9L20 16m0 4v-4h-4"/>',
  play: '<path d="M7 4v16l13-8z"/>',
  download: '<path d="M12 4v12m0 0-5-5m5 5 5-5"/><path d="M4 20h16"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  arrowL: '<path d="M15 5l-7 7 7 7"/>',
  arrowR: '<path d="M9 5l7 7-7 7"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  bolt: '<path d="M13 3 4 14h7l-1 7 9-11h-7z"/>',
  alert: '<path d="M12 3 2 21h20z"/><path d="M12 10v5M12 18v.01"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5v.01"/>',
  target: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
  file: '<path d="M7 3h7l5 5v13H7z"/><path d="M14 3v5h5"/>',
  sparkle: '<path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6"/>',
  zoom: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4M11 8v6M8 11h6"/>',
  external: '<path d="M14 4h6v6M20 4l-9 9"/><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',
  code: '<path d="m8 8-5 4 5 4M16 8l5 4-5 4M14 4l-4 16"/>',
  shield: '<path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z"/><path d="m9 12 2 2 4-4"/>',
  layers: '<path d="m12 3 9 5-9 5-9-5z"/><path d="m3 13 9 5 9-5"/>'
};
export function icon(name, size) {
  const s = size || 18;
  return '<svg viewBox="0 0 24 24" width="' + s + '" height="' + s + '" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (P[name] || '') + '</svg>';
}

// ------------------------------------------------------------------ components
export function kpi({ label, value, unit, hint, iconName, tone, href }) {
  return '<div class="card kpi' + (tone ? ' kpi--' + tone : '') + '">' +
    '<div class="kpi__label"><span class="kpi__icon">' + icon(iconName || 'chart', 17) + '</span>' + esc(label) + '</div>' +
    '<div class="kpi__value">' + esc(value) + (unit ? '<small>' + esc(unit) + '</small>' : '') + '</div>' +
    (hint ? '<div class="kpi__hint">' + hint + '</div>' : '') +
    (href ? '<a class="kpi__link" href="' + esc(href) + '" aria-label="' + esc(label) + '"></a>' : '') + '</div>';
}
export function meter(conf) {
  if (conf == null) return '<span class="muted small">–</span>';
  const v = Math.max(0, Math.min(1, Number(conf)));
  const tone = v >= 0.85 ? '' : v >= 0.6 ? ' meter--mid' : ' meter--lo';
  return '<span class="meter' + tone + '" title="Model confidence"><span class="meter__track"><span class="meter__fill" style="width:' + Math.round(v * 100) + '%"></span></span>' + pct(v) + '</span>';
}
export function ring(conf, label) {
  if (conf == null) return '';
  const r = 14, c = 2 * Math.PI * r, v = Math.max(0, Math.min(1, Number(conf)));
  const color = v >= 0.85 ? 'var(--g-600)' : v >= 0.6 ? 'var(--warn-solid)' : 'var(--danger)';
  return '<span class="ring" title="' + esc(label || 'Model confidence for the whole document') + '"><svg viewBox="0 0 34 34" aria-hidden="true">' +
    '<circle cx="17" cy="17" r="' + r + '" fill="none" stroke="var(--line)" stroke-width="4"/>' +
    '<circle cx="17" cy="17" r="' + r + '" fill="none" stroke="' + color + '" stroke-width="4" stroke-linecap="round" stroke-dasharray="' +
    (c * v).toFixed(1) + ' ' + c.toFixed(1) + '"/></svg><span>' + esc(label ? label : 'Confidence') + ' <b>' + pct(v) + '</b></span></span>';
}
export function empty(title, text, iconName, action) {
  return '<div class="empty fade-in"><div class="empty__art">' + icon(iconName || 'docs', 30) + '</div><h3>' + esc(title) + '</h3>' +
    (text ? '<p>' + text + '</p>' : '') + (action || '') + '</div>';
}
export function skeletonRows(n, h) {
  return Array.from({ length: n || 5 }, () => '<div class="skeleton" style="height:' + (h || 44) + 'px;margin:10px 16px"></div>').join('');
}
export function pager(total, page, size) {
  const pages = Math.max(1, Math.ceil(total / size));
  const from = total ? page * size + 1 : 0, to = Math.min(total, (page + 1) * size);
  return '<div class="pager"><span>' + num(from) + '–' + num(to) + ' of ' + num(total) + '</span><span class="pager__pages">' +
    '<button class="icon-btn" data-page="' + (page - 1) + '" aria-label="Previous page"' + (page <= 0 ? ' disabled' : '') + '>' + icon('arrowL') + '</button>' +
    '<span style="padding:6px 8px">Page ' + (page + 1) + ' of ' + pages + '</span>' +
    '<button class="icon-btn" data-page="' + (page + 1) + '" aria-label="Next page"' + (page >= pages - 1 ? ' disabled' : '') + '>' + icon('arrowR') + '</button></span></div>';
}
export function pageHead(title, text, actions, crumbs) {
  return '<div class="page-head fade-in"><div class="page-head__text">' + (crumbs ? '<div class="crumbs">' + crumbs + '</div>' : '') +
    '<h1>' + esc(title) + '</h1>' + (text ? '<p>' + text + '</p>' : '') + '</div>' +
    (actions ? '<div class="page-head__actions">' + actions + '</div>' : '') + '</div>';
}

/** Pretty, colourised JSON (escaped). */
export function jsonHtml(value) {
  const s = JSON.stringify(value, null, 2);
  if (s == null) return '';
  return esc(s).replace(/(&quot;(?:[^&]|&(?!quot;))*?&quot;)(\s*:)?|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g, (m, str, colon, bool, n) => {
    if (str) return '<span class="' + (colon ? 'k' : 's') + '">' + str + '</span>' + (colon || '');
    if (bool) return '<span class="b">' + bool + '</span>';
    return '<span class="n">' + n + '</span>';
  });
}

// ------------------------------------------------------------------ toasts & dialog
export function toast(msg, tone, link) {
  const el = document.createElement('div');
  el.className = 'toast' + (tone ? ' toast--' + tone : '');
  el.setAttribute('role', tone === 'error' ? 'alert' : 'status');
  el.innerHTML = '<span>' + icon(tone === 'error' ? 'alert' : tone === 'ok' ? 'check' : 'info', 18) + '</span><span>' + esc(msg) +
    (link ? '<a href="' + esc(link.href) + '">' + esc(link.text) + '</a>' : '') + '</span>';
  $('#toasts').appendChild(el);
  setTimeout(() => el.remove(), tone === 'error' ? 7000 : 4500);
}

/** Modal dialog. Resolves with {name: value} of the inputs in the body when OK is pressed, otherwise null. */
export function dialog({ title, body, ok, cancel, wide, onOpen, validate }) {
  const dlg = $('#dialog');
  dlg.classList.toggle('is-wide', !!wide);
  $('.dialog__title', dlg).textContent = title;
  $('.dialog__body', dlg).innerHTML = body;
  const okBtn = $('.dialog__ok', dlg);
  okBtn.hidden = ok === false;
  okBtn.textContent = ok || 'OK';
  $('.dialog__cancel', dlg).textContent = cancel || (ok === false ? 'Close' : 'Cancel');
  if (onOpen) onOpen(dlg);
  return new Promise(resolve => {
    function values() {
      const out = {};
      $$('[name]', $('.dialog__body', dlg)).forEach(i => {
        if (i.type === 'checkbox') out[i.name] = i.checked;
        else if (i.type === 'radio') { if (i.checked) out[i.name] = i.value; }
        else out[i.name] = i.value;
      });
      return out;
    }
    function onClick(e) {
      if (e.target === okBtn && validate) {
        const err = validate(values());
        if (err) { e.preventDefault(); toast(err, 'error'); }
      }
    }
    okBtn.addEventListener('click', onClick);
    dlg.addEventListener('close', function handler() {
      dlg.removeEventListener('close', handler);
      okBtn.removeEventListener('click', onClick);
      resolve(dlg.returnValue === 'ok' ? values() : null);
    });
    dlg.returnValue = '';
    dlg.showModal();
    const first = $('.dialog__body input:not([type=hidden]):not([type=radio]), .dialog__body textarea, .dialog__body select', dlg);
    if (first) first.focus();
  });
}

export async function ensureReviewer() {
  if (state.user) return true;
  return askReviewer();
}
export async function askReviewer() {
  const r = await dialog({
    title: 'Who is reviewing?',
    body: '<p>Your name is recorded with every correction and approval (audit trail in <code>doc_field_correction</code> and <code>doc_status_history</code>).</p>' +
      '<label class="field-label">Your name<input class="input" name="user" maxlength="60" autocomplete="name" value="' + esc(state.user) + '"></label>',
    ok: 'Save'
  });
  if (r && r.user.trim()) {
    state.user = r.user.trim();
    save('reviewer', state.user);
    document.dispatchEvent(new CustomEvent('reviewer-changed'));
    return true;
  }
  return !!state.user;
}
