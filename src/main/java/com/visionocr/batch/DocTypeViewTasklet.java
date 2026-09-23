package com.visionocr.batch;

import com.visionocr.config.DocTypeConfig;
import com.visionocr.config.DocTypeRegistry;
import com.visionocr.config.FieldMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Generates one flat view per doc type, e.g. v_doc_auto_pay_auth: one row per document, one column
 * per field (values from v_doc_field_final, so reviewer corrections are included).
 * <p>
 * Columns come from the doc type XML - {@code viewColumns} if set, otherwise every field in
 * {@code fields}. Nothing is hard-coded: add a doc type or a field in XML and the view follows on the
 * next run. Fields that are only returned by the model (not listed) stay available in
 * doc_field / v_doc_field_final / doc_job.extracted_json.
 */
public class DocTypeViewTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(DocTypeViewTasklet.class);

    private final JdbcTemplate jdbc;
    private final DocTypeRegistry registry;

    public DocTypeViewTasklet(JdbcTemplate jdbc, DocTypeRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        for (DocTypeConfig c : registry.all()) {
            String view = viewName(c.getDocType());
            List<String> columns = columnsOf(c);
            // Drop + create (not CREATE OR REPLACE): PostgreSQL cannot remove/reorder columns of a view in place
            jdbc.execute("DROP VIEW IF EXISTS " + view);
            jdbc.execute(buildSql(view, c.getDocType(), columns));
            log.info("View {} ready ({} field columns)", view, columns.size());
        }
        return RepeatStatus.FINISHED;
    }

    static List<String> columnsOf(DocTypeConfig c) {
        Set<String> cols = new LinkedHashSet<>();
        if (c.getViewColumns() != null && !c.getViewColumns().isEmpty()) {
            cols.addAll(c.getViewColumns());
        } else {
            for (FieldMapping fm : c.getFields()) {
                cols.add(fm.getCanonicalField());
            }
        }
        return List.copyOf(cols);
    }

    static String buildSql(String view, String docType, List<String> fieldNames) {
        StringBuilder sql = new StringBuilder("CREATE VIEW ").append(view).append(" AS SELECT ")
                .append("j.id AS doc_id, j.file_name, j.status, j.review_reasons, j.model_id, j.doc_confidence, j.updated_at");
        Set<String> used = new LinkedHashSet<>(List.of("doc_id", "file_name", "status", "review_reasons",
                "model_id", "doc_confidence", "updated_at"));
        for (String field : fieldNames) {
            String col = columnName(field, used);
            sql.append(",\n  MAX(CASE WHEN f.field_name = '").append(field.replace("'", "''"))
                    .append("' THEN f.final_value END) AS \"").append(col).append('"');
        }
        sql.append("\nFROM doc_job j LEFT JOIN v_doc_field_final f ON f.doc_id = j.id")
                .append("\nWHERE j.doc_type = '").append(docType.replace("'", "''")).append('\'')
                .append("\nGROUP BY j.id, j.file_name, j.status, j.review_reasons, j.model_id, j.doc_confidence, j.updated_at");
        return sql.toString();
    }

    /** v_doc_ + doc type in lower case, letters/digits/underscore only. */
    static String viewName(String docType) {
        return "v_doc_" + docType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    /** Safe, unique column name: "Items[0].Amount" -> "Items_0_Amount". */
    private static String columnName(String field, Set<String> used) {
        String base = field.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (base.isEmpty()) {
            base = "field";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        String name = base;
        for (int i = 2; used.contains(name.toLowerCase(Locale.ROOT)); i++) {
            name = base + "_" + i;
        }
        used.add(name.toLowerCase(Locale.ROOT));
        return name;
    }
}
