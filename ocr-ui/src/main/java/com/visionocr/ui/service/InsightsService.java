package com.visionocr.ui.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Numbers for the overview page, model quality per field and the correction log. Read-only. */
@Service
public class InsightsService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public InsightsService(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
        this.named = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    // ------------------------------------------------------------------ overview

    public Map<String, Object> overview(int days) {
        int d = Math.max(1, Math.min(days, 90));
        Map<String, Object> m = new LinkedHashMap<>();

        Map<String, Object> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT status, COUNT(*) AS cnt FROM ocr.doc_job GROUP BY status")) {
            byStatus.put((String) r.get("status"), r.get("cnt"));
        }
        m.put("byStatus", byStatus);

        Map<String, Object> k = new LinkedHashMap<>();
        k.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_job", Integer.class));
        k.put("completed", jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_job WHERE status = 'COMPLETED'", Integer.class));
        // straight-through = completed and never in the review queue
        k.put("straightThrough", jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_job j WHERE j.status = 'COMPLETED' "
                + "AND NOT EXISTS (SELECT 1 FROM ocr.doc_status_history h WHERE h.doc_id = j.id AND h.to_status = 'REVIEW')", Integer.class));
        k.put("reviewed", jdbc.queryForObject("SELECT COUNT(DISTINCT doc_id) FROM ocr.doc_status_history WHERE to_status = 'REVIEW'", Integer.class));
        k.put("avgConfidence", jdbc.queryForObject("SELECT AVG(doc_confidence) FROM ocr.doc_job WHERE doc_confidence IS NOT NULL", Double.class));
        k.put("fieldsExtracted", jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_field", Long.class));
        k.put("corrections", jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_field_correction WHERE active = 1 AND reason <> 'CONFIRMED'", Integer.class));
        k.put("confirmations", jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_field_correction WHERE active = 1 AND reason = 'CONFIRMED'", Integer.class));
        // seconds from upload (ingest) to the first result (completed or review) - the machine's part of the work
        k.put("avgSecondsToResult", jdbc.queryForObject("SELECT AVG(CAST(DATEDIFF(SECOND, j.created_at, x.done_at) AS FLOAT)) "
                + "FROM ocr.doc_job j CROSS APPLY (SELECT MIN(h.changed_at) AS done_at FROM ocr.doc_status_history h "
                + "WHERE h.doc_id = j.id AND h.to_status IN ('COMPLETED', 'REVIEW') AND h.stage <> 'REVIEW') x "
                + "WHERE x.done_at IS NOT NULL", Double.class));
        k.put("uploadedToday", jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_job WHERE created_at >= CAST(SYSDATETIME() AS DATE)", Integer.class));
        m.put("kpis", k);

        m.put("daily", jdbc.queryForList("SELECT CONVERT(VARCHAR(10), created_at, 23) AS day, status, COUNT(*) AS cnt "
                + "FROM ocr.doc_job WHERE created_at >= DATEADD(DAY, ?, CAST(SYSDATETIME() AS DATE)) "
                + "GROUP BY CONVERT(VARCHAR(10), created_at, 23), status ORDER BY day", -(d - 1)));
        m.put("days", d);
        m.put("byDocType", jdbc.queryForList("SELECT COALESCE(doc_type, '') AS doc_type, COUNT(*) AS total, "
                + "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed, "
                + "SUM(CASE WHEN status = 'REVIEW' THEN 1 ELSE 0 END) AS review, "
                + "SUM(CASE WHEN status IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END) AS problems, "
                + "AVG(doc_confidence) AS avg_confidence FROM ocr.doc_job GROUP BY doc_type ORDER BY total DESC"));
        m.put("confidence", jdbc.queryForList("SELECT CAST(FLOOR(CASE WHEN doc_confidence >= 1 THEN 0.999 ELSE doc_confidence END * 10) AS INT) "
                + "AS bucket, COUNT(*) AS cnt FROM ocr.doc_job WHERE doc_confidence IS NOT NULL "
                + "GROUP BY CAST(FLOOR(CASE WHEN doc_confidence >= 1 THEN 0.999 ELSE doc_confidence END * 10) AS INT) ORDER BY bucket"));
        m.put("flaggedFields", jdbc.queryForList("SELECT TOP 8 field_name, field_status, COUNT(*) AS cnt FROM ocr.doc_field "
                + "WHERE field_status <> 'OK' GROUP BY field_name, field_status ORDER BY cnt DESC"));
        m.put("recent", jdbc.queryForList("SELECT TOP 12 h.id, h.doc_id, j.file_name, h.stage, h.from_status, h.to_status, "
                + "h.note, h.changed_by, h.changed_at FROM ocr.doc_status_history h JOIN ocr.doc_job j ON j.id = h.doc_id "
                + "ORDER BY h.id DESC"));
        return m;
    }

    // ------------------------------------------------------------------ model quality

    /**
     * Per field: how often it was extracted, flagged by the rules, corrected and confirmed by reviewers.
     * accuracy = 1 - corrected / extracted (confirmations count as correct).
     */
    public List<Map<String, Object>> fieldQuality(String docType) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (docType != null && !docType.isBlank()) {
            where = " WHERE f.doc_type = :docType";
            p.addValue("docType", docType);
        }
        return named.queryForList("SELECT f.doc_type, f.field_name, COUNT(*) AS extracted, "
                + "SUM(CASE WHEN f.field_status <> 'OK' THEN 1 ELSE 0 END) AS flagged, "
                + "SUM(CASE WHEN f.field_status = 'MISSING' THEN 1 ELSE 0 END) AS missing, "
                + "SUM(CASE WHEN c.id IS NOT NULL AND c.reason <> 'CONFIRMED' THEN 1 ELSE 0 END) AS corrected, "
                + "SUM(CASE WHEN c.reason = 'CONFIRMED' THEN 1 ELSE 0 END) AS confirmed, "
                + "AVG(f.confidence) AS avg_confidence, MIN(f.confidence) AS min_confidence, "
                + "MAX(CAST(f.configured AS INT)) AS configured "
                + "FROM ocr.doc_field f LEFT JOIN ocr.doc_field_correction c "
                + "ON c.doc_id = f.doc_id AND c.field_name = f.field_name AND c.active = 1" + where
                + " GROUP BY f.doc_type, f.field_name ORDER BY f.doc_type, corrected DESC, flagged DESC, f.field_name", p);
    }

    // ------------------------------------------------------------------ corrections

    public Map<String, Object> corrections(String docType, String field, String reason, String user, boolean includeInactive,
                                           int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        filters(where, p, docType, field, reason, user, includeInactive);
        int s = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        p.addValue("offset", pg * s);
        p.addValue("size", s);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", named.queryForObject("SELECT COUNT(*) FROM ocr.doc_field_correction c JOIN ocr.doc_job j ON j.id = c.doc_id"
                + where, p, Integer.class));
        m.put("items", named.queryForList("SELECT c.id, c.doc_id, j.file_name, j.doc_type, j.status, c.field_name, c.original_value, "
                + "c.corrected_value, c.reason, c.comment_text, c.corrected_by, c.corrected_at, c.active, c.used_for_training "
                + "FROM ocr.doc_field_correction c JOIN ocr.doc_job j ON j.id = c.doc_id" + where
                + " ORDER BY c.id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", p));
        m.put("page", pg);
        m.put("size", s);
        return m;
    }

    public Map<String, Object> correctionStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byReason", jdbc.queryForList("SELECT reason, COUNT(*) AS cnt FROM ocr.doc_field_correction WHERE active = 1 "
                + "GROUP BY reason ORDER BY cnt DESC"));
        m.put("byReviewer", jdbc.queryForList("SELECT TOP 10 corrected_by, COUNT(*) AS cnt, MAX(corrected_at) AS last_at "
                + "FROM ocr.doc_field_correction GROUP BY corrected_by ORDER BY cnt DESC"));
        m.put("byField", jdbc.queryForList("SELECT TOP 10 j.doc_type, c.field_name, COUNT(*) AS cnt FROM ocr.doc_field_correction c "
                + "JOIN ocr.doc_job j ON j.id = c.doc_id WHERE c.active = 1 AND c.reason <> 'CONFIRMED' "
                + "GROUP BY j.doc_type, c.field_name ORDER BY cnt DESC"));
        m.put("trainingCandidates", jdbc.queryForObject("SELECT COUNT(DISTINCT doc_id) FROM ocr.doc_field_correction "
                + "WHERE active = 1 AND reason <> 'CONFIRMED' AND used_for_training = 0", Integer.class));
        return m;
    }

    /** Active corrections as CSV - the input for the next model training round. */
    public void correctionsCsv(String docType, Writer out) throws IOException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        filters(where, p, docType, null, null, null, false);
        out.write("doc_id,file_name,doc_type,field_name,original_value,corrected_value,reason,comment,corrected_by,corrected_at\r\n");
        named.query("SELECT TOP 100000 c.doc_id, j.file_name, j.doc_type, c.field_name, c.original_value, c.corrected_value, "
                + "c.reason, c.comment_text, c.corrected_by, c.corrected_at FROM ocr.doc_field_correction c "
                + "JOIN ocr.doc_job j ON j.id = c.doc_id" + where + " ORDER BY c.id", p, rs -> {
                    try {
                        for (int i = 1; i <= 10; i++) {
                            out.write(Csv.cell(rs.getObject(i)));
                            out.write(i < 10 ? "," : "\r\n");
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    private static void filters(StringBuilder where, MapSqlParameterSource p, String docType, String field, String reason,
                                String user, boolean includeInactive) {
        if (!includeInactive) {
            where.append(" AND c.active = 1");
        }
        if (docType != null && !docType.isBlank()) {
            where.append(" AND j.doc_type = :docType");
            p.addValue("docType", docType);
        }
        if (field != null && !field.isBlank()) {
            where.append(" AND c.field_name = :field");
            p.addValue("field", field);
        }
        if (reason != null && !reason.isBlank()) {
            where.append(" AND c.reason = :reason");
            p.addValue("reason", reason);
        }
        if (user != null && !user.isBlank()) {
            where.append(" AND c.corrected_by = :user");
            p.addValue("user", user);
        }
    }
}
