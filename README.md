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
        │                 → DOC_FIELD (one row per field) + DOC_JOB.EXTRACTED_JSON
 ⑤ housekeepingStep ───── retention: old full JSON, history/errors, Spring Batch run records
 ⑥ summaryStep ────────── counts per doc type / status

 any failure ─► ERROR (retried automatically with back-off) or FAILED (operator reprocess)
```

* **Status-driven:** every step reads documents by status. A crashed or stopped run is simply
  launched again and continues where it stopped. Nothing is processed twice.
* **Parallel:** classify and extract send `batch.threads` documents to Azure at the same time
  (documents split by `id % threads`). `batch.threads=1` processes one at a time.
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
  schema-batch-sqlserver.sql  Spring Batch tables (ocr.BATCH_*)
  schema-app-sqlserver.sql    job tables, all in schema ocr:
                              doc_job, doc_azure_result, doc_field, doc_field_correction,
                              doc_status_history, doc_error, doc_reprocess_request, v_doc_field_final
src/main/java/com/visionocr/
  azure/       DocIntelClient (seam), AzureDocIntelClient
  batch/       tasklets (recovery, ingest, summary), processors, writers, RetryPolicy
  repository/  DocumentRepository - all pipeline writes (status, Azure JSON, errors, history, fields)
  config/      DocTypeConfig, FieldMapping, DocTypeRegistry
  mapping/     FieldMappingService + normalizers
  validation/  field validators + cross-field rules (ABA checksum, fuzzy name match...)
scripts/azure-train.sh        train model/classifier via REST
ops/operations.sql            monitoring, reprocess, correction and housekeeping queries
```

## Build and run

Requirements: **JDK 17+**, **Maven 3.9+**.

```bash
mvn clean package              # unit tests; the end-to-end test runs only with a test SQL Server (see below)

./run.sh                       # real Azure: set AZURE_DI_ENDPOINT, AZURE_DI_KEY (or managed identity),
                               #             AZURE_DI_CLASSIFIER_ID (optional)
```

Direct launch (Spring Batch `CommandLineJobRunner`):

```bash
java -jar target/vision-ocr-batch.jar job-context.xml docExtractionJob -next
# override any property:  -Dbatch.commit-interval=10 -Dinput.dir=/mnt/scans -Dconfig.file=/etc/ocr/app.properties
```

### Database (SQL Server)

The job uses Microsoft SQL Server (2016 SP1 or later, or Azure SQL Database), typically an **existing
database shared with other applications**. It is designed not to interfere with anything already there:

* **Everything lives in its own schema `ocr`**: the job's tables (`ocr.doc_job`, `ocr.doc_field`, ...),
  its views, and Spring Batch's own tables (`ocr.BATCH_*`, via `tablePrefix`). Nothing is created in `dbo`,
  and other Spring Batch applications in the same database are not affected.
* **Create-only scripts:** `schema-batch-sqlserver.sql` and `schema-app-sqlserver.sql` create the schema
  and the objects that are missing, and never drop or alter existing objects. Errors are not ignored.
* **The only DROP statement** is for the job's generated views `ocr.v_doc_<doctype>`, recreated on each run.
  The code refuses any other view name.
* Row deletes only happen in the job's own tables (`ocr.doc_field` when a document is re-mapped).

Connection settings, in `application-local.properties` or environment variables (`DB_URL`, `DB_USER`,
`DB_PASSWORD`):

```properties
db.url=jdbc:sqlserver://<server>:1433;databaseName=<existing db>;encrypt=true;trustServerCertificate=false
db.user=<sql login>
db.password=<password>
db.init=true     # create missing ocr.* objects at startup; false if the DBA has run the two scripts
```

**Permissions for the job's login:**

* `db.init=true`: `CREATE SCHEMA` (first run only), plus `CREATE TABLE`, `CREATE VIEW` and `CREATE SEQUENCE`.
* In production the DBA usually runs the two scripts once. Then set `db.init=false`. The login only needs:
  * `SELECT, INSERT, UPDATE, DELETE` on schema `ocr`
  * `CREATE VIEW` in the database, plus `ALTER` on schema `ocr`, for the generated per-doc-type views

