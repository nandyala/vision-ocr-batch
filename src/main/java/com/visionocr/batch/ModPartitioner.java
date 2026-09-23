package com.visionocr.batch;

import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Splits the classify/extract work into batch.threads slices by id % gridSize, processed in parallel.
 * Each document belongs to exactly one slice, so no document is processed twice.
 */
public class ModPartitioner implements Partitioner {

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> map = new HashMap<>();
        for (int i = 0; i < gridSize; i++) {
            ExecutionContext ctx = new ExecutionContext();
            ctx.putInt("partition", i);
            ctx.putInt("gridSize", gridSize);
            map.put("partition" + i, ctx);
        }
        return map;
    }
}
