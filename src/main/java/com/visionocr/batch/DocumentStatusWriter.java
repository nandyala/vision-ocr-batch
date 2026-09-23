package com.visionocr.batch;

import com.visionocr.domain.DocumentRecord;
import com.visionocr.domain.Stage;
import com.visionocr.repository.DocumentRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/** Persists the outcome of the classify or extract stage (one instance per stage). */
public class DocumentStatusWriter implements ItemWriter<DocumentRecord> {

    private final DocumentRepository repository;
    private final Stage stage;

    public DocumentStatusWriter(DocumentRepository repository, String stage) {
        this.repository = repository;
        this.stage = Stage.valueOf(stage);
    }

    @Override
    public void write(Chunk<? extends DocumentRecord> chunk) {
        for (DocumentRecord d : chunk) {
            repository.saveOutcome(d, stage);
        }
    }
}
