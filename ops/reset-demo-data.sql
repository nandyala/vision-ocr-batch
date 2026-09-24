-- =============================================================================
-- Vision OCR - RESET ALL DATA (for demos / test environments only)
--
-- Deletes every row in the job's tables in schema ocr, including Spring Batch run history,
-- and restarts the id counters at 1. Tables, views, indexes and anything outside schema ocr
-- are NOT touched. Input files are not touched either: files still in the input folder are
-- processed again on the next run - so ALSO run ops/reset-demo-files.bat (Windows) or
-- ops/reset-demo-files.sh, which moves them to data/archive/<timestamp>/.
--
-- SAFETY:
--   1. Set @confirm_database below to the name of the database you mean to reset.
--      The script stops if you are connected to a different database.
--   2. Do not run it while the job is running.
--   3. Everything runs in one transaction: either all data is deleted or nothing is.
--
-- Run the whole script at once in SSMS / Azure Data Studio (it has no GO separators).
-- =============================================================================
SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @confirm_database SYSNAME = N'<type the database name here>';

IF DB_NAME() <> @confirm_database
    THROW 50001, 'Stopped: @confirm_database does not match the current database. Nothing was deleted.', 1;

IF SCHEMA_ID(N'ocr') IS NULL
    THROW 50002, 'Stopped: schema ocr does not exist in this database. Nothing was deleted.', 1;

BEGIN TRANSACTION;

-- ---- job data (children first, because of foreign keys)
DELETE FROM ocr.doc_field;
DELETE FROM ocr.doc_field_correction;
DELETE FROM ocr.doc_status_history;
DELETE FROM ocr.doc_error;
DELETE FROM ocr.doc_reprocess_request;
DELETE FROM ocr.doc_azure_result;
DELETE FROM ocr.doc_job;

-- ---- Spring Batch run history (ocr.BATCH_*)
DELETE FROM ocr.BATCH_STEP_EXECUTION_CONTEXT;
DELETE FROM ocr.BATCH_STEP_EXECUTION;
DELETE FROM ocr.BATCH_JOB_EXECUTION_CONTEXT;
DELETE FROM ocr.BATCH_JOB_EXECUTION_PARAMS;
DELETE FROM ocr.BATCH_JOB_EXECUTION;
DELETE FROM ocr.BATCH_JOB_INSTANCE;

-- ---- restart ids at 1 (only tables that have been used, so fresh tables also start at 1)
DECLARE @table NVARCHAR(300), @sql NVARCHAR(400);
DECLARE reseed CURSOR LOCAL FAST_FORWARD FOR
    SELECT QUOTENAME(s.name) + N'.' + QUOTENAME(t.name)
    FROM sys.identity_columns ic
    JOIN sys.tables t  ON t.object_id = ic.object_id
    JOIN sys.schemas s ON s.schema_id = t.schema_id
    WHERE s.name = N'ocr' AND ic.last_value IS NOT NULL;
OPEN reseed;
FETCH NEXT FROM reseed INTO @table;
WHILE @@FETCH_STATUS = 0
BEGIN
    SET @sql = N'DBCC CHECKIDENT (''' + @table + N''', RESEED, 0) WITH NO_INFOMSGS;';
    EXEC sys.sp_executesql @sql;
    FETCH NEXT FROM reseed INTO @table;
END;
CLOSE reseed;
DEALLOCATE reseed;

ALTER SEQUENCE ocr.BATCH_JOB_SEQ RESTART WITH 0;
ALTER SEQUENCE ocr.BATCH_JOB_EXECUTION_SEQ RESTART WITH 0;
ALTER SEQUENCE ocr.BATCH_STEP_EXECUTION_SEQ RESTART WITH 0;

COMMIT TRANSACTION;

-- ---- check: every table should show 0 rows
SELECT t.name AS table_name, SUM(p.rows) AS row_count
FROM sys.tables t
JOIN sys.schemas s ON s.schema_id = t.schema_id
JOIN sys.partitions p ON p.object_id = t.object_id AND p.index_id IN (0, 1)
WHERE s.name = N'ocr'
GROUP BY t.name
ORDER BY t.name;

PRINT 'Demo data deleted from schema ocr in database ' + DB_NAME() + '.';
