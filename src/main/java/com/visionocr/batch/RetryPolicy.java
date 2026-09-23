package com.visionocr.batch;

import com.azure.core.exception.HttpResponseException;
import com.visionocr.azure.DocIntelException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

/**
 * Decides whether a failure is worth retrying and when.
 * <ul>
 *   <li>Retryable: HTTP 408/429/5xx, network errors, timeouts, Azure operation still running.</li>
 *   <li>Not retryable: HTTP 4xx (bad/corrupt document, unknown model, auth), config and mapping bugs.
 *       Those go to FAILED; after fixing the cause, insert a doc_reprocess_request.</li>
 * </ul>
 * Backoff: baseDelay * 2^(attempt-1), capped at maxDelay. After maxAttempts consecutive failures -> FAILED.
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    public RetryPolicy(int maxAttempts, int baseDelayMinutes, int maxDelayMinutes) {
        this.maxAttempts = maxAttempts;
        this.baseDelay = Duration.ofMinutes(baseDelayMinutes);
        this.maxDelay = Duration.ofMinutes(maxDelayMinutes);
    }

    public boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof DocIntelException) {
                if (((DocIntelException) t).isRetryable()) {
                    return true;
                }
            } else if (t instanceof HttpResponseException) {
                Integer code = httpStatus(t);
                return code == null || code == 408 || code == 429 || code >= 500;
            } else if (t instanceof IOException || t instanceof TimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** HTTP status of the first HTTP error in the cause chain, if any. */
    public Integer httpStatus(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpResponseException) {
                HttpResponseException h = (HttpResponseException) t;
                return h.getResponse() == null ? null : h.getResponse().getStatusCode();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    public boolean canRetry(int failuresSoFar) {
        return failuresSoFar < maxAttempts;
    }

    public Instant nextRetryAt(int failuresSoFar) {
        long factor = 1L << Math.min(Math.max(failuresSoFar - 1, 0), 20);
        Duration delay = baseDelay.multipliedBy(factor);
        if (delay.compareTo(maxDelay) > 0) {
            delay = maxDelay;
        }
        return Instant.now().plus(delay);
    }
}
