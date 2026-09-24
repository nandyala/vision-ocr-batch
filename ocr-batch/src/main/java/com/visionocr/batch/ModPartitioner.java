package com.visionocr.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits the classify/extract work into up to batch.threads slices by id % gridSize, processed in parallel.
 * Each document belongs to exactly one slice, so no document is processed twice.
 * <p>
 * Only slices that actually have documents waiting (status = {@code status}) are created: one uploaded document
 * gives one partition, 50 documents use all threads. With nothing waiting, a single (empty) partition runs so the
 * step still completes normally.
 */
public class ModPartitioner implements Partitioner {

    private static final Logger log = LoggerFactory.getLogger(ModPartitioner.class);

    private final JdbcTemplate jdbc;
    private final String status;

    /**
     * @param status the status the worker step reads (NEW for classify, CLASSIFIED for extract)
     */
    public ModPartitioner(JdbcTemplate jdbc, String status) {
        this.jdbc = jdbc;
        this.status = status;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        int grid = Math.max(1, gridSize);
        List<Integer> used = jdbc.queryForList(
                "SELECT DISTINCT CAST(id % ? AS INT) FROM ocr.doc_job WHERE status = ?", Integer.class, grid, status);
        if (used.isEmpty()) {
            used = List.of(0);
        }
        Map<String, ExecutionContext> map = new LinkedHashMap<>();
        for (int p : used.stream().sorted().toList()) {
            ExecutionContext ctx = new ExecutionContext();
            ctx.putInt("partition", p);
            ctx.putInt("gridSize", grid);   // the readers select id % gridSize = partition
            map.put("partition" + p, ctx);
        }
        log.debug("{} documents: {} partition(s) of {}", status, map.size(), grid);
        return map;
    }
}
