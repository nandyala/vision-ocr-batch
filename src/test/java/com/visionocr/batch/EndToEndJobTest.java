package com.visionocr.batch;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real XML job end-to-end against a SQL Server TEST database with an offline Azure stub:
 * normal processing, automatic retry, an operator reprocess request and a correction.
 * All input files and fake Azure responses are generated here (synthetic data only).
 * <p>
 * Skipped unless a test database is given, e.g.
 * {@code mvn test -Dtest.db.url="jdbc:sqlserver://host:1433;databaseName=ocr_test;encrypt=true;trustServerCertificate=true"
 * -Dtest.db.user=... -Dtest.db.password=...} (or env TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD).
 * The test only creates/uses objects in schema ocr and uses unique file names per run,
 * but please point it at a test database, not production.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndJobTest {

    private static final String[] PROPS = {"config.file", "db.url", "db.user", "db.password", "input.dir",
            "retry.base-delay-minutes", "azure.endpoint", "azure.classifier-id"};

    /** Unique per run, so repeated runs against the same test database do not collide. */
    private static final String RUN = "e2e" + System.currentTimeMillis() + "-";

    private static ClassPathXmlApplicationContext ctx;
    private static JdbcTemplate jdbc;

    private static String setting(String prop, String env) {
        String v = System.getProperty(prop);
        return v != null && !v.isBlank() ? v : System.getenv(env);
    }

    @BeforeAll
    static void setUp() throws IOException {
        String url = setting("test.db.url", "TEST_DB_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "No test SQL Server configured (test.db.url / TEST_DB_URL) - end-to-end test skipped");

        Path work = Files.createTempDirectory("vision-ocr-e2e");
        Path input = work.resolve("input");
        createInputs(input);

        System.setProperty("config.file", work.resolve("none.properties").toString());
        System.setProperty("db.url", url);
        System.setProperty("db.user", String.valueOf(setting("test.db.user", "TEST_DB_USER")));
        System.setProperty("db.password", String.valueOf(setting("test.db.password", "TEST_DB_PASSWORD")));
        System.setProperty("input.dir", input.toString());
        System.setProperty("retry.base-delay-minutes", "0");   // retry immediately on the next run
        System.setProperty("azure.endpoint", "https://unused.example");
        System.setProperty("azure.classifier-id", "test-classifier");

        // test-stub-context.xml replaces the real Azure client bean
        ctx = new ClassPathXmlApplicationContext("job-context.xml", "test-stub-context.xml");
        jdbc = ctx.getBean("jdbcTemplate", JdbcTemplate.class);
    }

    @AfterAll
    static void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
        for (String p : PROPS) {
            System.clearProperty(p);
        }
    }

    // ------------------------------------------------------------------ synthetic inputs

    private static final String GOOD_FIELDS = "\"CustomerName\":{\"value\":\"Jane Doe\",\"confidence\":0.98},"
            + "\"LenderAccountNumber\":{\"value\":\"LN-4455667\",\"confidence\":0.96},"
            + "\"BuyerAddress\":{\"value\":\"100 Example Ave\\nSpringfield, IL 62701\",\"confidence\":0.91},"
            + "\"BankAccountHolderName\":{\"value\":\"Jane  Doe.\",\"confidence\":0.95},"
            + "\"BankName\":{\"value\":\"Example Bank\",\"confidence\":0.93},"
            + "\"RoutingNumber\":{\"value\":\"0110-0001-5\",\"confidence\":0.96},"
            + "\"CheckingAccountNumber\":{\"value\":\"1234 5678 90\",\"confidence\":0.95},"
            + "\"Signature\":{\"value\":\"signed\",\"type\":\"signature\",\"confidence\":0.90},"
            + "\"ReferenceCode\":{\"value\":\" REF-2024-001 \",\"confidence\":0.89}";   // not declared in XML

    private static void createInputs(Path input) throws IOException {
        // all fields valid -> COMPLETED
        doc(input.resolve(RUN + "typed-good.pdf"), "{\"classifierLabel\":\"auto_pay_auth\",\"classifyConfidence\":0.97,"
                + "\"pages\":\"1\",\"docConfidence\":0.94,\"fields\":{" + GOOD_FIELDS + "}}");
        // doc type from sub-folder, classifier skipped -> COMPLETED
        doc(input.resolve("AUTO_PAY_AUTH").resolve(RUN + "folder-hint.pdf"), "{\"docConfidence\":0.92,\"fields\":{"
                + GOOD_FIELDS.replace("Jane Doe", "John Smith").replace("Jane  Doe.", "John Smith")
                        .replace("0110-0001-5", "011000028") + "}}");
        // bad routing checksum, unsigned, low confidence, name mismatch -> REVIEW
        doc(input.resolve(RUN + "handwritten-review.tif"), "{\"classifierLabel\":\"auto_pay_auth\",\"classifyConfidence\":0.91,"
                + "\"pages\":\"1\",\"docConfidence\":0.81,\"fields\":{"
                + "\"CustomerName\":{\"value\":\"Maria Lopez\",\"confidence\":0.88},"
                + "\"LenderAccountNumber\":{\"value\":\"LN 77123 99\",\"confidence\":0.86},"
                + "\"BankAccountHolderName\":{\"value\":\"Robert Lopez\",\"confidence\":0.84},"
                + "\"BankName\":{\"value\":\"Example Bank\",\"confidence\":0.90},"
                + "\"RoutingNumber\":{\"value\":\"0 1 1 0 0 0 0 1 6\",\"confidence\":0.93},"
                + "\"CheckingAccountNumber\":{\"value\":\"4455667788\",\"confidence\":0.62},"
                + "\"Signature\":{\"value\":\"unsigned\",\"type\":\"signature\",\"confidence\":0.88}}}");
        // classifier says "other" -> REVIEW
        doc(input.resolve(RUN + "unknown-letter.pdf"), "{\"classifierLabel\":\"other\",\"classifyConfidence\":0.88}");
        // first extract call throttled (429) -> ERROR, then COMPLETED on the next run
        doc(input.resolve(RUN + "throttled.pdf"), "{\"classifierLabel\":\"auto_pay_auth\",\"classifyConfidence\":0.95,"
                + "\"pages\":\"1\",\"docConfidence\":0.92,\"failExtractTimes\":1,\"fields\":{" + GOOD_FIELDS + "}}");
        // Azure rejects the document (400) -> FAILED
        doc(input.resolve(RUN + "corrupt.pdf"), "{\"classifierLabel\":\"auto_pay_auth\",\"classifyConfidence\":0.93,"
                + "\"failExtract\":\"permanent\"}");
    }

    /** Writes a small dummy file (unique content -> unique hash) plus its fake Azure response. */
    private static void doc(Path file, String stubJson) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "synthetic test document " + file.getFileName(), StandardCharsets.UTF_8);
        Files.writeString(file.resolveSibling(file.getFileName() + ".stub.json"), stubJson, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void firstRunProcessesDocuments() throws Exception {
        runJob();
        Map<String, String> status = statuses();
        assertEquals("COMPLETED", status.get(RUN + "typed-good.pdf"));
        assertEquals("COMPLETED", status.get(RUN + "folder-hint.pdf"));
        assertEquals("REVIEW", status.get(RUN + "handwritten-review.tif"));
        assertEquals("REVIEW", status.get(RUN + "unknown-letter.pdf"));
        assertEquals("ERROR", status.get(RUN + "throttled.pdf"), "429 -> retryable");
        assertEquals("FAILED", status.get(RUN + "corrupt.pdf"), "400 -> not retryable");

        String reasons = jdbc.queryForObject(
                "SELECT review_reasons FROM ocr.doc_job WHERE file_name = '" + RUN + "handwritten-review.tif'", String.class);
        assertTrue(reasons.contains("INVALID:routingNumber:ROUTING_CHECKSUM_FAILED"), reasons);
        assertTrue(reasons.contains("INVALID:signature:NOT_SIGNED"), reasons);
        assertTrue(reasons.contains("LOW_CONFIDENCE:checkingAccountNumber"), reasons);
        assertTrue(reasons.contains("ACCOUNT_HOLDER_NAME_MISMATCH"), reasons);

        // Values stored as plain text; configured fields normalized
        assertEquals("011000015", jdbc.queryForObject("SELECT f.field_value FROM ocr.doc_field f JOIN ocr.doc_job j ON j.id = f.doc_id "
                + "WHERE j.file_name = '" + RUN + "typed-good.pdf' AND f.field_name = 'routingNumber'", String.class));

        // Fields not declared in the doc type XML are stored too, under their Azure name
        Map<String, Object> extra = jdbc.queryForMap("SELECT f.field_value, f.configured FROM ocr.doc_field f "
                + "JOIN ocr.doc_job j ON j.id = f.doc_id WHERE j.file_name = '" + RUN + "typed-good.pdf' AND f.field_name = 'ReferenceCode'");
        assertEquals("REF-2024-001", extra.get("field_value"));
        assertEquals(Boolean.FALSE, extra.get("configured"));

        // One JSON object per document with all extracted values
        String extracted = jdbc.queryForObject("SELECT extracted_json FROM ocr.doc_job WHERE file_name = '" + RUN + "typed-good.pdf'", String.class);
        assertTrue(extracted.contains("\"routingNumber\":\"011000015\""), extracted);
        assertTrue(extracted.contains("\"ReferenceCode\":\"REF-2024-001\""), extracted);

        // Generated per-doc-type view: one row per document, one column per configured field
        Map<String, Object> flat = jdbc.queryForMap("SELECT * FROM ocr.v_doc_auto_pay_auth WHERE file_name = '" + RUN + "typed-good.pdf'");
        assertEquals("011000015", flat.get("routingNumber"));
        assertEquals("JANE DOE", flat.get("customerName"));

        // Azure JSON stored per call as plain JSON
        String fieldsJson = jdbc.queryForObject("SELECT r.fields_json FROM ocr.doc_azure_result r JOIN ocr.doc_job j ON j.id = r.doc_id "
                + "WHERE j.file_name = '" + RUN + "typed-good.pdf' AND r.operation = 'EXTRACT' AND r.is_current = 1", String.class);
        assertTrue(fieldsJson.startsWith("{"), fieldsJson);

        // Errors recorded with retryable flag
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT e.retryable FROM ocr.doc_error e JOIN ocr.doc_job j ON j.id = e.doc_id "
                + "WHERE j.file_name = '" + RUN + "throttled.pdf'", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT e.retryable FROM ocr.doc_error e JOIN ocr.doc_job j ON j.id = e.doc_id "
                + "WHERE j.file_name = '" + RUN + "corrupt.pdf'", Boolean.class));
    }

    @Test
    @Order(2)
    void secondRunRetriesTheThrottledDocument() throws Exception {
        runJob();
        Map<String, String> status = statuses();
        assertEquals("COMPLETED", status.get(RUN + "throttled.pdf"));
        assertEquals("FAILED", status.get(RUN + "corrupt.pdf"), "non-retryable failures are not retried automatically");
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_job WHERE file_name LIKE ?", Integer.class, RUN + "%"),
                "nothing ingested twice");

        String path = String.join(" > ", jdbc.queryForList("SELECT h.to_status FROM ocr.doc_status_history h "
                + "JOIN ocr.doc_job j ON j.id = h.doc_id WHERE j.file_name = '" + RUN + "throttled.pdf' ORDER BY h.id", String.class));
        assertEquals("NEW > CLASSIFIED > ERROR > CLASSIFIED > EXTRACTED > COMPLETED", path);
    }

    @Test
    @Order(3)
    void reprocessRequestReMapsWithoutCallingAzure() throws Exception {
        Long id = jdbc.queryForObject("SELECT id FROM ocr.doc_job WHERE file_name = '" + RUN + "handwritten-review.tif'", Long.class);
        jdbc.update("INSERT INTO ocr.doc_reprocess_request (from_stage, doc_id, reason, requested_by) VALUES ('MAP', ?, 'rules changed', 'test')", id);

        runJob();

        assertEquals("APPLIED", jdbc.queryForObject("SELECT state FROM ocr.doc_reprocess_request WHERE doc_id = ?", String.class, id));
        assertEquals("REVIEW", jdbc.queryForObject("SELECT status FROM ocr.doc_job WHERE id = ?", String.class, id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ocr.doc_azure_result WHERE doc_id = ? AND operation = 'EXTRACT'", Integer.class, id),
                "MAP reprocess must reuse the stored Azure result");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ocr.doc_status_history WHERE doc_id = ? AND stage = 'REPROCESS'", Integer.class, id));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_field WHERE doc_id = ?", Integer.class, id) > 0);
    }

    @Test
    @Order(4)
    void correctionWinsInFinalView() {
        Long id = jdbc.queryForObject("SELECT id FROM ocr.doc_job WHERE file_name = '" + RUN + "typed-good.pdf'", Long.class);
        assertEquals("JANE DOE", jdbc.queryForObject(
                "SELECT field_value FROM ocr.doc_field WHERE doc_id = ? AND field_name = 'customerName'", String.class, id));
        jdbc.update("INSERT INTO ocr.doc_field_correction (doc_id, field_name, original_value, corrected_value, "
                + "reason, corrected_by) VALUES (?, 'customerName', 'JANE DOE', 'JANE A DOE', "
                + "'OCR_MISREAD', 'reviewer1')", id);
        Map<String, Object> row = jdbc.queryForMap("SELECT final_value, value_source FROM ocr.v_doc_field_final "
                + "WHERE doc_id = ? AND field_name = 'customerName'", id);
        assertEquals("JANE A DOE", row.get("final_value"));
        assertEquals("CORRECTED", row.get("value_source"));
    }

    // ------------------------------------------------------------------ helpers

    private static void runJob() throws Exception {
        JobLauncher launcher = ctx.getBean("jobLauncher", JobLauncher.class);
        Job job = ctx.getBean("docExtractionJob", Job.class);
        JobExecution exec = launcher.run(job, new JobParametersBuilder().addLong("run.ts", System.nanoTime()).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, exec.getStatus(), exec.getAllFailureExceptions().toString());
    }

    private static Map<String, String> statuses() {
        return jdbc.queryForList("SELECT file_name, status FROM ocr.doc_job WHERE file_name LIKE ?", RUN + "%").stream()
                .collect(Collectors.toMap(r -> (String) r.get("file_name"), r -> (String) r.get("status")));
    }
}
