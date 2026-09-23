package com.visionocr.batch;

import com.visionocr.azure.DocIntelClient;
import com.visionocr.config.DocTypeConfig;
import com.visionocr.config.DocTypeRegistry;
import com.visionocr.domain.AzureCallResult;
import com.visionocr.domain.DocStatus;
import com.visionocr.domain.DocumentRecord;
import com.visionocr.domain.ExtractionOutput;
import com.visionocr.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

import java.nio.file.Path;

/**
 * Stage EXTRACT: runs the doc type's Azure extraction model (CLASSIFIED -> EXTRACTED | ERROR/FAILED).
 * The full Azure JSON and the simplified fields are stored in DOC_AZURE_RESULT by the writer,
 * so the MAP stage can be re-run later without paying for another Azure call.
 */
public class ExtractProcessor implements ItemProcessor<DocumentRecord, DocumentRecord> {

    private static final Logger log = LoggerFactory.getLogger(ExtractProcessor.class);

    private final DocIntelClient client;
    private final DocTypeRegistry registry;

    public ExtractProcessor(DocIntelClient client, DocTypeRegistry registry) {
        this.client = client;
        this.registry = registry;
    }

    @Override
    public DocumentRecord process(DocumentRecord doc) {
        try {
            DocTypeConfig config = registry.get(doc.getDocType());
            if (config == null) {
                throw new IllegalStateException("No config for doc type " + doc.getDocType());
            }
            long start = System.currentTimeMillis();
            ExtractionOutput out = client.analyze(config.getModelId(), Path.of(doc.getFilePath()), doc.getPages());
            doc.setPendingResult(new AzureCallResult("EXTRACT", config.getModelId(), out.getDocConfidence(),
                    out.getRawResultJson(), Json.write(out), System.currentTimeMillis() - start));
            doc.setModelId(config.getModelId());
            doc.setDocConfidence(out.getDocConfidence());
            doc.setStatus(DocStatus.EXTRACTED);
            log.info("{} extracted with model {} ({} fields, docConf={})", doc, config.getModelId(),
                    out.getFields().size(), out.getDocConfidence());
        } catch (Exception e) {
            log.error("{} extraction failed: {}", doc, e.getMessage());
            doc.fail(e);
        }
        return doc;
    }
}
