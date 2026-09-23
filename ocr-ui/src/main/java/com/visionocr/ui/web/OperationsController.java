package com.visionocr.ui.web;

import com.visionocr.ui.job.JobTrigger;
import com.visionocr.ui.service.OperationsService;
import com.visionocr.ui.support.Reviewer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Job runs, the live job state, the retry/failed queue, errors and reprocess requests. */
@RestController
@RequestMapping("/api")
public class OperationsController {

    private final OperationsService ops;
    private final JobTrigger jobTrigger;

    public OperationsController(OperationsService ops, JobTrigger jobTrigger) {
        this.ops = ops;
        this.jobTrigger = jobTrigger;
    }

    @GetMapping("/job")
    public Map<String, Object> job() {
        Map<String, Object> m = new LinkedHashMap<>(jobTrigger.state());
        m.put("currentStep", jobTrigger.isRunning() ? ops.currentStep() : null);
        return m;
    }

    @PostMapping("/job/run")
    public Map<String, Object> run() {
        jobTrigger.trigger();
        return job();
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "25") int limit) {
        return ops.jobRuns(limit);
    }

    @GetMapping("/jobs/{id}/steps")
    public List<Map<String, Object>> steps(@PathVariable long id) {
        return ops.jobSteps(id);
    }

    @GetMapping("/queue")
    public Map<String, Object> queue() {
        return ops.queue();
    }

    @PostMapping("/queue/retry-now")
    public Map<String, Object> retryNow() {
        int n = ops.retryNow();
        if (n > 0) {
            jobTrigger.trigger();
        }
        return Map.of("documents", n);
    }

    @GetMapping("/errors")
    public Map<String, Object> errors(@RequestParam(defaultValue = "7") int days) {
        return ops.errors(days);
    }

    @GetMapping("/reprocess-requests")
    public List<Map<String, Object>> reprocessRequests(@RequestParam(defaultValue = "50") int limit) {
        return ops.reprocessRequests(limit);
    }

    @PostMapping("/reprocess-requests")
    public Map<String, Object> createReprocessRequest(@RequestBody Map<String, Object> body,
                                                      @RequestHeader(value = Reviewer.HEADER, required = false) String user) {
        Object docId = body.get("docId");
        Map<String, Object> r = ops.createReprocessRequest(str(body.get("fromStage")),
                docId == null || String.valueOf(docId).isBlank() ? null : Long.valueOf(String.valueOf(docId).replace("#", "").trim()),
                str(body.get("docType")), str(body.get("currentStatus")), str(body.get("failedStage")), str(body.get("reason")),
                Reviewer.of(user));
        if (Boolean.TRUE.equals(body.get("runNow"))) {
            jobTrigger.trigger();
        }
        return r;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
