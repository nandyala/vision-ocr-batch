package com.visionocr.batch;

import com.visionocr.domain.DocStatus;
import com.visionocr.domain.MappedDocument;
import com.visionocr.domain.Stage;
import com.visionocr.repository.DocumentRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes canonical fields to DOC_FIELD (replacing any previous run) and the final document status.
 * Extend or replace this writer to push results to your target system (API, queue, core tables).
 */
public class MappedDocumentWriter implements ItemWriter<MappedDocument> {

    private final DocumentRepository repository;

    public MappedDocumentWriter(DocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public void write(Chunk<? extends MappedDocument> chunk) {
        List<MappedDocument> withFields = new ArrayList<>();
        for (MappedDocument m : chunk) {
            if (m.getRecord().getStatus() == DocStatus.COMPLETED || m.getRecord().getStatus() == DocStatus.REVIEW) {
                withFields.add(m);
            }
        }
        repository.replaceFields(withFields);   // batched for the whole chunk
        for (MappedDocument m : chunk) {
            repository.saveOutcome(m.getRecord(), Stage.MAP);
        }
    }
}
