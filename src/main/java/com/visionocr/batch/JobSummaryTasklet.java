package com.visionocr.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/** Step 5: logs document counts per doc type and status (hook for alerts/metrics). */
public class JobSummaryTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(JobSummaryTasklet.class);

    private final JdbcTemplate jdbc;

    public JobSummaryTasklet(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT COALESCE(doc_type, '-') AS doc_type, status, COUNT(*) AS cnt FROM ocr.doc_job "
                        + "GROUP BY doc_type, status ORDER BY doc_type, status");
        log.info("===== Document summary =====");
        for (Map<String, Object> r : rows) {
            // Spring returns case-insensitive maps, so column case differences between DBs do not matter.
            log.info(String.format("%-20s %-11s %6s", r.get("doc_type"), r.get("status"), r.get("cnt")));
        }
        return RepeatStatus.FINISHED;
    }
}
