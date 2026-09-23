package com.visionocr.ui.service;

import com.visionocr.config.DocTypeRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Job runs (Spring Batch metadata in ocr.BATCH_*), errors, the retry queue and reprocess requests. */
@Service
public class OperationsService {

    private static final Set<String> STAGES = Set.of("CLASSIFY", "EXTRACT", "MAP");
    private static final Set<String> STATUSES = Set.of("CLASSIFIED", "EXTRACTED", "COMPLETED", "REVIEW", "ERROR", "FAILED");

    private final JdbcTemplate jdbc;
    private final DocTypeRegistry registry;

    public OperationsService(JdbcTemplate jdbcTemplate, DocTypeRegistry docTypeRegistry) {
        this.jdbc = jdbcTemplate;
        this.registry = docTypeRegistry;
    }

    public List<Map<String, Object>> jobRuns(int limit) {
        return jdbc.queryForList("SELECT TOP (?) e.JOB_EXECUTION_ID AS id, e.STATUS AS status, e.EXIT_CODE AS exit_code, "
                + "e.EXIT_MESSAGE AS exit_message, e.CREATE_TIME AS created_at, e.START_TIME AS start_time, e.END_TIME AS end_time, "
                + "DATEDIFF(MILLISECOND, e.START_TIME, COALESCE(e.END_TIME, SYSDATETIME())) AS duration_ms, "
                + "(SELECT SUM(s.WRITE_COUNT) FROM ocr.BATCH_STEP_EXECUTION s WHERE s.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID "
                + " AND s.STEP_NAME = 'mapValidateStep') AS documents_mapped "
                + "FROM ocr.BATCH_JOB_EXECUTION e ORDER BY e.JOB_EXECUTION_ID DESC", Math.max(1, Math.min(limit, 200)));
    }

    public List<Map<String, Object>> jobSteps(long executionId) {
        return jdbc.queryForList("SELECT STEP_EXECUTION_ID AS id, STEP_NAME AS step_name, STATUS AS status, "
                + "READ_COUNT AS read_count, WRITE_COUNT AS write_count, FILTER_COUNT AS filter_count, "
                + "COMMIT_COUNT AS commit_count, ROLLBACK_COUNT AS rollback_count, START_TIME AS start_time, END_TIME AS end_time, "
                + "DATEDIFF(MILLISECOND, START_TIME, COALESCE(END_TIME, SYSDATETIME())) AS duration_ms, EXIT_CODE AS exit_code, "
                + "LEFT(EXIT_MESSAGE, 2000) AS exit_message "
                + "FROM ocr.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ? ORDER BY STEP_EXECUTION_ID", executionId);
    }

    /** Name of the step the latest unfinished run is executing (for the live indicator), or null. */
    public String currentStep() {
        List<String> r = jdbc.queryForList("SELECT TOP 1 s.STEP_NAME FROM ocr.BATCH_STEP_EXECUTION s "
                + "JOIN ocr.BATCH_JOB_EXECUTION e ON e.JOB_EXECUTION_ID = s.JOB_EXECUTION_ID "
                + "WHERE e.END_TIME IS NULL AND s.END_TIME IS NULL ORDER BY s.STEP_EXECUTION_ID DESC", String.class);
        return r.isEmpty() ? null : r.get(0);
    }

