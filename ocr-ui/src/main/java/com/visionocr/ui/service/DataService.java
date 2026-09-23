package com.visionocr.ui.service;

import com.visionocr.config.DocTypeConfig;
import com.visionocr.config.DocTypeRegistry;
import com.visionocr.config.FieldMapping;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * "Data explorer": the final values (reviewer corrections applied, from ocr.v_doc_field_final) of every document of
 * a doc type as a grid - one row per document, one column per field. Same content as the generated view
 * ocr.v_doc_&lt;doctype&gt;, but it also shows fields not listed in the XML and which values were corrected.
 */
@Service
public class DataService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final DocTypeRegistry registry;

    public DataService(JdbcTemplate jdbcTemplate, DocTypeRegistry docTypeRegistry) {
        this.jdbc = jdbcTemplate;
        this.named = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.registry = docTypeRegistry;
    }

    /** Doc types that have documents or are configured, with counts. */
    public List<Map<String, Object>> docTypes() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (DocTypeConfig c : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docType", c.getDocType());
            m.put("description", c.getDescription());
            m.put("documents", 0);
            m.put("configured", true);
            out.put(c.getDocType(), m);
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT doc_type, COUNT(*) AS cnt FROM ocr.doc_job "
                + "WHERE doc_type IS NOT NULL GROUP BY doc_type")) {
            String dt = (String) r.get("doc_type");
            Map<String, Object> m = out.computeIfAbsent(dt, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("docType", k);
                x.put("description", null);
                x.put("configured", false);
                return x;
            });
            m.put("documents", r.get("cnt"));
        }
        return new ArrayList<>(out.values());
    }

    public Map<String, Object> grid(String docType, String status, String q, int page, int size) {
        if (docType == null || docType.isBlank()) {
            throw new IllegalArgumentException("docType is required");
        }
        StringBuilder where = new StringBuilder(" WHERE j.doc_type = :docType");
        MapSqlParameterSource p = new MapSqlParameterSource("docType", docType);
        if (status != null && !status.isBlank()) {
            where.append(" AND j.status IN (:status)");
            p.addValue("status", List.of(status.toUpperCase(Locale.ROOT).split(",")));
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (j.file_name LIKE :like OR EXISTS (SELECT 1 FROM ocr.v_doc_field_final v "
                    + "WHERE v.doc_id = j.id AND v.final_value LIKE :like))");
            p.addValue("like", "%" + q.trim().replace("[", "[[]").replace("%", "[%]").replace("_", "[_]") + "%");
        }
        int s = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        p.addValue("offset", pg * s);
        p.addValue("size", s);

        Integer total = named.queryForObject("SELECT COUNT(*) FROM ocr.doc_job j" + where, p, Integer.class);
        List<Map<String, Object>> docs = named.queryForList("SELECT j.id AS doc_id, j.file_name, j.status, j.doc_confidence, "
                + "j.updated_at FROM ocr.doc_job j" + where + " ORDER BY j.id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", p);

        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<Object, Map<String, Object>> byDoc = new LinkedHashMap<>();
        for (Map<String, Object> d : docs) {
            Map<String, Object> row = new LinkedHashMap<>(d);
            row.put("values", new LinkedHashMap<String, Object>());
            byDoc.put(((Number) d.get("doc_id")).longValue(), row);
        }
        if (!byDoc.isEmpty()) {
            List<Map<String, Object>> values = named.queryForList("SELECT doc_id, field_name, final_value, value_source, field_status "
                    + "FROM ocr.v_doc_field_final WHERE doc_id IN (:ids)", new MapSqlParameterSource("ids", byDoc.keySet()));
            for (Map<String, Object> v : values) {
                Map<String, Object> row = byDoc.get(((Number) v.get("doc_id")).longValue());
                String field = (String) v.get("field_name");
                seen.add(field);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("v", v.get("final_value"));
                cell.put("src", v.get("value_source"));
                cell.put("st", v.get("field_status"));
                @SuppressWarnings("unchecked")
                Map<String, Object> vals = (Map<String, Object>) row.get("values");
                vals.put(field, cell);
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docType", docType);
        m.put("columns", columns(docType, seen));
        m.put("rows", new ArrayList<>(byDoc.values()));
        m.put("total", total == null ? 0 : total);
        m.put("page", pg);
        m.put("size", s);
        String view = viewName(docType);
        m.put("sqlView", view);
        m.put("sqlViewExists", Boolean.TRUE.equals(jdbc.queryForObject("SELECT CASE WHEN OBJECT_ID(?, 'V') IS NULL THEN 0 ELSE 1 END",
                Boolean.class, view)));
        return m;
    }

    /** All documents of a doc type as CSV, final values. */
    public void csv(String docType, Writer out) throws IOException {
        List<Long> ids = jdbc.queryForList("SELECT TOP 100000 id FROM ocr.doc_job WHERE doc_type = ? ORDER BY id", Long.class, docType);
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        seen.addAll(jdbc.queryForList("SELECT DISTINCT field_name FROM ocr.doc_field WHERE doc_type = ?", String.class, docType));
        List<Map<String, Object>> cols = columns(docType, seen);
        out.write("doc_id,file_name,status");
        for (Map<String, Object> c : cols) {
            out.write("," + Csv.cell(c.get("name")));
        }
        out.write("\r\n");
        for (int i = 0; i < ids.size(); i += 1000) {
            List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + 1000));
            MapSqlParameterSource p = new MapSqlParameterSource("ids", chunk);
            Map<Long, Map<String, Object>> docs = new LinkedHashMap<>();
            for (Map<String, Object> d : named.queryForList("SELECT id, file_name, status FROM ocr.doc_job WHERE id IN (:ids) ORDER BY id", p)) {
                d.put("values", new LinkedHashMap<String, Object>());
                docs.put(((Number) d.get("id")).longValue(), d);
            }
            for (Map<String, Object> v : named.queryForList("SELECT doc_id, field_name, final_value FROM ocr.v_doc_field_final "
                    + "WHERE doc_id IN (:ids)", p)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> vals = (Map<String, Object>) docs.get(((Number) v.get("doc_id")).longValue()).get("values");
                vals.put(((String) v.get("field_name")).toLowerCase(Locale.ROOT), v.get("final_value"));
            }
            for (Map<String, Object> d : docs.values()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> vals = (Map<String, Object>) d.get("values");
                out.write(Csv.cell(d.get("id")) + "," + Csv.cell(d.get("file_name")) + "," + Csv.cell(d.get("status")));
                for (Map<String, Object> c : cols) {
                    out.write("," + Csv.cell(vals.get(((String) c.get("name")).toLowerCase(Locale.ROOT))));
                }
                out.write("\r\n");
            }
        }
    }

    /** Configured fields (XML order, or viewColumns) first, then any other field the model returned. */
    private List<Map<String, Object>> columns(String docType, Set<String> seen) {
        Set<String> configured = new LinkedHashSet<>();
        DocTypeConfig cfg = registry.get(docType);
        if (cfg != null) {
            if (!cfg.getViewColumns().isEmpty()) {
                configured.addAll(cfg.getViewColumns());
            } else {
                for (FieldMapping f : cfg.getFields()) {
                    configured.add(f.getCanonicalField());
                }
            }
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        Set<String> used = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String c : configured) {
            cols.add(Map.of("name", c, "configured", true));
            used.add(c);
        }
        for (String c : seen) {
            if (used.add(c)) {
                cols.add(Map.of("name", c, "configured", false));
            }
        }
        return cols;
    }

    /** Same rule as the batch's DocTypeViewTasklet. */
    static String viewName(String docType) {
        return "ocr.v_doc_" + docType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
