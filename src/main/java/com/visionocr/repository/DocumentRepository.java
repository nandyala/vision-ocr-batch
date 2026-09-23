package com.visionocr.repository;

import com.visionocr.batch.RetryPolicy;
import com.visionocr.domain.AzureCallResult;
import com.visionocr.domain.DocStatus;
import com.visionocr.domain.DocumentRecord;
import com.visionocr.domain.MappedField;
import com.visionocr.domain.Stage;
import com.visionocr.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All writes of pipeline state. Called from ItemWriters, so everything for a chunk
 * (status, Azure JSON, errors, history, fields) commits or rolls back together.
 */
public class DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentRepository.class);

    private static final String UPDATE_JOB = "UPDATE ocr.doc_job SET status = ?, doc_type = ?, classifier_label = ?, "
            + "classify_confidence = ?, pages = ?, model_id = ?, doc_confidence = ?, review_reasons = ?, "
            + "failed_stage = ?, retry_count = ?, next_retry_at = ?, last_error = ?, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final RetryPolicy retryPolicy;
    private final boolean storeFullJson;

    public DocumentRepository(JdbcTemplate jdbc, RetryPolicy retryPolicy, boolean storeFullJson) {
        this.jdbc = jdbc;
        this.retryPolicy = retryPolicy;
        this.storeFullJson = storeFullJson;
    }

    /**
     * Persists the outcome of one stage for one document:
     * Azure result (if any), error (if any, deciding ERROR vs FAILED), new status, history entry.
     */
    public void saveOutcome(DocumentRecord d, Stage stage) {
        String note;
        if (d.getPendingError() != null) {
            note = recordFailure(d, stage);
        } else {
            d.setFailedStage(null);
            d.setRetryCount(0);
            d.setNextRetryAt(null);
            d.setLastError(null);
            if (d.getPendingResult() != null) {
                insertResult(d.getId(), d.getPendingResult());
            }
            note = d.reviewReasonsAsString();
        }
        jdbc.update(UPDATE_JOB, d.getStatus().name(), d.getDocType(), d.getClassifierLabel(), d.getClassifyConfidence(),
                d.getPages(), d.getModelId(), d.getDocConfidence(), d.reviewReasonsAsString(),
                d.getFailedStage(), d.getRetryCount(),
                d.getNextRetryAt() == null ? null : Timestamp.from(d.getNextRetryAt()),
                d.getLastError(), d.getId());
        insertHistory(d.getId(), d.getOriginalStatus(), d.getStatus(), stage.name(), note);
    }

    private String recordFailure(DocumentRecord d, Stage stage) {
        Exception e = d.getPendingError();
        boolean retryable = retryPolicy.isRetryable(e);
        int failures = d.getRetryCount() + 1;
        String message = truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 1900);

        d.setFailedStage(stage.name());
        d.setRetryCount(failures);
        d.setLastError(stage.name() + ": " + message);
        if (retryable && retryPolicy.canRetry(failures)) {
            d.setStatus(DocStatus.ERROR);
            d.setNextRetryAt(retryPolicy.nextRetryAt(failures));
        } else {
            d.setStatus(DocStatus.FAILED);
            d.setNextRetryAt(null);
        }
        jdbc.update("INSERT INTO ocr.doc_error (doc_id, stage, attempt_no, error_class, error_message, http_status, retryable) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                d.getId(), stage.name(), failures, e.getClass().getName(), message, retryPolicy.httpStatus(e), retryable);
        log.warn("{} {} failed (attempt {}, retryable={}) -> {}{}", d, stage, failures, retryable, d.getStatus(),
                d.getNextRetryAt() == null ? "" : " retry at " + d.getNextRetryAt());
        return d.getLastError();
    }

    public void insertResult(long docId, AzureCallResult r) {
        // Booleans are bound as parameters: SQL Server has no TRUE/FALSE literals (BIT columns)
        jdbc.update("UPDATE ocr.doc_azure_result SET is_current = ? WHERE doc_id = ? AND operation = ? AND is_current = ?",
                false, docId, r.getOperation(), true);
        jdbc.update("INSERT INTO ocr.doc_azure_result (doc_id, operation, model_id, api_version, doc_confidence, "
                        + "result_json, fields_json, duration_ms, is_current) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                docId, r.getOperation(), r.getModelId(), AzureCallResult.API_VERSION, r.getDocConfidence(),
                storeFullJson ? r.getResultJson() : null, r.getFieldsJson(), r.getDurationMs(), true);
    }

    public void insertHistory(long docId, DocStatus from, DocStatus to, String stage, String note) {
        jdbc.update("INSERT INTO ocr.doc_status_history (doc_id, from_status, to_status, stage, note) VALUES (?, ?, ?, ?, ?)",
                docId, from == null ? null : from.name(), to.name(), stage, truncate(note, 1990));
    }

    /** Current extraction (id + simplified fields JSON), or null if the document was never extracted. */
    public CurrentExtraction currentExtraction(long docId) {
        List<CurrentExtraction> rows = jdbc.query(
                "SELECT id, fields_json FROM ocr.doc_azure_result WHERE doc_id = ? AND operation = 'EXTRACT' AND is_current = ?",
                (rs, n) -> new CurrentExtraction(rs.getLong("id"), rs.getString("fields_json")),
                docId, true);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Replaces the extracted fields of a document: one DOC_FIELD row per field, plus
     * DOC_JOB.EXTRACTED_JSON = {"fieldName": "value", ...} for easy consumption.
     */
    public void replaceFields(DocumentRecord d, Long resultId, List<MappedField> fields) {
        jdbc.update("DELETE FROM ocr.doc_field WHERE doc_id = ?", d.getId());
        Map<String, String> json = new LinkedHashMap<>();
        for (MappedField f : fields) {
            jdbc.update("INSERT INTO ocr.doc_field (doc_id, result_id, doc_type, field_name, azure_field, field_value, "
                            + "raw_value, confidence, field_status, message, configured) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    d.getId(), resultId, d.getDocType(), truncate(f.getName(), 200), truncate(f.getAzureField(), 200),
                    truncate(f.getValue(), 4000), truncate(f.getRawValue(), 4000), f.getConfidence(),
                    f.getStatus().name(), truncate(f.getMessage(), 500), f.isConfigured());
            json.put(f.getName(), f.getValue());
        }
        jdbc.update("UPDATE ocr.doc_job SET extracted_json = ? WHERE id = ?", Json.write(json), d.getId());
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Pointer to the current EXTRACT result of a document. */
    public static class CurrentExtraction {
        private final long resultId;
        private final String fieldsJson;

        public CurrentExtraction(long resultId, String fieldsJson) {
            this.resultId = resultId;
            this.fieldsJson = fieldsJson;
        }

        public long getResultId() { return resultId; }
        public String getFieldsJson() { return fieldsJson; }
    }
}
