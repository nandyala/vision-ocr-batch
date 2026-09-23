# Data model, retries and reprocessing

DDL (SQL Server): `ocr-batch/src/main/resources/schema-app-sqlserver.sql` (job tables) and `schema-batch-sqlserver.sql`
(Spring Batch tables). Everything lives in the schema `ocr`; the scripts only create missing objects
and never drop or alter anything, so they are safe to run in an existing database.
Operator queries: `ops/operations.sql`.

## Tables

```
                    ┌──────────────────────┐
                    │ doc_job              │  one row per file: CURRENT state
                    │  status, failed_stage│  (the work queue)
                    │  retry_count,        │
                    │  next_retry_at       │
                    └─────────┬────────────┘
      ┌──────────────┬────────┼─────────────┬──────────────────┬─────────────────────┐
      ▼              ▼        ▼             ▼                  ▼                     ▼
 doc_azure_result  doc_field  doc_field_    doc_status_history  doc_error       doc_reprocess_request
 every Azure call  latest     correction    every transition    every failure   operator: "re-run
 (JSON, history)   mapping    human fixes   (audit)             (attempt, http, these docs from
                                                                 retryable)      stage X"
                        └───────┬───────┘
                                ▼
                        v_doc_field_final   correction wins over model value
```

| Table | Purpose | Written by |
|---|---|---|
| `doc_job` | Current status of each document (the queue each step reads from) + `extracted_json`: all field values as one JSON object | every step |
| `doc_azure_result` | Every successful Azure call: the extracted fields (`fields_json`: name, value, OCR text, confidence, type), always kept. Azure's full AnalyzeResult (`result_json_gz`, compressed) only with `results.store-full-json=true`, cleared after `retention.full-json-days`. Rows are never overwritten; `is_current` marks the latest per operation | classify, extract |
| `doc_field` | One row per extracted field (generic for every doc type): all fields the model returned, cleaned/validated where the doc type XML has rules (`configured`). `result_id` points to the Azure result | map |
| `doc_field_correction` | Reviewer corrections (fix / confirm / undo). Keyed by field name, so they survive reprocessing. Also a source of training data | demo UI (`ocr-ui`) / SQL |
| `doc_status_history` | Append-only audit of every status change (who, when, why) | all steps, recovery |
| `doc_error` | Every failure: stage, attempt number, exception, HTTP status, retryable | writers, ingest |
| `doc_reprocess_request` | Operator requests to push documents back into the pipeline | operators |
| `v_doc_field_final` | What downstream systems read | view |
| `ocr.v_doc_<doctype>` | Generated per doc type at each run (e.g. `ocr.v_doc_auto_pay_auth`): one row per document, one column per field listed in the doc type XML (`viewColumns` or `fields`) | viewStep |

## Status lifecycle

```
          ┌───────────── recoveryStep (auto retry / reprocess request) ─────────────┐
          ▼                                                                         │
NEW ──classify──► CLASSIFIED ──extract──► EXTRACTED ──map──► COMPLETED               │
 │                    │                      │          └──► REVIEW ──(approve)──► COMPLETED
 │                    │                      │
 └────────────────────┴──────────────────────┴──► ERROR   retryable, retry_count < max ─┘
                                                  FAILED  not retryable / retries exhausted
                                                          → needs a doc_reprocess_request
```

## Automatic retry (no operator involved)

1. A stage fails. The writer stores a `doc_error` row and asks `RetryPolicy`:
   * **Retryable:** HTTP 408/429/5xx, network errors, timeouts, or an Azure operation still running
     at the timeout. The document goes to `ERROR` with `next_retry_at = now + 5 min × 2^(attempt-1)`,
     capped at 4 h.
   * **Not retryable:** other HTTP 4xx (corrupt file, unknown model, auth), config and mapping errors.
     The document goes to `FAILED`.
   * When `retry_count` reaches `retry.max-attempts` (default 4), the document goes to `FAILED`.
2. On the next job run, `recoveryStep` moves every `ERROR` document whose `next_retry_at` has passed
   back to the entry status of the stage that failed:
   CLASSIFY → `NEW`, EXTRACT → `CLASSIFIED`, MAP → `EXTRACTED`.
   Only that stage and the ones after it run again. Earlier results (e.g. the classification) are reused.
3. On success, `retry_count` / `failed_stage` / `last_error` are cleared.

Tip: schedule the job frequently (e.g. every 15 min). Each run picks up new files and due retries.

## Reprocessing (operator driven)

Insert a row into `doc_reprocess_request`. The next run applies it, records `REPROCESS` in the
history, and marks the request `APPLIED` with the number of documents affected.

| `from_stage` | Resets to | Re-runs | Azure cost |
|---|---|---|---|
| `CLASSIFY` | NEW | classify + extract + map | yes |
| `EXTRACT` | CLASSIFIED | extract (with the model id currently configured) + map | yes |
| `MAP` | EXTRACTED | normalize/validate using the stored `fields_json` | **none** |

Target a single `doc_id`, or any combination of `doc_type`, `current_status` and `failed_stage`.
At least one filter is required; otherwise the request is `REJECTED`. Documents that failed at INGEST
(empty or oversized file) are never reset, because the file itself is the problem.

Typical cases:

* **Mapping bug or new validation rule** → `MAP` (free).
* **Azure outage, key expired, model id wrong** → fix it, then `EXTRACT` for `current_status=FAILED`
  and `failed_stage=EXTRACT`.
* **New model version** → change `doctype.<type>.model-id`, then `EXTRACT` for that doc type. The old
  results stay in `doc_azure_result`, so you can compare the two versions.
* **New classifier / wrong doc type** → `CLASSIFY`.

Corrections in `doc_field_correction` are kept on reprocessing. The human value keeps winning in
`v_doc_field_final`. Set `active = FALSE` on a correction if the new model result should be used instead.

## Consistency

All writes for a chunk (status update, Azure JSON, error, history, fields) go through
`DocumentRepository` inside the chunk transaction, so they commit or roll back together. If the job
crashes mid-chunk, those documents keep their previous status and are processed again on the next run.

## Volume and retention

* **Parallelism:** classify/extract run `batch.threads` partitions (`id % threads`); readers use `READPAST`
  so partitions never wait on each other's rows.
* **Compression:** the full Azure response is stored with `COMPRESS()` (typically 5-10x smaller); the large
  tables (`doc_field`, `doc_status_history`, `doc_error`, `doc_azure_result`) use PAGE compression.
* **Batched writes:** all fields of a chunk are inserted with one batched statement.
* **Retention (housekeepingStep, every run):** full Azure JSON after `retention.full-json-days`, history and
  error rows after `retention.history-days`, finished Spring Batch runs after `retention.batch-metadata-days`.
  Deletes run in batches of `retention.delete-batch-size`, each in its own transaction. Business data
  (`doc_job`, `doc_field`, corrections) is never deleted by the job.
* **Indexes** cover the queue (status), retries, doc type/date, value lookups, every foreign key and the
  retention date columns.
