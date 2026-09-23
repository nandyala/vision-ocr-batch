-- =============================================================================
-- Vision OCR - operator cookbook (PostgreSQL / H2)
-- Changes are picked up by the NEXT job run (recoveryStep runs first).
-- =============================================================================

-- ---------------------------------------------------------------- monitoring

-- Queue overview
SELECT COALESCE(doc_type, '-') AS doc_type, status, COUNT(*) AS docs
FROM doc_job GROUP BY doc_type, status ORDER BY doc_type, status;

-- Documents waiting for an automatic retry, and when
SELECT id, file_name, failed_stage, retry_count, next_retry_at, last_error
FROM doc_job WHERE status = 'ERROR' ORDER BY next_retry_at;

-- Documents that need an operator (not retried automatically)
SELECT id, file_name, failed_stage, retry_count, last_error, updated_at
FROM doc_job WHERE status = 'FAILED' ORDER BY updated_at DESC;

-- Most common errors in the last 24h (spot an outage or a bad model/config)
SELECT stage, error_class, http_status, retryable, COUNT(*) AS n, MAX(created_at) AS last_seen
FROM doc_error WHERE created_at > CURRENT_TIMESTAMP - INTERVAL '1' DAY
GROUP BY stage, error_class, http_status, retryable ORDER BY n DESC;

-- Review queue with reasons
SELECT id, file_name, doc_type, review_reasons, updated_at
FROM doc_job WHERE status = 'REVIEW' ORDER BY updated_at;

-- Full timeline of one document
SELECT changed_at, stage, from_status, to_status, changed_by, note
FROM doc_status_history WHERE doc_id = :doc_id ORDER BY id;

-- All Azure calls for one document (model versions, timings)
SELECT id, operation, model_id, doc_confidence, duration_ms, is_current, created_at
FROM doc_azure_result WHERE doc_id = :doc_id ORDER BY id;

-- ---------------------------------------------------------------- reprocessing
-- from_stage: CLASSIFY (re-classify + extract + map) | EXTRACT (re-extract + map) | MAP (re-map only, no Azure cost)

-- One document, e.g. after fixing a mapping rule
INSERT INTO doc_reprocess_request (from_stage, doc_id, reason, requested_by)
VALUES ('MAP', :doc_id, 'routing rule fixed', 'jdoe');

-- All FAILED extractions (e.g. Azure key was wrong / model id was missing, now fixed)
INSERT INTO doc_reprocess_request (from_stage, current_status, failed_stage, reason, requested_by)
VALUES ('EXTRACT', 'FAILED', 'EXTRACT', 'model id fixed in config', 'jdoe');

-- Re-extract everything of a doc type with a new model version (set the new model id first!)
INSERT INTO doc_reprocess_request (from_stage, doc_type, current_status, reason, requested_by)
VALUES ('EXTRACT', 'AUTO_PAY_AUTH', 'REVIEW', 'autopay-neural-v2 deployed', 'jdoe');

-- Re-apply mapping/validation rules to all completed docs of a type (free - uses stored Azure JSON)
INSERT INTO doc_reprocess_request (from_stage, doc_type, current_status, reason, requested_by)
VALUES ('MAP', 'AUTO_PAY_AUTH', 'COMPLETED', 'new validation rule', 'jdoe');

-- Retry ERROR documents now instead of waiting for next_retry_at
UPDATE doc_job SET next_retry_at = CURRENT_TIMESTAMP WHERE status = 'ERROR';

-- Result of the requests
SELECT id, from_stage, doc_id, doc_type, current_status, state, applied_count, message, applied_at
FROM doc_reprocess_request ORDER BY id DESC;

-- ---------------------------------------------------------------- review / corrections
-- (a review UI should do this in one transaction; values of sensitive fields must be
--  encrypted by the application - use the app's FieldEncryptor, not plain SQL)

-- Correct a non-sensitive field
UPDATE doc_field_correction SET active = FALSE WHERE doc_id = :doc_id AND field_name = 'customerName' AND active = TRUE;
INSERT INTO doc_field_correction (doc_id, field_name, original_value, corrected_value, display_value, reason, corrected_by)
SELECT doc_id, field_name, field_value, 'JOHN Q SAMPLE', 'JOHN Q SAMPLE', 'OCR_MISREAD', 'reviewer1'
FROM doc_field WHERE doc_id = :doc_id AND field_name = 'customerName';

-- Approve the document after review
INSERT INTO doc_status_history (doc_id, from_status, to_status, stage, note, changed_by)
SELECT id, status, 'COMPLETED', 'REVIEW', 'approved after correction', 'reviewer1' FROM doc_job WHERE id = :doc_id AND status = 'REVIEW';
UPDATE doc_job SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP WHERE id = :doc_id AND status = 'REVIEW';

-- What downstream systems read
SELECT doc_id, field_name, final_value, display_value, value_source FROM v_doc_field_final WHERE doc_id = :doc_id;

-- Correction rate per field (tells you what to retrain)
SELECT j.doc_type, c.field_name, c.reason, COUNT(*) AS corrections
FROM doc_field_correction c JOIN doc_job j ON j.id = c.doc_id
WHERE c.active = TRUE GROUP BY j.doc_type, c.field_name, c.reason ORDER BY corrections DESC;

-- ---------------------------------------------------------------- housekeeping

-- Drop the full Azure JSON of old, superseded results (keep fields_json for re-mapping)
UPDATE doc_azure_result SET result_json = NULL
WHERE is_current = FALSE AND created_at < CURRENT_TIMESTAMP - INTERVAL '90' DAY;