```sql
-- run by the DBA, after the two scripts
GRANT SELECT, INSERT, UPDATE, DELETE, ALTER ON SCHEMA::ocr TO <job_user>;
GRANT CREATE VIEW TO <job_user>;
```

**End-to-end test:** it needs a SQL Server **test** database (never production):

```bash
mvn test -Dtest.db.url="jdbc:sqlserver://<host>:1433;databaseName=<test db>;encrypt=true;trustServerCertificate=true" \
         -Dtest.db.user=<user> -Dtest.db.password=<password>
```

Without these settings it is skipped. The test uses unique file names per run, so it can be repeated.

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
7. **Look at the data:** in SQL Server Management Studio / Azure Data Studio, or IntelliJ Ultimate's
   *Database* tool window (*+ → Data Source → Microsoft SQL Server*). The tables are in schema `ocr`.

The run configurations live in `.run/` and appear in IntelliJ automatically. From a command prompt,
`run.bat` does the same as `run.sh`.

**Windows notes**
* Use forward slashes in properties paths: `input.dir=C:/scans/incoming`.
* If your company uses a proxy, add `-Dhttps.proxyHost=... -Dhttps.proxyPort=...` to the run
  configuration's VM options so the job can reach `*.cognitiveservices.azure.com`.
* "Cannot resolve symbol" errors in the editor: Maven tool window → *Reload All Maven Projects*.

## Viewing the extracted data

Everything the job produces is stored in schema `ocr` of the SQL Server database. Open it with
SQL Server Management Studio, Azure Data Studio, or IntelliJ Ultimate's *Database* tool window
(*+ → Data Source → Microsoft SQL Server*, same server/login as the job). More queries are in
`ops/operations.sql`.

### Useful queries

Each document and its outcome:

```sql
SELECT id, file_name, status, doc_type, model_id, doc_confidence, review_reasons, last_error
FROM ocr.doc_job ORDER BY id;
```

Extracted fields of one document (`field_status` = OK | MISSING | LOW_CONFIDENCE | INVALID,
`message` says why; `configured` = the field has rules in the doc type XML, `raw_value` = before cleanup):

```sql
SELECT field_name, field_value, raw_value, confidence, field_status, message, configured
FROM ocr.doc_field WHERE doc_id = 1 ORDER BY field_name;
```

All extracted values of every document as one JSON object (works for any doc type, no field names needed):

```sql
SELECT id, file_name, doc_type, status, extracted_json FROM ocr.doc_job ORDER BY id;
```

One row per document with the fields as columns: the job generates a view per doc type at the start
of every run, named `ocr.v_doc_<doctype>` (e.g. `ocr.v_doc_auto_pay_auth`). Its columns come from the doc type
XML (`viewColumns`, or else the listed `fields`), so a new doc type or field needs no SQL. Values include
reviewer corrections:

```sql
SELECT * FROM ocr.v_doc_auto_pay_auth ORDER BY doc_id;
```

Find documents by a field value (indexed):

```sql
SELECT j.id, j.file_name, j.status FROM ocr.doc_field f JOIN ocr.doc_job j ON j.id = f.doc_id
WHERE f.field_name = 'lenderAccountNumber' AND f.value_key = 'LN-4455667';
```

On SQL Server you can also read the JSON directly:
`SELECT JSON_VALUE(extracted_json, '$.routingNumber') FROM ocr.doc_job WHERE id = 1;`

All fields of all documents as rows, with reviewer corrections applied (generic for every doc type):

```sql
SELECT j.id, j.file_name, j.doc_type, f.field_name, f.final_value, f.value_source, f.confidence, f.field_status
FROM ocr.doc_job j JOIN ocr.v_doc_field_final f ON f.doc_id = j.id
ORDER BY j.id, f.field_name;
```

