package com.visionocr.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Runs the create-only schema scripts at startup (db.init=true) and always reports, at INFO level,
 * which database/login the job uses and how many tables exist in schema "ocr".
 * Stops the job with a clear message when the tables are missing.
 */
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final int EXPECTED_TABLES = 13;   // 7 job tables + 6 Spring Batch tables

    private final DataSource dataSource;
    private final boolean enabled;
    private final List<Resource> scripts;

    public SchemaInitializer(DataSource dataSource, boolean enabled, List<Resource> scripts) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.scripts = scripts;
    }

    public void initialize() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> info = jdbc.queryForMap(
                "SELECT DB_NAME() AS db_name, SUSER_SNAME() AS login_name, @@SERVERNAME AS server_name");
        log.info("Connected to SQL Server {} / database {} as {}",
                info.get("server_name"), info.get("db_name"), info.get("login_name"));

        if (enabled) {
            for (Resource script : scripts) {
                log.info("Running create-only script {} (schema ocr)", script.getFilename());
                // continueOnError = false: any failure (e.g. missing permission) stops the job with the SQL error
                new ResourceDatabasePopulator(false, false, "UTF-8", script).execute(dataSource);
            }
        } else {
            log.info("db.init=false: schema scripts not run (expecting a DBA to have created schema ocr)");
        }

        Integer tables = jdbc.queryForObject("SELECT COUNT(*) FROM sys.tables t "
                + "JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE s.name = 'ocr'", Integer.class);
        log.info("Schema ocr in database {}: {} tables found (expected {})", info.get("db_name"), tables, EXPECTED_TABLES);
        if (tables == null || tables < EXPECTED_TABLES) {
            throw new IllegalStateException("Schema ocr in database " + info.get("db_name") + " has " + tables
                    + " of " + EXPECTED_TABLES + " tables. Set db.init=true (login needs CREATE SCHEMA/TABLE/VIEW/SEQUENCE) "
                    + "or run schema-batch-sqlserver.sql and schema-app-sqlserver.sql in this database.");
        }
    }
}