    public Map<String, Object> queue() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("retries", jdbc.queryForList("SELECT TOP 50 id, file_name, doc_type, failed_stage, retry_count, next_retry_at, last_error "
                + "FROM ocr.doc_job WHERE status = 'ERROR' ORDER BY next_retry_at"));
        m.put("failed", jdbc.queryForList("SELECT TOP 50 id, file_name, doc_type, failed_stage, retry_count, last_error, updated_at "
                + "FROM ocr.doc_job WHERE status = 'FAILED' ORDER BY updated_at DESC"));
        m.put("inProgress", jdbc.queryForList("SELECT TOP 50 id, file_name, doc_type, status, updated_at "
                + "FROM ocr.doc_job WHERE status IN ('NEW', 'CLASSIFIED', 'EXTRACTED') ORDER BY id"));
        return m;
    }

    public Map<String, Object> errors(int days) {
        int d = Math.max(1, Math.min(days, 90));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groups", jdbc.queryForList("SELECT stage, error_class, http_status, retryable, COUNT(*) AS cnt, "
                + "COUNT(DISTINCT doc_id) AS documents, MAX(created_at) AS last_seen, MAX(error_message) AS sample_message "
                + "FROM ocr.doc_error WHERE created_at > DATEADD(DAY, ?, SYSDATETIME()) "
                + "GROUP BY stage, error_class, http_status, retryable ORDER BY cnt DESC", -d));
        m.put("recent", jdbc.queryForList("SELECT TOP 50 e.id, e.doc_id, j.file_name, e.stage, e.attempt_no, e.error_class, "
                + "e.http_status, e.retryable, e.error_message, e.created_at FROM ocr.doc_error e "
                + "JOIN ocr.doc_job j ON j.id = e.doc_id ORDER BY e.id DESC"));
        m.put("days", d);
        return m;
    }

    public List<Map<String, Object>> reprocessRequests(int limit) {
        return jdbc.queryForList("SELECT TOP (?) r.id, r.from_stage, r.doc_id, j.file_name, r.doc_type, r.current_status, "
                + "r.failed_stage, r.reason, r.requested_by, r.requested_at, r.state, r.applied_count, r.applied_at, r.message "
                + "FROM ocr.doc_reprocess_request r LEFT JOIN ocr.doc_job j ON j.id = r.doc_id ORDER BY r.id DESC",
                Math.max(1, Math.min(limit, 500)));
    }

    /** Bulk reprocess request (same rules as ops/operations.sql). Returns how many documents currently match. */
    public Map<String, Object> createReprocessRequest(String fromStage, Long docId, String docType, String currentStatus,
                                                      String failedStage, String reason, String user) {
        if (fromStage == null || !STAGES.contains(fromStage)) {
            throw new IllegalArgumentException("fromStage must be one of " + STAGES);
        }
        docType = blankToNull(docType);
        currentStatus = blankToNull(currentStatus);
        failedStage = blankToNull(failedStage);
        if (docId == null && docType == null && currentStatus == null && failedStage == null) {
            throw new IllegalArgumentException("Choose at least one filter (document, doc type, status or failed stage)");
        }
        if (docType != null && !registry.contains(docType)) {
            throw new IllegalArgumentException("Unknown doc type " + docType);
        }
        if (currentStatus != null && !STATUSES.contains(currentStatus)) {
            throw new IllegalArgumentException("Status must be one of " + STATUSES);
        }
        if (failedStage != null && !STAGES.contains(failedStage)) {
            throw new IllegalArgumentException("Failed stage must be one of " + STAGES);
        }
        StringBuilder where = new StringBuilder(" WHERE status <> 'NEW' AND COALESCE(failed_stage, '') <> 'INGEST'");
        List<Object> args = new ArrayList<>();
        filter(where, args, "id", docId);
        filter(where, args, "doc_type", docType);
        filter(where, args, "status", currentStatus);
        filter(where, args, "failed_stage", failedStage);
        Integer matching = jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_job" + where, Integer.class, args.toArray());
        jdbc.update("INSERT INTO ocr.doc_reprocess_request (from_stage, doc_id, doc_type, current_status, failed_stage, reason, "
                + "requested_by) VALUES (?, ?, ?, ?, ?, ?, ?)", fromStage, docId, docType, currentStatus, failedStage,
                reason == null || reason.isBlank() ? "requested from the demo UI" : DocumentService.trim(reason.trim(), 1000), user);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("matching", matching == null ? 0 : matching);
        return m;
    }

    /** Makes every ERROR document due now instead of waiting for its back-off. */
    public int retryNow() {
        return jdbc.update("UPDATE ocr.doc_job SET next_retry_at = SYSDATETIME() WHERE status = 'ERROR'");
    }

    private static void filter(StringBuilder where, List<Object> args, String column, Object value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
