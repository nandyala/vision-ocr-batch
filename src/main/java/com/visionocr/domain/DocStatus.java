package com.visionocr.domain;

/**
 * Lifecycle of a document in DOC_JOB. Each step reads documents in one status and moves them
 * forward, so re-running the job simply resumes where it stopped.
 *
 * <pre>
 * NEW -> CLASSIFIED -> EXTRACTED -> COMPLETED
 *   \-> REVIEW (human in the loop)  \-> REVIEW
 *
 * any stage failure -> ERROR  (retryable: RecoveryTasklet resets it at next_retry_at)
 *                   -> FAILED (not retryable / retries exhausted: needs a doc_reprocess_request)
 * </pre>
 */
public enum DocStatus {
    NEW,
    CLASSIFIED,
    EXTRACTED,
    COMPLETED,
    REVIEW,
    ERROR,
    FAILED
}
