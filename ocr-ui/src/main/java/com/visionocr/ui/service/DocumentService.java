package com.visionocr.ui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionocr.config.DocTypeConfig;
import com.visionocr.config.DocTypeRegistry;
import com.visionocr.config.FieldMapping;
import com.visionocr.ui.support.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Documents: search, detail, upload, review actions (corrections, approval, reprocessing) and file preview.
 * Works only on schema ocr. Review actions follow the same rules as ops/operations.sql: a correction is a new
 * row in doc_field_correction (the previous one is deactivated), the model value in doc_field is never changed.
 */
@Service
public class DocumentService {

    /** Why a value was corrected. CONFIRMED = the reviewer checked a flagged value and it was right. */
    public static final Set<String> REASONS = Set.of("OCR_MISREAD", "WRONG_REGION", "MISSING", "FORMAT", "CONFIRMED", "OTHER");
    private static final Set<String> STAGES = Set.of("CLASSIFY", "EXTRACT", "MAP");
    private static final Set<String> STATUSES = Set.of("NEW", "CLASSIFIED", "EXTRACTED", "COMPLETED", "REVIEW", "ERROR", "FAILED");
    private static final Map<String, String> SORTS = Map.of(
            "id", "j.id", "file_name", "j.file_name", "status", "j.status", "doc_type", "j.doc_type",
            "doc_confidence", "j.doc_confidence", "created_at", "j.created_at", "updated_at", "j.updated_at");

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;
    private final DocTypeRegistry registry;
    private final ObjectMapper mapper;
    private final Path inputDir;
    private final Set<String> extensions;
    private final long maxBytes;

