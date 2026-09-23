package com.visionocr.batch;

import com.visionocr.azure.DocIntelClient;
import com.visionocr.config.DocTypeConfig;
import com.visionocr.config.DocTypeRegistry;
import com.visionocr.domain.AzureCallResult;
import com.visionocr.domain.ClassificationOutput;
import com.visionocr.domain.ClassificationResult;
import com.visionocr.domain.DocStatus;
import com.visionocr.domain.DocumentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage CLASSIFY: decides the doc type (NEW -> CLASSIFIED | REVIEW | ERROR/FAILED).
 * <ul>
 *   <li>Doc type from the input sub-folder (input/AUTO_PAY_AUTH/x.pdf): classifier skipped.</li>
 *   <li>No classifier configured (azure.classifier-id empty): every document gets
 *       classify.default-doc-type. Useful while you only have one doc type / no classifier trained.</li>
 *   <li>Otherwise the Azure custom classifier decides.</li>
 * </ul>
 */
public class ClassifyProcessor implements ItemProcessor<DocumentRecord, DocumentRecord> {

    private static final Logger log = LoggerFactory.getLogger(ClassifyProcessor.class);
    static final String FOLDER_HINT = "folder-hint";
    static final String DEFAULT_DOC_TYPE = "default-doc-type";

    private final DocIntelClient client;
    private final DocTypeRegistry registry;
    private final String defaultDocType;

    public ClassifyProcessor(DocIntelClient client, DocTypeRegistry registry, String defaultDocType) {
        this.client = client;
        this.registry = registry;
        this.defaultDocType = defaultDocType == null || defaultDocType.isBlank() ? null : defaultDocType.trim();
        if (this.defaultDocType != null && !registry.contains(this.defaultDocType)) {
            throw new IllegalStateException("classify.default-doc-type '" + defaultDocType + "' is not a registered doc type");
        }
        if (!hasClassifier()) {
            log.info("No classifier configured (azure.classifier-id is empty): documents get doc type {}",
                    this.defaultDocType == null ? "from their input sub-folder only" : this.defaultDocType);
        }
    }

    private boolean hasClassifier() {
        return client.classifierId() != null && !client.classifierId().isBlank();
    }

    @Override
    public DocumentRecord process(DocumentRecord doc) {
        doc.getReviewReasons().clear();
        try {
            if (doc.getDocType() != null && registry.contains(doc.getDocType())) {
                doc.setClassifierLabel(FOLDER_HINT);
                doc.setClassifyConfidence(1.0);
                doc.setStatus(DocStatus.CLASSIFIED);
                return doc;
            }
            if (!hasClassifier()) {
                if (defaultDocType != null) {
                    doc.setDocType(registry.get(defaultDocType).getDocType());
                    doc.setClassifierLabel(DEFAULT_DOC_TYPE);
                    doc.setClassifyConfidence(1.0);
                    doc.setStatus(DocStatus.CLASSIFIED);
                } else {
                    doc.addReviewReason("NO_CLASSIFIER_CONFIGURED");
                    doc.setStatus(DocStatus.REVIEW);
                }
                return doc;
            }
            classify(doc);
        } catch (Exception e) {
            log.error("{} classification failed: {}", doc, e.getMessage());
            doc.fail(e);
        }
        return doc;
    }

    private void classify(DocumentRecord doc) {
        long start = System.currentTimeMillis();
        ClassificationOutput output = client.classify(Path.of(doc.getFilePath()));
        List<ClassificationResult> results = output.getDocuments();
        doc.setPendingResult(new AzureCallResult("CLASSIFY", client.classifierId(), null,
                output.getRawResultJson(), null, System.currentTimeMillis() - start));

        if (results.isEmpty()) {
            doc.addReviewReason("NOT_CLASSIFIED");
            doc.setStatus(DocStatus.REVIEW);
            return;
        }
        ClassificationResult best = results.stream()
                .max(Comparator.comparingDouble(ClassificationResult::getConfidence)).orElseThrow();
        doc.setClassifierLabel(best.getLabel());
        doc.setClassifyConfidence(best.getConfidence());
        doc.setPages(best.getPages());

        // Several copies of the same form (e.g. "complete both copies") are fine - we extract the best one.
        // Different doc types in one file need splitting first -> review.
        Set<String> labels = results.stream().map(ClassificationResult::getLabel).collect(Collectors.toSet());
        if (labels.size() > 1) {
            doc.addReviewReason("MULTIPLE_DOC_TYPES:" + String.join("|", labels));
        }

        DocTypeConfig config = registry.findByClassifierLabel(best.getLabel());
        if (config == null) {
            doc.setDocType(null);
            doc.addReviewReason("UNKNOWN_DOC_TYPE:" + best.getLabel());
        } else {
            doc.setDocType(config.getDocType());
            if (!config.isEnabled()) {
                doc.addReviewReason("DOC_TYPE_DISABLED");
            }
            if (best.getConfidence() < config.getMinClassifyConfidence()) {
                doc.addReviewReason(String.format("LOW_CLASSIFY_CONFIDENCE:%.2f", best.getConfidence()));
            }
        }
        doc.setStatus(doc.getReviewReasons().isEmpty() ? DocStatus.CLASSIFIED : DocStatus.REVIEW);
        log.info("{} classified as {} ({}, conf={})", doc, doc.getDocType(), best.getLabel(),
                String.format("%.2f", best.getConfidence()));
    }
}
