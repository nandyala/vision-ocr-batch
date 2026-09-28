package com.visionocr.ui.api;

import com.visionocr.ui.job.JobTrigger;
import com.visionocr.ui.service.ConfigService;
import com.visionocr.ui.service.DocumentService;
import com.visionocr.ui.support.NotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API for external systems (see README "External REST API"). Asynchronous: POST a file, get a ref
 * (the file's SHA-256), poll GET /documents/{ref} until "final" is true. Auth: {@link ApiAccessFilter}.
 */
@RestController
@RequestMapping("/api/v1")
public class ExternalApiController {

    private final DocumentService documents;
    private final ConfigService config;
    private final JobTrigger jobTrigger;
    // ponytail: in memory - after a restart, a file not yet picked up by the job answers 404 until it is
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    public ExternalApiController(DocumentService documents, ConfigService config, JobTrigger jobTrigger) {
        this.documents = documents;
        this.config = config;
        this.jobTrigger = jobTrigger;
    }

    /** 202 for a new document, 200 when the same file was sent before (safe to retry). */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> submit(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(name = "docType", defaultValue = "AUTO") String docType,
                                                      @RequestAttribute(ApiAccessFilter.CLIENT) String client) throws IOException {
        String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename().replaceAll(".*[/\\\\]", "");
        // the sending system is part of the stored file name, so the UI shows where a document came from
        Map<String, Object> r = documents.upload("api-" + client + "_" + name, file.getBytes(), docType);
        String ref = (String) r.get("hash");
        boolean existing = Boolean.TRUE.equals(r.get("existing"));
        if (!existing) {
            queued.add(ref);
            jobTrigger.trigger();
        }
        return respond(existing ? HttpStatus.OK : HttpStatus.ACCEPTED, ref);
    }

    /** ref = the SHA-256 returned by POST, or the document id. */
    @GetMapping("/documents/{ref}")
    public ResponseEntity<Map<String, Object>> document(@PathVariable("ref") String ref) {
        return respond(HttpStatus.OK, ref.toLowerCase(Locale.ROOT));
    }

    @GetMapping("/doc-types")
    public List<Map<String, Object>> docTypes() {
        return config.docTypes();
    }

    private ResponseEntity<Map<String, Object>> respond(HttpStatus status, String ref) {
        Map<String, Object> body = result(ref);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status)
                .header(HttpHeaders.LOCATION, "/api/v1/documents/" + body.get("ref"));
        if (!Boolean.TRUE.equals(body.get("final"))) {
            b.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return b.body(body);
    }

    private Map<String, Object> result(String ref) {
        Long id = null;
        if (ref.matches("\\d{1,18}")) {
            id = Long.parseLong(ref);
        } else if (ref.matches("[0-9a-f]{64}")) {
            List<Map<String, Object>> rows = documents.byHashes(List.of(ref));
            if (!rows.isEmpty()) {
                id = ((Number) rows.get(0).get("id")).longValue();
            } else if (queued.contains(ref)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ref", ref);
                m.put("documentId", null);
                m.put("status", "QUEUED");
                m.put("final", false);
                return m;
            }
        }
        if (id == null) {
            throw new NotFoundException("Unknown document " + ref);
        }
        return toResult(documents.detail(id));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toResult(Map<String, Object> detail) {
        Map<String, Object> doc = (Map<String, Object>) detail.get("document");
        String status = external((String) doc.get("status"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", doc.get("file_hash"));
        m.put("documentId", doc.get("id"));
        m.put("status", status);
        m.put("final", status.equals("COMPLETED") || status.equals("FAILED"));
        m.put("docType", doc.get("doc_type"));
        m.put("confidence", doc.get("doc_confidence"));
        String reasons = (String) doc.get("review_reasons");
        m.put("reviewReasons", reasons == null ? List.of()
                : Arrays.stream(reasons.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) detail.get("fields")) {
            boolean reviewed = f.get("correction_id") != null;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("value", reviewed ? f.get("corrected_value") : f.get("field_value"));
            v.put("confidence", f.get("confidence"));
            v.put("status", f.get("field_status"));
            v.put("reviewed", reviewed);                 // a reviewer confirmed or corrected it
            v.put("corrected", reviewed && !"CONFIRMED".equals(f.get("correction_reason")));
            fields.put((String) f.get("field_name"), v);
        }
        m.put("fields", fields);
        Object updated = doc.get("updated_at");
        m.put("updatedAt", updated instanceof Timestamp t ? t.toLocalDateTime().toString() : updated == null ? null : updated.toString());
        return m;
    }

    /** Internal pipeline states -> the few a caller needs (stable even if the pipeline changes). */
    static String external(String status) {
        return switch (status == null ? "" : status) {
            case "COMPLETED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "REVIEW" -> "IN_REVIEW";
            default -> "PROCESSING";      // NEW, CLASSIFIED, EXTRACTED, ERROR (will be retried)
        };
    }
}
