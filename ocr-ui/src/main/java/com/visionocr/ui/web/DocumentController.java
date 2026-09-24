package com.visionocr.ui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.visionocr.ui.job.JobTrigger;
import com.visionocr.ui.service.DocumentService;
import com.visionocr.ui.support.Reviewer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Documents: search, detail, preview, upload and the review actions (corrections, approve, reopen, reprocess). */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documents;
    private final JobTrigger jobTrigger;
    private final boolean runJobOnUpload;

    public DocumentController(DocumentService documents, JobTrigger jobTrigger,
                              @Value("${ui.run-job-on-upload:true}") boolean runJobOnUpload) {
        this.documents = documents;
        this.jobTrigger = jobTrigger;
        this.runJobOnUpload = runJobOnUpload;
    }

    @GetMapping("/documents")
    public Map<String, Object> search(@RequestParam(name = "status", required = false) String status,
                                      @RequestParam(name = "docType", required = false) String docType,
                                      @RequestParam(name = "q", required = false) String q,
                                      @RequestParam(name = "from", required = false) String from,
                                      @RequestParam(name = "to", required = false) String to,
                                      @RequestParam(name = "corrected", required = false) Boolean corrected,
                                      @RequestParam(name = "sort", defaultValue = "id") String sort,
                                      @RequestParam(name = "dir", defaultValue = "desc") String dir,
                                      @RequestParam(name = "page", defaultValue = "0") int page,
                                      @RequestParam(name = "size", defaultValue = "25") int size) {
        return documents.search(status, docType, q, from, to, corrected, sort, dir, page, size);
    }

    @GetMapping("/review-queue")
    public List<Map<String, Object>> reviewQueue(@RequestParam(name = "limit", defaultValue = "200") int limit) {
        return documents.reviewQueue(limit);
    }

    @GetMapping("/documents/{id}")
    public Map<String, Object> detail(@PathVariable("id") long id) {
        return documents.detail(id);
    }

    @GetMapping("/documents/{id}/pages")
    public Map<String, Object> pages(@PathVariable("id") long id) throws IOException {
        return Map.of("pages", documents.pages(id));
    }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable("id") long id, @RequestParam(name = "page", defaultValue = "0") int page) throws IOException {
        DocumentService.FileContent f = documents.file(id, page);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .body(f.bytes());
    }

    @GetMapping("/documents/{id}/azure/{resultId}")
    public JsonNode azureResult(@PathVariable("id") long id, @PathVariable("resultId") long resultId,
                                @RequestParam(name = "full", defaultValue = "false") boolean full) {
        return documents.azureResult(id, resultId, full);
    }

    @GetMapping("/documents/{id}/corrections")
    public List<Map<String, Object>> correctionHistory(@PathVariable("id") long id, @RequestParam(name = "field") String field) {
        return documents.correctionHistory(id, field);
    }

    @PostMapping("/documents/{id}/corrections")
    public Map<String, Object> correct(@PathVariable("id") long id, @RequestBody Map<String, String> body,
                                       @RequestHeader(value = Reviewer.HEADER, required = false) String user) {
        documents.correct(id, body.get("field"), body.get("value"), body.get("reason"), body.get("comment"), Reviewer.of(user));
        return documents.detail(id);
    }

    @PostMapping("/documents/{id}/corrections/revert")
    public Map<String, Object> revert(@PathVariable("id") long id, @RequestBody Map<String, String> body,
                                      @RequestHeader(value = Reviewer.HEADER, required = false) String user) {
        documents.revertCorrection(id, body.get("field"), Reviewer.of(user));
        return documents.detail(id);
    }

    @PostMapping("/documents/{id}/approve")
    public Map<String, Object> approve(@PathVariable("id") long id, @RequestBody(required = false) Map<String, String> body,
                                       @RequestHeader(value = Reviewer.HEADER, required = false) String user) {
        documents.approve(id, body == null ? null : body.get("note"), Reviewer.of(user));
        return documents.detail(id);
    }

    @PostMapping("/documents/{id}/reopen")
    public Map<String, Object> reopen(@PathVariable("id") long id, @RequestBody(required = false) Map<String, String> body,
                                      @RequestHeader(value = Reviewer.HEADER, required = false) String user) {
        documents.reopen(id, body == null ? null : body.get("note"), Reviewer.of(user));
        return documents.detail(id);
    }

    @PostMapping("/documents/{id}/reprocess")
    public Map<String, Object> reprocess(@PathVariable("id") long id, @RequestBody Map<String, String> body,
                                         @RequestHeader(value = Reviewer.HEADER, required = false) String user) {
        documents.reprocess(id, body.get("fromStage"), body.get("reason"), Reviewer.of(user));
        jobTrigger.trigger();
        return documents.detail(id);
    }

    /** Multipart upload of one or more files. docType = AUTO (classifier / default) or a doc type name. */
    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("files") List<MultipartFile> files,
                                      @RequestParam(name = "docType", defaultValue = "AUTO") String docType) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        boolean any = false;
        for (MultipartFile f : files) {
            try {
                Map<String, Object> r = documents.upload(f.getOriginalFilename(), f.getBytes(), docType);
                any |= !Boolean.TRUE.equals(r.get("existing"));
                results.add(r);
            } catch (IllegalArgumentException e) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("originalName", f.getOriginalFilename());
                r.put("error", e.getMessage());
                results.add(r);
            }
        }
        if (any && runJobOnUpload) {
            jobTrigger.trigger();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("files", results);
        m.put("jobStarted", any && runJobOnUpload);
        return m;
    }

    @PostMapping("/uploads/status")
    public List<Map<String, Object>> uploadStatus(@RequestBody Map<String, List<String>> body) {
        return documents.byHashes(body.getOrDefault("hashes", List.of()));
    }
}
