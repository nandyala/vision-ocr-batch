package com.visionocr.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;

/**
 * Demo web app. Loads the batch module's XML configuration (DataSource, schema initializer, the
 * docExtractionJob, doc types, Azure client) and adds a REST API + single-page UI on top of it.
 * <p>
 * Settings: ocr-batch's application.properties (defaults), overridden by ./application-local.properties
 * (or -Dconfig.file=...), overridden by system properties / environment variables. UI settings: application.yml.
 */
@SpringBootApplication
@ImportResource("classpath:job-context.xml")
public class OcrUiApplication {

    public static void main(String[] args) {
        // Same local override file as the batch job, with the same precedence (it wins over the defaults).
        if (System.getProperty("spring.config.additional-location") == null) {
            String local = System.getProperty("config.file", "./application-local.properties");
            System.setProperty("spring.config.additional-location", "optional:file:" + local);
        }
        SpringApplication.run(OcrUiApplication.class, args);
    }
}
