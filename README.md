# Vision OCR Batch

Spring Batch job (**pure XML configuration, no annotations**) that extracts fields from scanned
PDF/TIFF documents with **Azure Document Intelligence**. Doc types are plug-ins: each one is a single
XML file, and adding a doc type needs no Java change.

The first doc type is `AUTO_PAY_AUTH` (Automatic Payment Authorization Agreement).

* Azure setup (resource, labelling, training): **[AZURE_SETUP.md](AZURE_SETUP.md)**
* Tables, retries and reprocessing: **[DATA_MODEL.md](DATA_MODEL.md)**. Operator SQL: `ops/operations.sql`

---

## How it works

```
 ⓪ recoveryStep ──────── apply doc_reprocess_request rows, re-queue ERROR docs whose retry time has come
        │
 data/input/*.pdf|tif
        │
 ① ingestStep ─────────── register files in DOC_JOB (SHA-256 dedupe)            status NEW
        │
 ② classifyStep ───────── Azure custom classifier  → doc type                   CLASSIFIED | REVIEW
                          (skipped if file is in input/<DOC_TYPE>/ folder)
        │
 ③ extractStep ────────── Azure extraction model of that doc type               EXTRACTED
                          → full JSON + fields stored in DOC_AZURE_RESULT
        │
 ④ mapValidateStep ────── doc type XML: normalize → validate → cross-field rules COMPLETED | REVIEW
        │                 → DOC_FIELD (sensitive values encrypted + masked)
 ⑤ summaryStep ────────── counts per doc type / status

 any failure ─► ERROR (retried automatically with back-off) or FAILED (operator reprocess)
```

* **Status-driven:** every step reads documents by status. A crashed or stopped run is simply
  launched again and continues where it stopped. Nothing is processed twice.
* **Single-threaded:** documents are processed one at a time in id order.
* **REVIEW** means a person should look at the document. `DOC_JOB.REVIEW_REASONS` says why, e.g.
  `INVALID:routingNumber:ROUTING_CHECKSUM_FAILED;MISSING:signature`.
* **ERROR / FAILED:**
  * Throttling, timeouts and 5xx errors are retried automatically with back-off (`retry.*` properties).
  * Anything else goes to FAILED. Re-run it with a row in `doc_reprocess_request`, from CLASSIFY,
    EXTRACT or MAP. Re-running from MAP reuses the stored Azure JSON, so it costs nothing.
  * Every transition and error is kept in `doc_status_history` / `doc_error`.
  * See [DATA_MODEL.md](DATA_MODEL.md).

## Project layout

```
src/main/resources/
  job-context.xml             entry point: job + steps
  infrastructure-context.xml  datasource, tx, job repository/launcher
  pipeline-context.xml        readers/processors/writers + shared normalizer/validator beans
  azure-context.xml           Azure Document Intelligence client
  doctypes/
    auto-pay-auth.xml         ← one file per doc type
    _doctype-template.xml.example
  application.properties
  schema-app.sql              doc_job, doc_azure_result, doc_field, doc_field_correction,
                              doc_status_history, doc_error, doc_reprocess_request, v_doc_field_final
src/main/java/com/visionocr/
  azure/       DocIntelClient (seam), AzureDocIntelClient
  batch/       tasklets (recovery, ingest, summary), processors, writers, RetryPolicy
  repository/  DocumentRepository - all pipeline writes (status, Azure JSON, errors, history, fields)
  config/      DocTypeConfig, FieldMapping, DocTypeRegistry
  mapping/     FieldMappingService + normalizers
  validation/  field validators + cross-field rules (ABA checksum, fuzzy name match...)
  security/    AES-GCM field encryption, masking
scripts/azure-train.sh        train model/classifier via REST
ops/operations.sql            monitoring, reprocess, correction and housekeeping queries
```

## Build and run

Requirements: **JDK 17+**, **Maven 3.9+**.

```bash
mvn clean package              # runs unit + end-to-end tests (offline: synthetic data, stubbed Azure, in-memory H2)

./run.sh                       # real Azure: set AZURE_DI_ENDPOINT, AZURE_DI_KEY (or managed identity),
                               #             AZURE_DI_CLASSIFIER_ID, FIELD_ENCRYPTION_KEY
```

Direct launch (Spring Batch `CommandLineJobRunner`):

```bash
java -jar target/vision-ocr-batch.jar job-context.xml docExtractionJob -next
# override any property:  -Dbatch.commit-interval=10 -Dinput.dir=/mnt/scans -Dconfig.file=/etc/ocr/app.properties
```

Look at the results (H2 console, or any SQL client on `./data/db/*.mv.db`):

```sql
SELECT id, file_name, doc_type, status, review_reasons, failed_stage, next_retry_at FROM doc_job ORDER BY id;
SELECT field_name, display_value, confidence, field_status, message FROM doc_field WHERE doc_id = 1;
SELECT stage, from_status, to_status, note FROM doc_status_history WHERE doc_id = 1 ORDER BY id;
```

For PostgreSQL, set `db.url`, `db.driver=org.postgresql.Driver` and `db.platform=postgresql`.

## Run on Windows with IntelliJ IDEA

1. **Install:** JDK 17 or newer (e.g. Eclipse Temurin 17/21) and IntelliJ IDEA. Maven is bundled with IntelliJ.
2. **Open:** *File → Open* → select the project folder (the one with `pom.xml`) → *Open as Project*.
   Wait for the Maven import to finish (bottom-right progress bar).
