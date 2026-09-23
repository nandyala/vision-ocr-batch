package com.visionocr.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One row of DOC_JOB: a single input file travelling through the pipeline. */
public class DocumentRecord {

    private long id;
    private String filePath;
    private String fileName;
    private String fileHash;
    private long fileSize;
    /** Status when the row was read; used for the status-history entry. */
    private DocStatus originalStatus;
    private DocStatus status;
    private String docType;
    private String classifierLabel;
    private Double classifyConfidence;
    private String pages;
    private String modelId;
    private Double docConfidence;
    private final List<String> reviewReasons = new ArrayList<>();
    private String failedStage;
    private int retryCount;
    private Instant nextRetryAt;
    private String lastError;

    // Produced by processors, persisted by the writer (all DB writes happen in the writer's transaction).
    private transient AzureCallResult pendingResult;
    private transient Exception pendingError;

    public void addReviewReason(String reason) {
        if (reason != null && !reviewReasons.contains(reason)) {
            reviewReasons.add(reason);
        }
    }

    /** Marks the document as failed in this stage. The writer decides ERROR (retry) vs FAILED. */
    public void fail(Exception e) {
        this.pendingError = e;
        this.status = DocStatus.ERROR;
    }

    public String reviewReasonsAsString() {
        if (reviewReasons.isEmpty()) {
            return null;
        }
        String s = String.join(";", reviewReasons);
        return s.length() > 1990 ? s.substring(0, 1990) : s;
    }

    public void setReviewReasonsFromString(String value) {
        reviewReasons.clear();
        if (value != null && !value.isBlank()) {
            for (String r : value.split(";")) {
                addReviewReason(r.trim());
            }
        }
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public DocStatus getOriginalStatus() { return originalStatus; }
    public void setOriginalStatus(DocStatus originalStatus) { this.originalStatus = originalStatus; }
    public DocStatus getStatus() { return status; }
    public void setStatus(DocStatus status) { this.status = status; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getClassifierLabel() { return classifierLabel; }
    public void setClassifierLabel(String classifierLabel) { this.classifierLabel = classifierLabel; }
    public Double getClassifyConfidence() { return classifyConfidence; }
    public void setClassifyConfidence(Double classifyConfidence) { this.classifyConfidence = classifyConfidence; }
    public String getPages() { return pages; }
    public void setPages(String pages) { this.pages = pages; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public Double getDocConfidence() { return docConfidence; }
    public void setDocConfidence(Double docConfidence) { this.docConfidence = docConfidence; }
    public List<String> getReviewReasons() { return reviewReasons; }
    public String getFailedStage() { return failedStage; }
    public void setFailedStage(String failedStage) { this.failedStage = failedStage; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public AzureCallResult getPendingResult() { return pendingResult; }
    public void setPendingResult(AzureCallResult pendingResult) { this.pendingResult = pendingResult; }
    public Exception getPendingError() { return pendingError; }

    @Override
    public String toString() {
        // Never include field values here: this string ends up in logs.
        return "Doc[id=" + id + ", file=" + fileName + ", status=" + status + ", docType=" + docType + "]";
    }
}
