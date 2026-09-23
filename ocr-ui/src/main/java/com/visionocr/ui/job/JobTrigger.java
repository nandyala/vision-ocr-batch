package com.visionocr.ui.job;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the batch job (docExtractionJob from ocr-batch) in the background.
 * Only one run at a time; a trigger that arrives during a run schedules exactly one more run afterwards,
 * so an upload is never missed.
 */
@Component
public class JobTrigger {

    private static final Logger log = LoggerFactory.getLogger(JobTrigger.class);

    private final JobLauncher launcher;
    private final Job job;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ui-job-runner");
        t.setDaemon(true);
        return t;
    });

    private volatile String lastStatus = "IDLE";
    private volatile Instant lastStart;
    private volatile Instant lastEnd;
    private volatile String lastError;
    private volatile Long lastExecutionId;

    public JobTrigger(JobLauncher jobLauncher, @Qualifier("docExtractionJob") Job job) {
        this.launcher = jobLauncher;
        this.job = job;
    }

    public void trigger() {
        pending.set(true);
        if (running.compareAndSet(false, true)) {
            executor.submit(this::drain);
        }
    }

    private void drain() {
        try {
            while (pending.getAndSet(false)) {
                runOnce();
            }
        } finally {
            running.set(false);
            if (pending.get() && running.compareAndSet(false, true)) {
                executor.submit(this::drain);
            }
        }
    }

    private void runOnce() {
        lastStart = Instant.now();
        lastStatus = "RUNNING";
        lastError = null;
        try {
            JobExecution exec = launcher.run(job,
                    new JobParametersBuilder().addLong("run.ts", System.currentTimeMillis()).toJobParameters());
            lastExecutionId = exec.getId();
            lastStatus = String.valueOf(exec.getStatus());
            if (!exec.getAllFailureExceptions().isEmpty()) {
                lastError = String.valueOf(exec.getAllFailureExceptions().get(0).getMessage());
            }
        } catch (Exception e) {
            log.error("Job run failed", e);
            lastStatus = "FAILED";
            lastError = e.getMessage();
        } finally {
            lastEnd = Instant.now();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running.get());
        m.put("queued", pending.get());
        m.put("lastStatus", lastStatus);
        m.put("lastStart", lastStart == null ? null : lastStart.toString());
        m.put("lastEnd", lastEnd == null ? null : lastEnd.toString());
        m.put("lastError", lastError);
        m.put("lastExecutionId", lastExecutionId);
        return m;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
