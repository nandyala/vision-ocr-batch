package com.visionocr.batch;

import com.visionocr.domain.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * First step of every run. Puts documents back into the pipeline:
 * <ol>
 *   <li><b>Reprocess requests</b> - applies PENDING rows of DOC_REPROCESS_REQUEST (operator driven):
 *       CLASSIFY -> NEW, EXTRACT -> CLASSIFIED, MAP -> EXTRACTED.</li>
 *   <li><b>Automatic retries</b> - ERROR documents whose next_retry_at has passed go back to the
 *       entry status of the stage that failed.</li>
 * </ol>
 * Every change is written to DOC_STATUS_HISTORY. Runs in the step's transaction.
 */
public class RecoveryTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(RecoveryTasklet.class);

    private static final String ENTRY_STATUS_CASE = "CASE failed_stage WHEN 'CLASSIFY' THEN 'NEW' "
            + "WHEN 'EXTRACT' THEN 'CLASSIFIED' WHEN 'MAP' THEN 'EXTRACTED' END";

    private final JdbcTemplate jdbc;

    public RecoveryTasklet(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        applyReprocessRequests();
        scheduleRetries();
        return RepeatStatus.FINISHED;
    }

    // ------------------------------------------------------------------ reprocess requests

    void applyReprocessRequests() {
        List<Map<String, Object>> requests = jdbc.queryForList(
                "SELECT * FROM doc_reprocess_request WHERE state = 'PENDING' ORDER BY id");
        for (Map<String, Object> r : requests) {
            long requestId = ((Number) r.get("id")).longValue();
            Stage stage = Stage.valueOf((String) r.get("from_stage"));

            List<Object> args = new ArrayList<>();
            StringBuilder where = new StringBuilder(" WHERE status NOT IN ('NEW') AND COALESCE(failed_stage, '') <> 'INGEST'");
            boolean hasFilter = false;
            hasFilter |= addFilter(where, args, "id", r.get("doc_id"));
            hasFilter |= addFilter(where, args, "doc_type", r.get("doc_type"));
            hasFilter |= addFilter(where, args, "status", r.get("current_status"));
            hasFilter |= addFilter(where, args, "failed_stage", r.get("failed_stage"));
            if (!hasFilter) {
                reject(requestId, "At least one of doc_id, doc_type, current_status, failed_stage is required");
                continue;
            }
            // Only documents that have what the stage needs.
            if (stage == Stage.EXTRACT) {
                where.append(" AND doc_type IS NOT NULL");
            } else if (stage == Stage.MAP) {
                where.append(" AND doc_type IS NOT NULL AND EXISTS (SELECT 1 FROM doc_azure_result a "
                        + "WHERE a.doc_id = doc_job.id AND a.operation = 'EXTRACT' AND a.is_current = TRUE)");
            }
            String target = stage.entryStatus().name();
            String note = "request #" + requestId + " by " + r.get("requested_by")
                    + (r.get("reason") == null ? "" : ": " + r.get("reason"));

            List<Object> historyArgs = new ArrayList<>();
            historyArgs.add(target);
            historyArgs.add(note.length() > 1990 ? note.substring(0, 1990) : note);
            historyArgs.addAll(args);
            jdbc.update("INSERT INTO doc_status_history (doc_id, from_status, to_status, stage, note, changed_by) "
                    + "SELECT id, status, ?, 'REPROCESS', ?, 'reprocess' FROM doc_job" + where, historyArgs.toArray());
            jdbc.update("DELETE FROM doc_field WHERE doc_id IN (SELECT id FROM doc_job" + where + ")", args.toArray());

            String reset = "UPDATE doc_job SET status = ?, failed_stage = NULL, retry_count = 0, next_retry_at = NULL, "
                    + "last_error = NULL, review_reasons = NULL, updated_at = CURRENT_TIMESTAMP";
            if (stage == Stage.CLASSIFY) {
                // Re-classify from scratch unless the doc type came from the input folder.
                reset += ", doc_type = CASE WHEN classifier_label = '" + ClassifyProcessor.FOLDER_HINT
                        + "' THEN doc_type ELSE NULL END, classifier_label = NULL, classify_confidence = NULL, pages = NULL";
            }
            List<Object> updateArgs = new ArrayList<>();
            updateArgs.add(target);
            updateArgs.addAll(args);
            int count = jdbc.update(reset + where, updateArgs.toArray());

            jdbc.update("UPDATE doc_reprocess_request SET state = 'APPLIED', applied_count = ?, applied_at = CURRENT_TIMESTAMP, "
                    + "message = ? WHERE id = ?", count, count + " document(s) reset to " + target, requestId);
            log.info("Reprocess request #{}: {} document(s) reset to {} (from stage {})", requestId, count, target, stage);
        }
    }

    private static boolean addFilter(StringBuilder where, List<Object> args, String column, Object value) {
        if (value == null || (value instanceof String && ((String) value).isBlank())) {
            return false;
        }
        where.append(" AND ").append(column).append(" = ?");
        args.add(value);
        return true;
    }

    private void reject(long requestId, String message) {
        jdbc.update("UPDATE doc_reprocess_request SET state = 'REJECTED', applied_at = CURRENT_TIMESTAMP, message = ? "
                + "WHERE id = ?", message, requestId);
        log.warn("Reprocess request #{} rejected: {}", requestId, message);
    }

    // ------------------------------------------------------------------ automatic retries

    void scheduleRetries() {
        Timestamp now = Timestamp.from(Instant.now());
        String due = " FROM doc_job WHERE status = 'ERROR' AND next_retry_at <= ? "
                + "AND failed_stage IN ('CLASSIFY', 'EXTRACT', 'MAP')";
        jdbc.update("INSERT INTO doc_status_history (doc_id, from_status, to_status, stage, note, changed_by) "
                + "SELECT id, status, " + ENTRY_STATUS_CASE + ", 'RETRY', 'automatic retry after ' || retry_count "
                + "|| ' failure(s) in ' || failed_stage, 'retry'" + due, now);
        int count = jdbc.update("UPDATE doc_job SET status = " + ENTRY_STATUS_CASE + ", next_retry_at = NULL, "
                + "updated_at = CURRENT_TIMESTAMP WHERE status = 'ERROR' AND next_retry_at <= ? "
                + "AND failed_stage IN ('CLASSIFY', 'EXTRACT', 'MAP')", now);
        Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM doc_job WHERE status = 'ERROR'", Integer.class);
        log.info("Retry: {} document(s) re-queued, {} still waiting for their retry time", count, waiting);
    }
}
