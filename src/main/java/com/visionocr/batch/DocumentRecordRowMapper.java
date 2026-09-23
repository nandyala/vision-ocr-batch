package com.visionocr.batch;

import com.visionocr.domain.DocStatus;
import com.visionocr.domain.DocumentRecord;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

public class DocumentRecordRowMapper implements RowMapper<DocumentRecord> {

    @Override
    public DocumentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        DocumentRecord d = new DocumentRecord();
        d.setId(rs.getLong("id"));
        d.setFilePath(rs.getString("file_path"));
        d.setFileName(rs.getString("file_name"));
        d.setFileHash(rs.getString("file_hash"));
        d.setFileSize(rs.getLong("file_size"));
        DocStatus status = DocStatus.valueOf(rs.getString("status"));
        d.setStatus(status);
        d.setOriginalStatus(status);
        d.setDocType(rs.getString("doc_type"));
        d.setClassifierLabel(rs.getString("classifier_label"));
        d.setClassifyConfidence(nullableDouble(rs, "classify_confidence"));
        d.setPages(rs.getString("pages"));
        d.setModelId(rs.getString("model_id"));
        d.setDocConfidence(nullableDouble(rs, "doc_confidence"));
        d.setReviewReasonsFromString(rs.getString("review_reasons"));
        d.setFailedStage(rs.getString("failed_stage"));
        d.setRetryCount(rs.getInt("retry_count"));
        Timestamp next = rs.getTimestamp("next_retry_at");
        d.setNextRetryAt(next == null ? null : next.toInstant());
        d.setLastError(rs.getString("last_error"));
        return d;
    }

    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
