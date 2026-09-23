package com.visionocr.batch;

import com.visionocr.config.DocTypeRegistry;
import com.visionocr.domain.DocStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Step 1: registers new files from the input folder in DOC_JOB (status NEW).
 * <ul>
 *   <li>Duplicates (same SHA-256) are ignored, so re-running is safe.</li>
 *   <li>If a file sits in a sub-folder named after a doc type (input/AUTO_PAY_AUTH/x.pdf)
 *       the doc type is pre-set and the classifier is skipped for it.</li>
 *   <li>Empty or oversized files are registered as FAILED (stage INGEST) so they are visible.</li>
 * </ul>
 */
public class IngestTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(IngestTasklet.class);

    private final JdbcTemplate jdbc;
    private final DocTypeRegistry registry;
    private final Path inputDir;
    private final Set<String> extensions;
    private final long maxBytes;

    public IngestTasklet(JdbcTemplate jdbc, DocTypeRegistry registry, String inputDir,
                         String extensions, int maxFileMb) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.inputDir = Path.of(inputDir).toAbsolutePath().normalize();
        this.extensions = Stream.of(extensions.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.maxBytes = maxFileMb * 1024L * 1024L;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        if (!Files.isDirectory(inputDir)) {
            log.warn("Input folder {} does not exist - nothing to ingest", inputDir);
            return RepeatStatus.FINISHED;
        }
        int added = 0;
        int skipped = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(inputDir)) {
            files = walk.filter(Files::isRegularFile).filter(this::supported).sorted().collect(Collectors.toList());
        }
        for (Path file : files) {
            String hash = sha256(file);
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_job WHERE file_hash = ?", Integer.class, hash);
            if (exists != null && exists > 0) {
                skipped++;
                continue;
            }
            long size = Files.size(file);
            boolean badSize = size == 0 || size > maxBytes;
            DocStatus status = badSize ? DocStatus.FAILED : DocStatus.NEW;
            String error = badSize ? "INGEST: file size " + size + " bytes outside 1.." + maxBytes : null;
            jdbc.update("INSERT INTO ocr.doc_job (file_path, file_name, file_hash, file_size, status, doc_type, "
                            + "failed_stage, last_error) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    file.toString(), file.getFileName().toString(), hash, size, status.name(), docTypeHint(file),
                    badSize ? "INGEST" : null, error);
            Long id = jdbc.queryForObject("SELECT id FROM ocr.doc_job WHERE file_hash = ?", Long.class, hash);
            jdbc.update("INSERT INTO ocr.doc_status_history (doc_id, from_status, to_status, stage, note) VALUES (?, ?, ?, ?, ?)",
                    id, null, status.name(), "INGEST", error);
            if (badSize) {
                jdbc.update("INSERT INTO ocr.doc_error (doc_id, stage, attempt_no, error_class, error_message, retryable) "
                        + "VALUES (?, 'INGEST', 1, 'InvalidFile', ?, ?)", id, error, false);
            }
            added++;
            contribution.incrementWriteCount(1);
        }
        log.info("Ingest: {} new file(s) registered, {} already known (input={})", added, skipped, inputDir);
        return RepeatStatus.FINISHED;
    }

    private boolean supported(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && extensions.contains(name.substring(dot + 1));
    }

    /** input/AUTO_PAY_AUTH/file.pdf -> AUTO_PAY_AUTH (only if that doc type is registered). */
    private String docTypeHint(Path file) {
        Path rel = inputDir.relativize(file);
        if (rel.getNameCount() < 2) {
            return null;
        }
        String folder = rel.getName(0).toString();
        return registry.contains(folder) ? registry.get(folder).getDocType() : null;
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
