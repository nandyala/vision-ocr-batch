package com.visionocr.batch;

import com.visionocr.config.DocTypeConfig;
import com.visionocr.config.DocTypeRegistry;
import com.visionocr.domain.DocStatus;
import com.visionocr.domain.DocumentRecord;
import com.visionocr.domain.ExtractionOutput;
import com.visionocr.domain.MappedDocument;
import com.visionocr.mapping.FieldMappingService;
import com.visionocr.repository.DocumentRepository;
import com.visionocr.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

/**
 * Stage MAP: maps the current Azure result to canonical fields and validates
 * (EXTRACTED -> COMPLETED | REVIEW | FAILED). Reads DOC_AZURE_RESULT, never calls Azure.
 */
public class MapValidateProcessor implements ItemProcessor<DocumentRecord, MappedDocument> {

    private static final Logger log = LoggerFactory.getLogger(MapValidateProcessor.class);

    private final DocTypeRegistry registry;
    private final FieldMappingService mappingService;
    private final DocumentRepository repository;

    public MapValidateProcessor(DocTypeRegistry registry, FieldMappingService mappingService,
                                DocumentRepository repository) {
        this.registry = registry;
        this.mappingService = mappingService;
        this.repository = repository;
    }

    @Override
    public MappedDocument process(DocumentRecord doc) {
        try {
            DocTypeConfig config = registry.get(doc.getDocType());
            if (config == null) {
                throw new IllegalStateException("No config for doc type " + doc.getDocType());
            }
            DocumentRepository.CurrentExtraction current = repository.currentExtraction(doc.getId());
            if (current == null) {
                throw new IllegalStateException("No current EXTRACT result - reprocess from EXTRACT");
            }
            doc.getReviewReasons().clear();
            ExtractionOutput out = Json.read(current.getFieldsJson(), ExtractionOutput.class);
            MappedDocument mapped = mappingService.map(config, doc, out, current.getResultId());
            doc.setStatus(doc.getReviewReasons().isEmpty() ? DocStatus.COMPLETED : DocStatus.REVIEW);
            log.info("{} -> {} {}", doc, doc.getStatus(), doc.getReviewReasons().isEmpty() ? "" : doc.getReviewReasons());
            return mapped;
        } catch (Exception e) {
            log.error("{} mapping failed: {}", doc, e.getMessage());
            doc.fail(e);
            return new MappedDocument(doc, null);
        }
    }
}
