/* App shell: routing, header, navigation, live job indicator. Pages live in ./pages/. */
import { state, api, $, $$, esc, icon, toast, askReviewer, ago, stepLabel } from './core.js';

const ROUTES = [
  { re: /^\/overview$/, page: 'overview', nav: 'overview' },
  { re: /^\/upload$/, page: 'upload', nav: 'upload' },
  { re: /^\/review$/, page: 'review', nav: 'review' },
  { re: /^\/documents$/, page: 'documents', nav: 'documents' },
  { re: /^\/documents\/(\d+)$/, page: 'document', nav: 'documents', keys: ['id'] },
  { re: /^\/data$/, page: 'data', nav: 'data' },
  { re: /^\/data\/([^/]+)$/, page: 'data', nav: 'data', keys: ['docType'] },
  { re: /^\/insights$/, page: 'insights', nav: 'insights' },
  { re: /^\/operations$/, page: 'operations', nav: 'operations' },
  { re: /^\/config$/, page: 'config', nav: 'config' },
  { re: /^\/config\/settings$/, page: 'config', nav: 'config', fixed: { tab: 'settings' } },
  { re: /^\/config\/doc-types\/new$/, page: 'designer', nav: 'config' },
  { re: /^\/config\/doc-types\/([^/]+)\/(edit|copy)$/, page: 'designer', nav: 'config', keys: ['docType', 'mode'] },
  { re: /^\/config\/doc-types\/([^/]+)$/, page: 'doctype', nav: 'config', keys: ['docType'] }
];

let current = null;     // { page module instance }
let routeSeq = 0;

function parseHash() {
  const h = location.hash.replace(/^#/, '') || '/overview';
  const [path, q] = h.split('?');
  const query = Object.fromEntries(new URLSearchParams(q || ''));
  for (const r of ROUTES) {
    const m = r.re.exec(path);
    if (m) {
      const params = Object.assign({}, r.fixed || {});
      (r.keys || []).forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
      return { route: r, params, query, path };
    }
  }
  return { route: ROUTES[0], params: {}, query: {}, path: '/overview' };
}

async function render() {
  const seq = ++routeSeq;
  const { route, params, query } = parseHash();
  $$('.nav__item').forEach(a => a.classList.toggle('is-active', a.dataset.nav === route.nav));
  document.body.classList.remove('nav-open');
  if (current && current.unmount) { try { current.unmount(); } catch (e) { /* ignore */ } }
  current = null;
  const main = $('#main');
  main.innerHTML = '<div class="empty"><div class="spinner" style="margin:40px auto"></div></div>';
  try {
    const mod = await import('./pages/' + route.page + '.js');
    if (seq !== routeSeq) return;
    main.innerHTML = '';
    current = (await mod.mount(main, { params, query, go })) || {};
  } catch (e) {
    if (seq !== routeSeq) return;
    console.error(e);
    main.innerHTML = '<div class="banner banner--danger">' + icon('alert', 22) + '<div><div class="banner__title">This page could not be loaded</div>' + esc(e.message) + '</div></div>';
  }
  window.scrollTo(0, 0);
}

export function go(hash) {
  if (location.hash === hash) render(); else location.hash = hash;
}

// ------------------------------------------------------------------ header
function renderUser() {
  const name = state.user || '';
  $('.user__name').textContent = name || 'Set reviewer';
  $('.user__avatar').textContent = name ? name.split(/\s+/).map(p => p[0]).join('').slice(0, 2).toUpperCase() : '?';
}

function renderJob() {
  const el = $('#job-pill');
  const j = state.job || {};
  el.classList.toggle('is-running', !!j.running);
  el.classList.toggle('is-failed', !j.running && j.lastStatus === 'FAILED');
  let text = 'Pipeline ready';
  if (j.running) text = 'Processing' + (j.currentStep ? ' · ' + stepLabel(j.currentStep) : '…');
  else if (j.lastStatus === 'FAILED') text = 'Last run failed';
  else if (j.lastEnd) text = 'Last run ' + ago(j.lastEnd);
  $('.job-pill__text', el).textContent = text;
  el.title = j.lastError || 'Batch job status - click for operations';
}


function brand() {
  // Official logo from the local brand folder (./brand, not in git); neutral icon otherwise.
  const holder = $('#brand-logo');
  const fallback = () => { holder.innerHTML = '<span class="brand__mark">' + icon('docs', 20).replace('stroke="currentColor"', 'stroke="#fff"') + '</span>'; };
  const img = new Image();
  img.alt = '';
  img.onload = () => { holder.innerHTML = ''; holder.appendChild(img); };
  img.onerror = () => {
    const png = new Image();
    png.alt = '';
    png.onload = () => { holder.innerHTML = ''; holder.appendChild(png); };
    png.onerror = fallback;
    png.src = '/brand/logo.png';
  };
  fallback();
  img.src = '/brand/logo.svg';
}

// ------------------------------------------------------------------ polling
let ticking = false;
async function tick() {
  if (ticking || document.hidden) return;
  ticking = true;
  try {
    const [job, queue] = await Promise.all([api('/api/job'), api('/api/review-queue?limit=500')]);
    const wasRunning = state.job.running;
    state.job = job;
    state.reviewCount = queue.length;
    renderJob();
    const b = $('#review-count');
    b.textContent = queue.length;
    b.hidden = !queue.length;
    if (wasRunning && !job.running) document.dispatchEvent(new CustomEvent('job-finished', { detail: job }));
    if (current && current.tick) await current.tick();
  } catch (e) {
    $('.job-pill__text').textContent = 'Server unreachable';
  } finally {
    ticking = false;
  }
}

// ------------------------------------------------------------------ start
async function start() {
  $$('[data-icon]').forEach(el => { el.innerHTML = icon(el.dataset.icon, 19); });
  brand();
  renderUser();
  document.addEventListener('reviewer-changed', renderUser);
  $('#user-btn').addEventListener('click', askReviewer);
  // table rows / cards with data-href navigate (links and buttons inside keep their own behaviour)
  document.addEventListener('click', e => {
    const t = e.target.closest('[data-href]');
    if (t && !e.target.closest('a, button, input, select, label, textarea') ) location.hash = t.dataset.href;
    else if (t && t.id === 'job-pill') location.hash = t.dataset.href;
  });
  document.addEventListener('keydown', e => {
    const t = e.target.closest && e.target.closest('[data-href]');
    if (t && e.key === 'Enter' && t === e.target) location.hash = t.dataset.href;
  });
  $('[data-action="toggle-nav"]').addEventListener('click', () => document.body.classList.toggle('nav-open'));
  $('#global-search').addEventListener('submit', e => {
    e.preventDefault();
    const q = e.target.q.value.trim();
    go('#/documents' + (q ? '?q=' + encodeURIComponent(q) : ''));
    e.target.q.blur();
  });
  document.addEventListener('keydown', e => {
    if (e.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) {
      e.preventDefault();
      $('#global-search input').focus();
    }
  });
  try {
    state.meta = await api('/api/meta');
    $('#product-name').textContent = state.meta.productName;
    document.title = state.meta.productName;
  } catch (e) {
    toast(e.message, 'error');
  }
  window.addEventListener('hashchange', render);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) tick(); });
  await render();
  tick();
  setInterval(tick, 3000);
}

start();
