package com.visionocr.ui.web;

import com.visionocr.config.DocTypeConfig;
import com.visionocr.config.DocTypeRegistry;
import com.visionocr.ui.service.ConfigService;
import com.visionocr.ui.service.DocTypeSpec;
import com.visionocr.ui.support.Reviewer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Doc types (view, create, edit), building blocks, Azure models and settings. Also /api/meta for the UI shell. */
@RestController
@RequestMapping("/api")
public class ConfigController {

    private final ConfigService config;
    private final DocTypeRegistry registry;
    private final Environment env;
    private final String productName;

    public ConfigController(ConfigService config, DocTypeRegistry registry, Environment env,
                            @Value("${ui.product-name:Document Intelligence}") String productName) {
        this.config = config;
        this.registry = registry;
        this.env = env;
        this.productName = productName;
    }

    /** What the UI needs on start: product name, doc types for pickers, upload limits. */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productName", productName);
        List<Map<String, Object>> types = new ArrayList<>();
        for (DocTypeConfig c : registry.all()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("docType", c.getDocType());
            t.put("description", c.getDescription());
            t.put("enabled", c.isEnabled());
            types.add(t);
        }
        m.put("docTypes", types);
        m.put("defaultDocType", env.getProperty("classify.default-doc-type", ""));
        m.put("classifier", !env.getProperty("azure.classifier-id", "").isBlank());
        m.put("extensions", env.getProperty("input.extensions", "pdf,tif,tiff,jpg,jpeg,png").split(","));
        m.put("maxFileMb", env.getProperty("input.max-file-mb", Integer.class, 500));
        return m;
    }

    @GetMapping("/config/doc-types")
    public List<Map<String, Object>> docTypes() {
        return config.docTypes();
    }

    @GetMapping("/config/doc-types/{docType}")
    public Map<String, Object> docType(@PathVariable("docType") String docType) {
        return config.docType(docType);
    }

    @PostMapping("/config/doc-types/preview")
    public Map<String, Object> preview(@RequestBody DocTypeSpec spec,
                                       @RequestHeader(value = Reviewer.HEADER, required = false) String user) {
        return Map.of("xml", config.previewXml(spec, Reviewer.of(user)));
    }

    @PostMapping("/config/doc-types")
    public Map<String, Object> create(@RequestBody DocTypeSpec spec,
                                      @RequestHeader(value = Reviewer.HEADER, required = false) String user) throws IOException {
        return config.save(spec, false, Reviewer.of(user));
    }

    @PutMapping("/config/doc-types/{docType}")
    public Map<String, Object> update(@PathVariable("docType") String docType, @RequestBody DocTypeSpec spec,
                                      @RequestHeader(value = Reviewer.HEADER, required = false) String user) throws IOException {
        if (spec.docType() == null || !spec.docType().equalsIgnoreCase(docType)) {
            throw new IllegalArgumentException("The doc type name cannot be changed");
        }
        return config.save(spec, true, Reviewer.of(user));
    }

    @GetMapping("/config/catalog")
    public Map<String, Object> catalog() {
        return config.catalog();
    }

    @GetMapping("/config/models")
    public Map<String, String> models() {
        return config.models();
    }

    @GetMapping("/config/models/{modelId}/fields")
    public Map<String, String> modelFields(@PathVariable("modelId") String modelId) {
        return config.modelFields(modelId);
    }

    @GetMapping("/config/settings")
    public Map<String, Object> settings() {
        return config.settings();
    }
}
