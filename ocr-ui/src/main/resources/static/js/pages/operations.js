import { api, state, esc, icon, pageHead, badge, fmtDate, ago, duration, docTypeLabel, empty, toast, dialog, ensureReviewer, num, STAGES, stepLabel, displayName } from '../core.js';

const JOB_TONE = { COMPLETED: 'success', STARTED: 'info', STARTING: 'info', FAILED: 'danger', STOPPED: 'warning', ABANDONED: 'neutral', UNKNOWN: 'neutral' };
const STEP_NAMES = { viewStep: 'Refresh SQL views', recoveryStep: 'Retries & reprocess requests', ingestStep: 'Read new files', classifyStep: 'Classify',
  extractStep: 'Extract fields', mapValidateStep: 'Validate', housekeepingStep: 'Housekeeping', summaryStep: 'Summary' };

export async function mount(el) {
  el.innerHTML = pageHead('Operations',
    'The batch pipeline behind the scenes: runs, automatic retries, failures and reprocessing. The same job runs on the server on a schedule; here it also starts after every upload.',
    '<button class="btn btn--secondary" type="button" id="new-rr">' + icon('refresh') + 'New reprocess request</button>' +
    '<button class="btn btn--primary" type="button" id="run">' + icon('play') + 'Run job now</button>') +
    '<div id="live"></div><div class="grid grid--2" style="margin-top:20px"><section class="card" id="queue"></section><section class="card" id="errors"></section></div>' +
    '<section class="card" style="margin-top:20px" id="runs"></section>' +
    '<section class="card" style="margin-top:20px" id="rr"></section>';
  let openRun = null;

  async function live() {
    const j = state.job || {};
    el.querySelector('#live').innerHTML = '<div class="banner ' + (j.running ? 'banner--info' : j.lastStatus === 'FAILED' ? 'banner--danger' : 'banner--ok') + '">' +
      (j.running ? '<span class="spinner spinner--sm" style="margin-top:3px"></span>' : icon(j.lastStatus === 'FAILED' ? 'alert' : 'check', 22)) +
      '<div><div class="banner__title">' + (j.running ? 'Job running' + (j.currentStep ? ' - ' + esc(stepLabel(j.currentStep)) : '') : j.lastStatus === 'FAILED' ? 'Last run failed' : 'Pipeline idle') + '</div>' +
      (j.lastStart ? 'Last run started ' + esc(ago(j.lastStart, true)) + (j.lastEnd && !j.running ? ', finished ' + esc(ago(j.lastEnd, true)) : '') : 'No run from this app yet.') +
      (j.lastError ? '<br><span class="small">' + esc(j.lastError) + '</span>' : '') + '</div></div>';
  }

  async function queue() {
    const q = await api('/api/queue');
    const table = (rows, cols) => '<div class="table-wrap"><table class="table table--compact"><tbody>' + rows.map(r => '<tr class="is-clickable" data-href="#/documents/' + r.id + '">' + cols(r) + '</tr>').join('') + '</tbody></table></div>';
    el.querySelector('#queue').innerHTML = '<div class="card__head"><h2>Work queue</h2>' +
      (q.retries.length ? '<button class="btn btn--ghost btn--sm" id="retry-now" style="margin-left:auto">' + icon('refresh', 15) + 'Retry now</button>' : '') + '</div>' +
      (!q.inProgress.length && !q.retries.length && !q.failed.length ? empty('Nothing waiting', 'No document is in progress, waiting for a retry or failed.', 'check') :
        (q.inProgress.length ? '<div class="section-label" style="margin:14px 20px 4px">In progress (' + q.inProgress.length + ')</div>' +
          table(q.inProgress, r => '<td>#' + r.id + '</td><td class="ellipsis" style="max-width:260px">' + esc(displayName(r.file_name)) + '</td><td>' + badge(r.status, 'badge--pulse') + '</td><td class="muted nowrap">' + esc(ago(r.updated_at)) + '</td>') : '') +
        (q.retries.length ? '<div class="section-label" style="margin:14px 20px 4px">Waiting for automatic retry (' + q.retries.length + ')</div>' +
          table(q.retries, r => '<td>#' + r.id + '</td><td class="ellipsis" style="max-width:220px">' + esc(displayName(r.file_name)) + '</td><td>' + esc(STAGES[r.failed_stage] || r.failed_stage) + ' · attempt ' + r.retry_count +
            '</td><td class="nowrap muted">next ' + esc(fmtDate(r.next_retry_at)) + '</td>') : '') +
        (q.failed.length ? '<div class="section-label" style="margin:14px 20px 4px">Failed - needs an operator (' + q.failed.length + ')</div>' +
          table(q.failed, r => '<td>#' + r.id + '</td><td class="ellipsis" style="max-width:220px">' + esc(displayName(r.file_name)) + '</td><td>' + esc(STAGES[r.failed_stage] || r.failed_stage || '') +
            '</td><td class="small muted ellipsis" style="max-width:260px" title="' + esc(r.last_error) + '">' + esc(r.last_error || '') + '</td>') : ''));
    const rn = el.querySelector('#retry-now');
    if (rn) rn.addEventListener('click', async () => {
      try { const r = await api('/api/queue/retry-now', { method: 'POST', body: {} }); toast(r.documents + ' document(s) retried now', 'ok'); refresh(); } catch (e) { toast(e.message, 'error'); }
    });
  }

  async function errors() {
    const e = await api('/api/errors?days=7');
    el.querySelector('#errors').innerHTML = '<div class="card__head"><h2>Errors</h2><span class="muted small">last 7 days, grouped</span></div>' +
      (!e.groups.length ? empty('No errors', 'Azure, database and file errors of the last 7 days appear here.', 'shield') :
        '<div class="table-wrap"><table class="table table--compact"><thead><tr><th>Stage</th><th>Error</th><th class="num">Count</th><th class="num">Docs</th><th>Last</th></tr></thead><tbody>' +
        e.groups.map(g => '<tr><td>' + esc(STAGES[g.stage] || g.stage) + '</td><td><div>' + (g.http_status ? '<span class="tag">HTTP ' + g.http_status + '</span> ' : '') +
          '<span class="badge badge--' + (g.retryable ? 'info">retryable' : 'danger">permanent') + '</span></div><div class="small muted ellipsis" style="max-width:240px" title="' + esc(g.sample_message) + '">' +
          esc(g.sample_message || g.error_class || '') + '</div></td><td class="num">' + num(g.cnt) + '</td><td class="num">' + num(g.documents) + '</td><td class="nowrap muted">' + esc(ago(g.last_seen)) + '</td></tr>').join('') +
        '</tbody></table></div>');
  }

  async function runs() {
    const rows = await api('/api/jobs?limit=15');
    let steps = [];
    if (openRun) steps = await api('/api/jobs/' + openRun + '/steps').catch(() => []);
    el.querySelector('#runs').innerHTML = '<div class="card__head"><h2>Job runs</h2><span class="muted small">Spring Batch metadata (ocr.BATCH_JOB_EXECUTION) · click a run for its steps</span></div>' +
      (!rows.length ? empty('No runs yet', 'Run the job or upload a document.', 'play') :
        '<div class="table-wrap"><table class="table table--compact"><thead><tr><th>Run</th><th>Status</th><th>Started</th><th>Duration</th><th class="num">Documents validated</th><th>Exit</th></tr></thead><tbody>' +
        rows.map(r => '<tr class="is-clickable" data-run="' + r.id + '"><td>#' + r.id + '</td><td><span class="badge badge--' + (JOB_TONE[r.status] || 'neutral') + '">' + esc(r.status) + '</span></td>' +
          '<td class="nowrap">' + esc(fmtDate(r.start_time, false, true)) + '</td><td>' + esc(duration(r.duration_ms)) + '</td><td class="num">' + num(r.documents_mapped || 0) + '</td>' +
          '<td class="small muted ellipsis" style="max-width:280px" title="' + esc(r.exit_message) + '">' + esc(r.exit_code) + '</td></tr>' +
          (openRun === r.id ? '<tr><td colspan="6" style="background:var(--g-50);padding:12px 20px">' + stepsTable(steps) + '</td></tr>' : '')).join('') + '</tbody></table></div>');
  }

  function stepsTable(steps) {
    const main = steps.filter(s => !/:partition\d+$/.test(s.step_name));
    return '<table class="table table--compact" style="background:#fff;border-radius:8px"><thead><tr><th>Step</th><th>Status</th><th class="num">Read</th><th class="num">Written</th><th class="num">Filtered</th><th>Duration</th></tr></thead><tbody>' +
      main.map(s => {
        const parts = steps.filter(p => p.step_name.indexOf(s.step_name.replace('Step', 'WorkerStep') + ':partition') === 0);
        const read = parts.length ? parts.reduce((a, p) => a + p.read_count, 0) : s.read_count;
        const written = parts.length ? parts.reduce((a, p) => a + p.write_count, 0) : s.write_count;
        return '<tr><td>' + esc(STEP_NAMES[s.step_name] || s.step_name) + (parts.length ? ' <span class="tag">' + parts.length + ' parallel</span>' : '') + '</td><td><span class="badge badge--' +
          (JOB_TONE[s.status] || 'neutral') + '">' + esc(s.status) + '</span></td><td class="num">' + num(read) + '</td><td class="num">' + num(written) + '</td><td class="num">' + num(s.filter_count) +
          '</td><td>' + esc(duration(s.duration_ms)) + '</td></tr>';
      }).join('') + '</tbody></table>';
  }

  async function requests() {
    const rows = await api('/api/reprocess-requests?limit=20');
    el.querySelector('#rr').innerHTML = '<div class="card__head"><h2>Reprocess requests</h2><span class="muted small">ocr.doc_reprocess_request - applied by the next job run</span></div>' +
      (!rows.length ? empty('No requests yet', 'Push documents back into the pipeline after fixing a rule, a model or a key.', 'refresh') :
        '<div class="table-wrap"><table class="table table--compact"><thead><tr><th>#</th><th>From</th><th>Which documents</th><th>Reason</th><th>By</th><th>When</th><th>State</th></tr></thead><tbody>' +
        rows.map(r => '<tr><td>' + r.id + '</td><td>' + esc(STAGES[r.from_stage] || r.from_stage) + '</td><td>' + [
          r.doc_id ? '<a href="#/documents/' + r.doc_id + '">#' + r.doc_id + ' ' + esc(displayName(r.file_name || '')) + '</a>' : '',
          r.doc_type ? 'type ' + esc(docTypeLabel(r.doc_type)) : '', r.current_status ? 'status ' + esc(r.current_status) : '', r.failed_stage ? 'failed at ' + esc(r.failed_stage) : ''
        ].filter(Boolean).join(' · ') + '</td><td class="small">' + esc(r.reason || '') + '</td><td>' + esc(r.requested_by) + '</td><td class="nowrap muted">' + esc(ago(r.requested_at)) + '</td>' +
          '<td><span class="badge badge--' + (r.state === 'APPLIED' ? 'success' : r.state === 'REJECTED' ? 'danger' : 'info') + '">' + esc(r.state) + '</span>' +
          (r.applied_count != null ? ' <span class="small muted">' + r.applied_count + ' doc(s)</span>' : '') + (r.message ? '<div class="small muted">' + esc(r.message) + '</div>' : '') + '</td></tr>').join('') +
        '</tbody></table></div>');
  }

  function refresh() { return Promise.all([live(), queue(), errors(), runs(), requests()]).catch(e => toast(e.message, 'error')); }

  el.querySelector('#run').addEventListener('click', async () => {
    try { state.job = await api('/api/job/run', { method: 'POST', body: {} }); toast('Job started', 'ok'); live(); } catch (e) { toast(e.message, 'error'); }
  });
  el.querySelector('#runs').addEventListener('click', e => {
    const tr = e.target.closest('tr[data-run]');
    if (!tr) return;
    openRun = openRun === Number(tr.dataset.run) ? null : Number(tr.dataset.run);
    runs();
  });
  el.querySelector('#new-rr').addEventListener('click', async () => {
    if (!(await ensureReviewer())) return;
    const types = state.meta.docTypes || [];
    const r = await dialog({
      title: 'New reprocess request', ok: 'Create request',
      body: '<p class="small muted">Pushes matching documents back into the pipeline. Corrections are kept. Choose at least one filter.</p>' +
        '<div class="form-grid"><label class="field-label span-2">Re-run from<select class="select" name="fromStage">' +
        '<option value="MAP">Validation - re-apply rules to the stored result (no Azure cost)</option>' +
        '<option value="EXTRACT">Extraction - call the AI model again</option><option value="CLASSIFY">Classification - start from the beginning</option></select></label>' +
        '<label class="field-label">Document #<input class="input" name="docId" placeholder="any" inputmode="numeric"></label>' +
        '<label class="field-label">Document type<select class="select" name="docType"><option value="">any</option>' + types.map(t => '<option value="' + esc(t.docType) + '">' + esc(docTypeLabel(t.docType)) + '</option>').join('') + '</select></label>' +
        '<label class="field-label">Current status<select class="select" name="currentStatus"><option value="">any</option><option>COMPLETED</option><option>REVIEW</option><option>FAILED</option><option>ERROR</option></select></label>' +
        '<label class="field-label">Failed at<select class="select" name="failedStage"><option value="">any</option><option>CLASSIFY</option><option>EXTRACT</option><option>MAP</option></select></label>' +
        '<label class="field-label span-2">Reason<input class="input" name="reason" placeholder="e.g. new validation rule for account numbers"></label>' +
        '<label class="toggle span-2"><input type="checkbox" name="runNow" checked>Run the job right away</label></div>',
      validate: v => (!v.docId && !v.docType && !v.currentStatus && !v.failedStage) ? 'Choose at least one filter' : (v.docId && !/^#?\d+$/.test(v.docId.trim()) ? 'Document # must be a number' : null)
    });
    if (!r) return;
    try {
      const res = await api('/api/reprocess-requests', { method: 'POST', body: r });
      toast('Request created - ' + res.matching + ' document(s) match right now', 'ok');
      refresh();
    } catch (e) { toast(e.message, 'error'); }
  });

  await refresh();
  let n = 0;
  return { tick: () => { live(); if (++n % 2 === 0 || state.job.running) return refresh(); } };
}