Exactly what Azure returned for each field, including fields the job does not map (`fields_json`).
Azure's full response is only kept with `results.store-full-json=true`:

```sql
SELECT id, model_id, doc_confidence, fields_json
FROM ocr.doc_azure_result WHERE doc_id = 1 AND operation = 'EXTRACT' AND is_current = 1;
```

History and errors of one document:

```sql
SELECT changed_at, stage, from_status, to_status, note FROM ocr.doc_status_history WHERE doc_id = 1 ORDER BY id;
SELECT stage, attempt_no, http_status, error_message FROM ocr.doc_error WHERE doc_id = 1 ORDER BY id;
```

### Fields showing MISSING?

Usually the field names in the Azure model differ from `azureField` in `doctypes/auto-pay-auth.xml`
(names are case-sensitive). Compare them with the keys in `fields_json`, fix the XML, then re-map the
documents without calling Azure again:

```sql
INSERT INTO ocr.doc_reprocess_request (from_stage, doc_type, current_status, reason, requested_by)
VALUES ('MAP', 'AUTO_PAY_AUTH', 'REVIEW', 'field names aligned', 'me');
```

More queries: `ops/operations.sql`.

## Adding a document type (no Java or schema changes)

The data structure is generic: every field the Azure model returns is stored as a row in `doc_field`
and in `doc_job.extracted_json`, whatever the doc type. A new doc type with different fields needs
no table changes and, at minimum, only `docType` + `modelId` (+ `classifierLabels`) in its XML.
The field list in the XML is optional and only adds rules (rename, cleanup, validation, required).

1. **Azure:** train an extraction model for it, and add its class to the classifier (AZURE_SETUP.md §10).
2. Copy `doctypes/_doctype-template.xml.example` to `doctypes/<name>.xml` and set:
   * `docType`: the internal name (also usable as an input sub-folder name)
   * `classifierLabels`: the classifier class name(s) that mean this doc type
   * `modelId`: the extraction model
   * `fields`: one `FieldMapping` per field: `azureField` (label name in the Studio) →
     `canonicalField`, `required`, `minConfidence`, `normalizers`, `validators`
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
| `reference.routing-directory-csv` | – | optional `routing,bankName` CSV for the bank-name cross-check |
| `retry.max-attempts` / `retry.base-delay-minutes` / `retry.max-delay-minutes` | – | automatic retry and back-off |
| `results.store-full-json` | – | `false` (default): store only the extracted fields JSON. `true`: also keep Azure's full response (compressed) |
| `batch.threads` | – | documents sent to Azure in parallel (default 4; keep within your Azure TPS limit, `db.pool-size` ≥ threads + 2) |
| `retention.full-json-days` | – | clear the full Azure JSON after N days (default 180; re-mapping still works) |
| `retention.history-days` | – | delete `doc_status_history` / `doc_error` rows after N days (default 730) |
| `retention.batch-metadata-days` | – | delete Spring Batch run records after N days (default 90) |

## Security notes

* Extracted values and Azure JSON are stored in plain text. Protect the database itself: access
  control, encryption at rest (SQL Server TDE), backups.
* Logs never contain field values, only document ids, statuses and reason codes.
* Secrets (Azure key, DB password) come from environment variables or `application-local.properties`,
  which is git-ignored.

## Known limits / next steps

* A file with **different** doc types inside goes to REVIEW (`MULTIPLE_DOC_TYPES`). Splitting it into
  separate documents is a possible extension using the classifier's page ranges.
* The Azure call runs inside the chunk transaction, so keep `batch.commit-interval` small (default 5).
  If the job crashes mid-chunk, those documents are sent to Azure again on the next run.
* The schema scripts only create missing objects. Changes to existing tables need a migration
  (Flyway/Liquibase or DBA-managed change scripts).
* Build a review UI (or connect an existing work queue) on top of `doc_job` / `doc_field`, and feed
  corrections back as training data.
* Auto-labelling helper: generate `.labels.json` for historical documents from their known values.
