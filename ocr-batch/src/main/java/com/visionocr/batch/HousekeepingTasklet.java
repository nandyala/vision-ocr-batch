package com.visionocr.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Keeps the job's own tables from growing without limit. Only touches schema ocr:
 * <ul>
 *   <li>full Azure JSON older than retention.full-json-days is cleared (fields_json is kept, so
 *       re-mapping still works)</li>
 *   <li>doc_status_history / doc_error rows older than retention.history-days are deleted</li>
 *   <li>Spring Batch run history (ocr.BATCH_*) of finished runs older than retention.batch-metadata-days
 *       is deleted</li>
 * </ul>
 * Business data (doc_job, doc_field, corrections) is never deleted here. Deletes run in small batches,
 * each in its own transaction, to keep locks and log growth small. 0 days = keep forever.
 */
public class HousekeepingTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingTasklet.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int fullJsonDays;
    private final int historyDays;
    private final int batchMetadataDays;
    private final int batchSize;

    public HousekeepingTasklet(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                               int fullJsonDays, int historyDays, int batchMetadataDays, int batchSize) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.fullJsonDays = fullJsonDays;
        this.historyDays = historyDays;
        this.batchMetadataDays = batchMetadataDays;
        this.batchSize = batchSize;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        if (fullJsonDays > 0) {
            int n = inBatches("UPDATE TOP (" + batchSize + ") ocr.doc_azure_result SET result_json_gz = NULL, result_json = NULL "
                    + "WHERE created_at < ? AND (result_json_gz IS NOT NULL OR result_json IS NOT NULL)", cutoff(fullJsonDays));
            log.info("Housekeeping: cleared full Azure JSON of {} result(s) older than {} days", n, fullJsonDays);
        }
        if (historyDays > 0) {
            Timestamp c = cutoff(historyDays);
            int h = inBatches("DELETE TOP (" + batchSize + ") FROM ocr.doc_status_history WHERE changed_at < ?", c);
            int e = inBatches("DELETE TOP (" + batchSize + ") FROM ocr.doc_error WHERE created_at < ?", c);
            log.info("Housekeeping: deleted {} history and {} error row(s) older than {} days", h, e, historyDays);
        }
        if (batchMetadataDays > 0) {
            int runs = deleteOldBatchRuns(cutoff(batchMetadataDays));
            log.info("Housekeeping: deleted {} finished Spring Batch run(s) older than {} days", runs, batchMetadataDays);
        }
        return RepeatStatus.FINISHED;
    }

    /** Repeats a TOP (n) statement, each batch in its own transaction, until nothing is left. */
    private int inBatches(String sql, Timestamp cutoff) {
        int total = 0;
        while (true) {
            Integer n = tx.execute(status -> jdbc.update(sql, cutoff));
            if (n == null || n == 0) {
                return total;
            }
            total += n;
        }
    }

    /** Only finished runs (END_TIME set), children first. Running executions are never touched. */
    private int deleteOldBatchRuns(Timestamp cutoff) {
        String old = "SELECT JOB_EXECUTION_ID FROM ocr.BATCH_JOB_EXECUTION WHERE END_TIME < ?";
        Integer runs = tx.execute(status -> {
            jdbc.update("DELETE FROM ocr.BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID IN "
                    + "(SELECT STEP_EXECUTION_ID FROM ocr.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN (" + old + "))", cutoff);
            jdbc.update("DELETE FROM ocr.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN (" + old + ")", cutoff);
            jdbc.update("DELETE FROM ocr.BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID IN (" + old + ")", cutoff);
            jdbc.update("DELETE FROM ocr.BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID IN (" + old + ")", cutoff);
            int n = jdbc.update("DELETE FROM ocr.BATCH_JOB_EXECUTION WHERE END_TIME < ?", cutoff);
            jdbc.update("DELETE FROM ocr.BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID NOT IN "
                    + "(SELECT JOB_INSTANCE_ID FROM ocr.BATCH_JOB_EXECUTION)");
            return n;
        });
        return runs == null ? 0 : runs;
    }

    private static Timestamp cutoff(int days) {
        return Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
    }
}