    public DocumentService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                           DocTypeRegistry docTypeRegistry, ObjectMapper objectMapper,
                           @Value("${input.dir}") String inputDir,
                           @Value("${input.extensions}") String extensions,
                           @Value("${input.max-file-mb}") int maxFileMb) {
        this.jdbc = jdbcTemplate;
        this.named = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.tx = new TransactionTemplate(transactionManager);
        this.registry = docTypeRegistry;
        this.mapper = objectMapper;
        this.inputDir = Path.of(inputDir).toAbsolutePath().normalize();
        this.extensions = Stream.of(extensions.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.maxBytes = maxFileMb * 1024L * 1024L;
    }

    // ------------------------------------------------------------------ search

    /**
     * Paged search. status: comma separated; q: matches file name, document id, or any extracted value.
     */
    public Map<String, Object> search(String status, String docType, String q, String from, String to,
                                      Boolean corrected, String sort, String dir, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            List<String> st = Stream.of(status.split(",")).map(String::trim).map(s -> s.toUpperCase(Locale.ROOT))
                    .filter(STATUSES::contains).toList();
            if (!st.isEmpty()) {
                where.append(" AND j.status IN (:status)");
                p.addValue("status", st);
            }
        }
        if (docType != null && !docType.isBlank()) {
            if ("NONE".equals(docType)) {
                where.append(" AND j.doc_type IS NULL");
            } else {
                where.append(" AND j.doc_type = :docType");
                p.addValue("docType", docType);
            }
        }
        if (q != null && !q.isBlank()) {
            String term = q.trim();
            where.append(" AND (j.file_name LIKE :like OR CAST(j.id AS VARCHAR(20)) = :q"
                    + " OR EXISTS (SELECT 1 FROM ocr.doc_field f WHERE f.doc_id = j.id AND f.value_key LIKE :like)"
                    + " OR EXISTS (SELECT 1 FROM ocr.doc_field_correction c WHERE c.doc_id = j.id AND c.active = 1"
                    + " AND c.corrected_value LIKE :like))");
            p.addValue("q", term.replaceFirst("^#", ""));
            p.addValue("like", "%" + term.replace("[", "[[]").replace("%", "[%]").replace("_", "[_]") + "%");
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND j.created_at >= :from");
            p.addValue("from", LocalDate.parse(from).atStartOfDay());
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND j.created_at < :to");
            p.addValue("to", LocalDate.parse(to).plusDays(1).atStartOfDay());
        }
        if (Boolean.TRUE.equals(corrected)) {
            where.append(" AND EXISTS (SELECT 1 FROM ocr.doc_field_correction c WHERE c.doc_id = j.id AND c.active = 1)");
        }
        String order = SORTS.getOrDefault(sort == null ? "id" : sort, "j.id") + ("asc".equalsIgnoreCase(dir) ? " ASC" : " DESC");
        if (!order.startsWith("j.id")) {
            order += ", j.id DESC";
        }
        int s = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        p.addValue("offset", pg * s);
        p.addValue("size", s);

        Integer total = named.queryForObject("SELECT COUNT(*) FROM ocr.doc_job j" + where, p, Integer.class);
        List<Map<String, Object>> items = named.queryForList(
                "SELECT j.id, j.file_name, j.doc_type, j.status, j.doc_confidence, j.review_reasons, j.failed_stage, "
                        + "j.retry_count, j.file_size, j.created_at, j.updated_at, "
                        + "(SELECT COUNT(*) FROM ocr.doc_field_correction c WHERE c.doc_id = j.id AND c.active = 1 "
                        + " AND c.reason <> 'CONFIRMED') AS corrections, "
                        + "(SELECT COUNT(*) FROM ocr.doc_field f WHERE f.doc_id = j.id) AS field_count "
                        + "FROM ocr.doc_job j" + where + " ORDER BY " + order
                        + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("total", total == null ? 0 : total);
        m.put("page", pg);
        m.put("size", s);
        return m;
    }

    /** Document ids and file names for the review queue, oldest first (so reviewers work first-in first-out). */
    public List<Map<String, Object>> reviewQueue(int limit) {
        return jdbc.queryForList("SELECT TOP (?) j.id, j.file_name, j.doc_type, j.doc_confidence, j.review_reasons, "
                + "j.created_at, j.updated_at, "
                + "(SELECT COUNT(*) FROM ocr.doc_field f WHERE f.doc_id = j.id AND f.field_status <> 'OK') AS flagged_fields, "
                + "(SELECT COUNT(*) FROM ocr.doc_field_correction c WHERE c.doc_id = j.id AND c.active = 1) AS corrections "
                + "FROM ocr.doc_job j WHERE j.status = 'REVIEW' ORDER BY j.updated_at, j.id", Math.max(1, Math.min(limit, 500)));
    }

    // ------------------------------------------------------------------ detail

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, file_name, file_path, file_size, doc_type, status, "
                + "classifier_label, classify_confidence, pages, model_id, doc_confidence, review_reasons, failed_stage, "
                + "retry_count, next_retry_at, last_error, extracted_json, created_at, updated_at FROM ocr.doc_job WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new NotFoundException("Document " + id + " not found");
        }
        Map<String, Object> doc = new LinkedHashMap<>(rows.get(0));
        String path = (String) doc.remove("file_path");          // never sent to the browser
        doc.put("file_type", extension(path));
        doc.put("file_available", path != null && Files.isRegularFile(Path.of(path)));
        doc.put("extracted_json", parse((String) doc.get("extracted_json")));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("document", doc);
        m.put("fields", orderFields((String) doc.get("doc_type"), jdbc.queryForList(
                "SELECT f.field_name, f.azure_field, f.field_value, f.raw_value, f.confidence, f.field_status, f.message, "
                        + "f.configured, c.id AS correction_id, c.corrected_value, c.original_value, c.reason AS correction_reason, "
                        + "c.comment_text AS correction_comment, c.corrected_by, c.corrected_at, "
                        + "(SELECT COUNT(*) FROM ocr.doc_field_correction h WHERE h.doc_id = f.doc_id AND h.field_name = f.field_name) "
                        + "AS correction_versions "
                        + "FROM ocr.doc_field f LEFT JOIN ocr.doc_field_correction c "
                        + "ON c.doc_id = f.doc_id AND c.field_name = f.field_name AND c.active = 1 "
                        + "WHERE f.doc_id = ? ORDER BY f.id", id)));
        m.put("history", jdbc.queryForList("SELECT id, changed_at, stage, from_status, to_status, note, changed_by "
                + "FROM ocr.doc_status_history WHERE doc_id = ? ORDER BY id", id));
        m.put("errors", jdbc.queryForList("SELECT id, created_at, stage, attempt_no, error_class, http_status, retryable, "
                + "error_message FROM ocr.doc_error WHERE doc_id = ? ORDER BY id", id));
        m.put("azureCalls", jdbc.queryForList("SELECT id, operation, model_id, api_version, doc_confidence, duration_ms, "
                + "is_current, created_at, CASE WHEN result_json_gz IS NOT NULL OR result_json IS NOT NULL THEN 1 ELSE 0 END "
                + "AS has_full_json FROM ocr.doc_azure_result WHERE doc_id = ? ORDER BY id", id));
        m.put("reprocessRequests", jdbc.queryForList("SELECT id, from_stage, reason, requested_by, requested_at, state, "
                + "applied_at, message FROM ocr.doc_reprocess_request WHERE doc_id = ? ORDER BY id DESC", id));
        DocTypeConfig cfg = registry.get((String) doc.get("doc_type"));
        m.put("docTypeDescription", cfg == null ? null : cfg.getDescription());
        return m;
    }

    /** Configured fields first in the order of the doc type XML, then the rest in extraction order. */
    private List<Map<String, Object>> orderFields(String docType, List<Map<String, Object>> fields) {
        DocTypeConfig cfg = registry.get(docType);
        if (cfg == null) {
            return fields;
        }
        List<String> order = cfg.getFields().stream().map(FieldMapping::getCanonicalField).toList();
        List<Map<String, Object>> sorted = new ArrayList<>(fields);
        sorted.sort((a, b) -> Integer.compare(rank(order, a), rank(order, b)));
        return sorted;
    }

    private static int rank(List<String> order, Map<String, Object> field) {
        int i = order.indexOf((String) field.get("field_name"));
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Fields of one Azure call as stored (name, value, OCR text, confidence, type), or the full response if kept. */
    public JsonNode azureResult(long docId, long resultId, boolean full) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT fields_json, "
                + (full ? "COALESCE(CAST(DECOMPRESS(result_json_gz) AS NVARCHAR(MAX)), result_json)" : "NULL")
                + " AS full_json FROM ocr.doc_azure_result WHERE id = ? AND doc_id = ?", resultId, docId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Azure result " + resultId + " not found");
        }
        String json = (String) rows.get(0).get(full ? "full_json" : "fields_json");
        if (json == null) {
            throw new NotFoundException(full ? "The full Azure response was not kept (results.store-full-json=false)"
                    : "No field data stored for this call");
        }
        return parse(json);
    }

    /** Every correction ever made to one field (the active one and the ones it replaced). */
    public List<Map<String, Object>> correctionHistory(long docId, String field) {
        return jdbc.queryForList("SELECT id, original_value, corrected_value, reason, comment_text, corrected_by, corrected_at, "
                + "active, used_for_training FROM ocr.doc_field_correction WHERE doc_id = ? AND field_name = ? ORDER BY id DESC",
                docId, field);
    }

    // ------------------------------------------------------------------ review actions

    public void correct(long id, String field, String value, String reason, String comment, String user) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Field is required");
        }
        if (reason == null || !REASONS.contains(reason)) {
            throw new IllegalArgumentException("Reason must be one of " + REASONS);
        }
        String v = value == null ? "" : value.trim();
        if (v.length() > 4000) {
            throw new IllegalArgumentException("Value is too long (max 4000 characters)");
        }
        tx.executeWithoutResult(s -> {
            List<String> current = jdbc.queryForList("SELECT field_value FROM ocr.doc_field WHERE doc_id = ? AND field_name = ?",
                    String.class, id, field);
            if (current.isEmpty()) {
                throw new NotFoundException("Field " + field + " not found on document " + id);
            }
            jdbc.update("UPDATE ocr.doc_field_correction SET active = 0 WHERE doc_id = ? AND field_name = ? AND active = 1", id, field);
            // corrected_value '' (not NULL) so v_doc_field_final shows the cleared value instead of the model value
            jdbc.update("INSERT INTO ocr.doc_field_correction (doc_id, field_name, original_value, corrected_value, reason, "
                    + "comment_text, corrected_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, field, current.get(0), v, reason, trim(comment, 1000), user);
            String status = status(id);
            history(id, status, status, "REVIEW", ("CONFIRMED".equals(reason) ? "Confirmed " : "Corrected ") + field
                    + (comment == null || comment.isBlank() ? "" : " - " + comment.trim()), user);
            touch(id);
        });
    }

    public void revertCorrection(long id, String field, String user) {
        tx.executeWithoutResult(s -> {
            int n = jdbc.update("UPDATE ocr.doc_field_correction SET active = 0 WHERE doc_id = ? AND field_name = ? AND active = 1",
                    id, field);
            if (n == 0) {
                throw new NotFoundException("No active correction for " + field);
            }
            String status = status(id);
            history(id, status, status, "REVIEW", "Correction of " + field + " undone (model value applies again)", user);
            touch(id);
        });
    }

    public void approve(long id, String note, String user) {
        tx.executeWithoutResult(s -> {
            String status = status(id);
            if (!"REVIEW".equals(status)) {
                throw new IllegalArgumentException("Only documents in review can be approved (current status " + status + ")");
            }
            history(id, status, "COMPLETED", "REVIEW", note == null || note.isBlank() ? "Approved" : "Approved - " + trim(note, 1900), user);
            jdbc.update("UPDATE ocr.doc_job SET status = 'COMPLETED', review_reasons = NULL, updated_at = SYSDATETIME() WHERE id = ?", id);
        });
    }

    /** Puts a completed document back into the review queue (e.g. a spot check found a problem). */
    public void reopen(long id, String note, String user) {
        tx.executeWithoutResult(s -> {
            String status = status(id);
            if (!"COMPLETED".equals(status)) {
                throw new IllegalArgumentException("Only completed documents can be sent back to review (current status " + status + ")");
            }
            String reason = "MANUAL_REVIEW:" + (note == null || note.isBlank() ? "sent back by " + user : trim(note, 500));
            history(id, status, "REVIEW", "REVIEW", "Sent back to review" + (note == null || note.isBlank() ? "" : " - " + trim(note, 1900)), user);
            jdbc.update("UPDATE ocr.doc_job SET status = 'REVIEW', review_reasons = ?, updated_at = SYSDATETIME() WHERE id = ?",
                    reason, id);
        });
    }

    public void reprocess(long id, String fromStage, String reason, String user) {
        if (fromStage == null || !STAGES.contains(fromStage)) {
            throw new IllegalArgumentException("fromStage must be one of " + STAGES);
        }
        String status = status(id);
        if ("NEW".equals(status)) {
            throw new IllegalArgumentException("The document has not been processed yet");
        }
        jdbc.update("INSERT INTO ocr.doc_reprocess_request (from_stage, doc_id, reason, requested_by) VALUES (?, ?, ?, ?)",
                fromStage, id, reason == null || reason.isBlank() ? "requested from the demo UI" : trim(reason, 1000), user);
    }

    // ------------------------------------------------------------------ upload

    /**
     * Saves an uploaded file into the input folder (or input/&lt;DOC_TYPE&gt;/ when a doc type is chosen, which skips
     * the classifier). Written under a temporary name first, so the job never reads a half-written file.
     * Identical content (same SHA-256) is not stored twice.
     */
    public Map<String, Object> upload(String originalName, byte[] data, String docType) throws IOException {
        String name = sanitize(originalName);
        String ext = extension(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("originalName", originalName);
        if (!extensions.contains(ext)) {
            throw new IllegalArgumentException(originalName + ": unsupported file type ." + ext
                    + " (allowed: " + String.join(", ", extensions) + ")");
        }
        if (data.length == 0) {
            throw new IllegalArgumentException(originalName + " is empty");
        }
        if (data.length > maxBytes) {
            throw new IllegalArgumentException(originalName + " is larger than " + (maxBytes / 1024 / 1024) + " MB");
        }
        Path dir = inputDir;
        if (docType != null && !docType.isBlank() && !"AUTO".equalsIgnoreCase(docType)) {
            DocTypeConfig cfg = registry.get(docType);
            if (cfg == null) {
                throw new IllegalArgumentException("Unknown doc type " + docType);
            }
            dir = inputDir.resolve(cfg.getDocType());
        }
        String hash = sha256(data);
        result.put("hash", hash);
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id, status FROM ocr.doc_job WHERE file_hash = ?", hash);
        if (!existing.isEmpty()) {
            result.put("existing", true);
            result.put("docId", existing.get(0).get("id"));
            result.put("status", existing.get(0).get("status"));
            return result;
        }
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = dir.resolve(stamp + "-" + name);
        Path temp = dir.resolve("." + target.getFileName() + ".uploading");
        Files.write(temp, data);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        result.put("existing", false);
        result.put("fileName", target.getFileName().toString());
        return result;
    }

    /** Status of uploaded files by content hash (a file only gets a document id once the job has ingested it). */
    public List<Map<String, Object>> byHashes(List<String> hashes) {
        List<String> clean = hashes.stream().filter(h -> h != null && h.matches("[0-9a-f]{64}")).limit(100).toList();
        if (clean.isEmpty()) {
            return List.of();
        }
        return named.queryForList("SELECT id, file_hash, file_name, status, doc_type, doc_confidence, updated_at "
                + "FROM ocr.doc_job WHERE file_hash IN (:h)", new MapSqlParameterSource("h", clean));
    }

    // ------------------------------------------------------------------ preview

    public FileContent file(long id, int page) throws IOException {
        Path p = path(id);
        switch (extension(p.toString())) {
            case "pdf":
                return new FileContent("application/pdf", Files.readAllBytes(p));
            case "png":
                return new FileContent("image/png", Files.readAllBytes(p));
            case "jpg":
            case "jpeg":
                return new FileContent("image/jpeg", Files.readAllBytes(p));
            case "tif":
            case "tiff":
                return new FileContent("image/png", tiffPageAsPng(p, page));   // browsers cannot show TIFF
            default:
                throw new IllegalArgumentException("No preview for this file type");
        }
    }

    public int pages(long id) throws IOException {
        Path p = path(id);
        String ext = extension(p.toString());
        if (!ext.equals("tif") && !ext.equals("tiff")) {
            return 1;
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
            ImageReader r = reader(in);
            try {
                r.setInput(in);
                return r.getNumImages(true);
            } finally {
                r.dispose();
            }
        }
    }

    private static byte[] tiffPageAsPng(Path p, int page) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
            ImageReader r = reader(in);
            try {
                r.setInput(in);
                int n = r.getNumImages(true);
                BufferedImage img = r.read(Math.max(0, Math.min(page, n - 1)));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                return out.toByteArray();
            } finally {
                r.dispose();
            }
        }
    }

    private static ImageReader reader(ImageInputStream in) throws IOException {
        if (in == null) {
            throw new IOException("Cannot open image");
        }
        Iterator<ImageReader> it = ImageIO.getImageReaders(in);
        if (!it.hasNext()) {
            throw new IOException("No image reader for this file");
        }
        return it.next();
    }

    private Path path(long id) {
        List<String> rows = jdbc.queryForList("SELECT file_path FROM ocr.doc_job WHERE id = ?", String.class, id);
        if (rows.isEmpty()) {
            throw new NotFoundException("Document " + id + " not found");
        }
        Path p = Path.of(rows.get(0));
        if (!Files.isRegularFile(p)) {
            throw new NotFoundException("The original file is no longer available on this machine");
        }
        return p;
    }

    // ------------------------------------------------------------------ helpers

    String status(long id) {
        List<String> s = jdbc.queryForList("SELECT status FROM ocr.doc_job WHERE id = ?", String.class, id);
        if (s.isEmpty()) {
            throw new NotFoundException("Document " + id + " not found");
        }
        return s.get(0);
    }

    private void touch(long id) {
        jdbc.update("UPDATE ocr.doc_job SET updated_at = SYSDATETIME() WHERE id = ?", id);
    }

    private void history(long id, String from, String to, String stage, String note, String user) {
        jdbc.update("INSERT INTO ocr.doc_status_history (doc_id, from_status, to_status, stage, note, changed_by) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, from, to, stage, trim(note, 1990), user);
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            return mapper.getNodeFactory().textNode(json);
        }
    }

    static String sanitize(String name) {
        String base = name == null ? "" : Path.of(name.replace('\\', '/')).getFileName().toString();
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank() || base.startsWith(".")) {
            base = "document" + base;
        }
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Bytes + content type of a document preview. */
    public record FileContent(String contentType, byte[] bytes) {
    }
}