3. **JDK:** *File → Project Structure → Project → SDK* = your JDK 17+. Set *Language level* to 17.
4. **Azure settings:** copy `application-local.properties.example` to `application-local.properties`
   (project root) and fill in the endpoint, key, classifier id and model id. This file is git-ignored.
5. **Build + test:** Maven tool window (right side) → *vision-ocr-batch → Lifecycle → package*,
   or run the **All tests** configuration.
6. **Run:** pick a configuration in the top-right drop-down and press ▶:
   * **OCR Job - Azure:** the real job; reads `application-local.properties`
   Files to process go in `data\input\` (or `data\input\AUTO_PAY_AUTH\` to skip the classifier).
7. **Look at the data:** *Database* tool window → *+ → Data Source → H2* →
   URL `jdbc:h2:file:<project folder>/data/db/visionocr;MODE=PostgreSQL;AUTO_SERVER=TRUE`, user `sa`,
   empty password. `AUTO_SERVER=TRUE` lets you keep it open while the job runs.

The run configurations live in `.run/` and appear in IntelliJ automatically. From a command prompt,
`run.bat` does the same as `run.sh`.

**Windows notes**
* Use forward slashes in properties paths: `input.dir=C:/scans/incoming`.
* If your company uses a proxy, add `-Dhttps.proxyHost=... -Dhttps.proxyPort=...` to the run
  configuration's VM options so the job can reach `*.cognitiveservices.azure.com`.
* "Cannot resolve symbol" errors in the editor: Maven tool window → *Reload All Maven Projects*.

## Adding a document type (no Java changes)

1. **Azure:** train an extraction model for it, and add its class to the classifier (AZURE_SETUP.md §10).
2. Copy `doctypes/_doctype-template.xml.example` to `doctypes/<name>.xml` and set:
   * `docType`: the internal name (also usable as an input sub-folder name)
   * `classifierLabels`: the classifier class name(s) that mean this doc type
   * `modelId`: the extraction model
   * `fields`: one `FieldMapping` per field: `azureField` (label name in the Studio) →
     `canonicalField`, `required`, `sensitive`, `minConfidence`, `normalizers`, `validators`
   * `crossFieldRules`: optional
3. Build and run. `DocTypeRegistry` picks up every `DocTypeConfig` bean automatically.

Reusable building blocks (defined in `pipeline-context.xml`):
`norm.whitespace`, `norm.upper`, `norm.lower`, `norm.digits`, `norm.date`, `norm.amount`,
`val.abaRouting`, `val.isoDate`, `val.signed`, plus the `RegexReplaceNormalizer`, `RegexValidator`,
`AllowedValuesValidator`, `FuzzyMatchRule` and `RoutingBankMatchRule` classes.
You only write Java for a genuinely new rule: implement `FieldNormalizer`, `FieldValidator` or
`CrossFieldRule`, then declare it as a bean.

Table/array fields are flattened as `Items[0].Amount`, `Items[1].Amount`, and so on.

## Configuration reference

See `src/main/resources/application.properties`. Key settings:

| Property | Env var | Meaning |
|---|---|---|
| `azure.endpoint` | `AZURE_DI_ENDPOINT` | Document Intelligence endpoint |
| `azure.api-key` | `AZURE_DI_KEY` | empty = Entra ID (managed identity / az login) |
| `azure.classifier-id` | `AZURE_DI_CLASSIFIER_ID` | custom classifier id |
| `doctype.auto-pay-auth.model-id` | – | extraction model for AUTO_PAY_AUTH (currently `autopay-neural-v2`) |
| `security.field-encryption-key` | `FIELD_ENCRYPTION_KEY` | base64 AES-256 key for sensitive fields |
| `reference.routing-directory-csv` | – | optional `routing,bankName` CSV for the bank-name cross-check |
| `retry.max-attempts` / `retry.base-delay-minutes` / `retry.max-delay-minutes` | – | automatic retry and back-off |
| `results.store-full-json` | – | keep the full Azure JSON (`false` = simplified fields only) |

## Security notes

* Fields marked `sensitive` (routing and account numbers) are AES-GCM encrypted in
  `doc_field.field_value` and masked in `display_value`. The Azure JSON in `doc_azure_result` is
  encrypted too.
* Logs never contain field values, only document ids, statuses and reason codes.
* Without `FIELD_ENCRYPTION_KEY`, values are stored in clear text and a warning is logged. Don't run
  real data like that.

## Known limits / next steps

* A file with **different** doc types inside goes to REVIEW (`MULTIPLE_DOC_TYPES`). Splitting it into
  separate documents is a possible extension using the classifier's page ranges.
* The Azure call runs inside the chunk transaction, so keep `batch.commit-interval` small (default 5).
  If the job crashes mid-chunk, those documents are sent to Azure again on the next run.
* Local H2 databases created by an earlier version of this project must be deleted (`data/db`),
  because the schema changed. For PostgreSQL, use a migration tool (Flyway/Liquibase) from here on.
* Build a review UI (or connect an existing work queue) on top of `doc_job` / `doc_field`, and feed
  corrections back as training data.
* Auto-labelling helper: generate `.labels.json` for historical documents from their known values.
