package com.visionocr.ui.service;

import com.visionocr.azure.DocIntelClient;
import com.visionocr.config.DocTypeConfig;
import com.visionocr.config.DocTypeRegistry;
import com.visionocr.config.FieldMapping;
import com.visionocr.mapping.FieldNormalizer;
import com.visionocr.validation.AllowedValuesValidator;
import com.visionocr.validation.CrossFieldRule;
import com.visionocr.validation.FieldValidator;
import com.visionocr.validation.RegexValidator;
import com.visionocr.ui.support.NotFoundException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericXmlApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration screens: doc types (built-in and created in the designer), the building blocks
 * (clean-up steps and checks) a doc type can use, Azure models, and the effective settings.
 * <p>
 * Doc types created in the UI are written as XML into ui.doctypes-dir (= the batch's doctypes.dir), loaded
 * right away into a child Spring context (so they can use the shared norm.* / val.* beans) and registered in
 * the running {@link DocTypeRegistry}. The batch job loads the same files at its next start.
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final Pattern DOC_TYPE_IN_XML = Pattern.compile("<property\\s+name=\"docType\"\\s+value=\"([^\"]+)\"");

    /** Plain-English names of the shared building blocks. Unknown beans are shown by class name. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("norm.whitespace", "Trim and collapse spaces"),
            Map.entry("norm.upper", "UPPER CASE"),
            Map.entry("norm.lower", "lower case"),
            Map.entry("norm.digits", "Keep digits only"),
            Map.entry("norm.signature", "Signature present \u2192 signed / unsigned"),
            Map.entry("norm.date", "Date \u2192 yyyy-MM-dd"),
            Map.entry("norm.amount", "Amount \u2192 1234.56"),
            Map.entry("val.abaRouting", "Valid US routing number (ABA checksum)"),
            Map.entry("val.isoDate", "Is a date (yyyy-MM-dd)"),
            Map.entry("val.signed", "Must be signed"));

    private final ApplicationContext context;
    private final DocTypeRegistry registry;
    private final DocIntelClient docIntelClient;
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final Path doctypesDir;
    private final Path brandDir;
    /** docType -> file for doc types created in the UI (editable here) */
    private final Map<String, Path> customFiles = new ConcurrentHashMap<>();
    /** child contexts holding doc types registered at runtime */
    private final Map<String, GenericXmlApplicationContext> children = new ConcurrentHashMap<>();

    public ConfigService(ApplicationContext context, DocTypeRegistry docTypeRegistry, DocIntelClient docIntelClient,
                         JdbcTemplate jdbcTemplate, Environment env,
                         @Value("${ui.doctypes-dir:./doctypes}") String doctypesDir,
                         @Value("${ui.brand-dir:./brand}") String brandDir) {
        this.context = context;
        this.registry = docTypeRegistry;
        this.docIntelClient = docIntelClient;
        this.jdbc = jdbcTemplate;
        this.env = env;
        this.doctypesDir = Path.of(doctypesDir).toAbsolutePath().normalize();
        this.brandDir = Path.of(brandDir).toAbsolutePath().normalize();
        scanCustomFiles();
    }

    private void scanCustomFiles() {
        if (!Files.isDirectory(doctypesDir)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(doctypesDir, "*.xml")) {
            for (Path p : ds) {
                Matcher m = DOC_TYPE_IN_XML.matcher(Files.readString(p, StandardCharsets.UTF_8));
                if (m.find()) {
                    customFiles.put(m.group(1).toUpperCase(Locale.ROOT), p);
                }
            }
        } catch (IOException e) {
            log.warn("Cannot read doc types folder {}: {}", doctypesDir, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ doc types

    public List<Map<String, Object>> docTypes() {
        Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map<String, Object> r : jdbc.queryForList("SELECT doc_type, COUNT(*) AS cnt FROM ocr.doc_job WHERE doc_type IS NOT NULL GROUP BY doc_type")) {
            counts.put((String) r.get("doc_type"), ((Number) r.get("cnt")).intValue());
        }
        Map<Object, String> names = beanNames();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocTypeConfig c : registry.all()) {
            Map<String, Object> m = describe(c, names);
            m.put("documents", counts.getOrDefault(c.getDocType(), 0));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> docType(String docType) {
        DocTypeConfig c = registry.get(docType);
        if (c == null) {
            throw new NotFoundException("Doc type " + docType + " not found");
        }
        Map<String, Object> m = describe(c, beanNames());
        m.put("documents", jdbc.queryForObject("SELECT COUNT(*) FROM ocr.doc_job WHERE doc_type = ?", Integer.class, c.getDocType()));
        Path file = customFiles.get(c.getDocType().toUpperCase(Locale.ROOT));
        if (file != null && Files.isRegularFile(file)) {
            try {
                m.put("xml", Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                m.put("xml", null);
            }
        }
        return m;
    }

    private Map<String, Object> describe(DocTypeConfig c, Map<Object, String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docType", c.getDocType());
        m.put("description", c.getDescription());
        m.put("enabled", c.isEnabled());
        m.put("modelId", c.getModelId());
        m.put("classifierLabels", c.getClassifierLabels());
        m.put("minClassifyConfidence", c.getMinClassifyConfidence());
        m.put("minDocumentConfidence", c.getMinDocumentConfidence());
        m.put("includeUnmappedFields", c.isIncludeUnmappedFields());
        m.put("defaultMinConfidence", c.getDefaultMinConfidence());
        m.put("viewColumns", c.getViewColumns());
        m.put("sqlView", DataService.viewName(c.getDocType()));
        Path file = customFiles.get(c.getDocType().toUpperCase(Locale.ROOT));
        m.put("source", file == null ? "built-in" : "custom");
        m.put("file", file == null ? "doctypes/" + DocTypeXml.fileName(c.getDocType()) + " (in the batch jar)" : file.toString());
        m.put("editable", file != null);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldMapping f : c.getFields()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("azureField", f.getAzureField());
            fm.put("canonicalField", f.getCanonicalField());
            fm.put("required", f.isRequired());
            fm.put("minConfidence", f.getMinConfidence());
            List<Map<String, Object>> norms = new ArrayList<>();
            for (FieldNormalizer n : f.getNormalizers()) {
                norms.add(block(n, names));
            }
            fm.put("normalizers", norms);
            List<Map<String, Object>> vals = new ArrayList<>();
            for (FieldValidator v : f.getValidators()) {
                vals.add(block(v, names));
            }
            fm.put("validators", vals);
            fields.add(fm);
        }
        m.put("fields", fields);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (CrossFieldRule r : c.getCrossFieldRules()) {
            Map<String, Object> rm = new LinkedHashMap<>();
            String type = r.getClass().getSimpleName();
            rm.put("type", type);
            rm.put("label", switch (type) {
                case "FuzzyMatchRule" -> "Two name fields must match (fuzzy) - otherwise review";
                case "RoutingBankMatchRule" -> "Routing number must belong to the bank name (reference list)";
                default -> type;
            });
            rules.add(rm);
        }
        m.put("crossFieldRules", rules);
        return m;
    }

    /** A normalizer or validator as the UI shows it: shared bean (id + label) or inline rule (regex / allowed values). */
    private static Map<String, Object> block(Object bean, Map<Object, String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        String id = names.get(bean);
        if (bean instanceof RegexValidator r) {
            m.put("type", "regex");
            m.put("regex", r.getRegex());
            m.put("message", r.getMessage());
        } else if (bean instanceof AllowedValuesValidator a) {
            m.put("type", "allowed");
            m.put("values", a.getAllowed());
            m.put("message", a.getMessage());
        } else {
            m.put("type", "bean");
        }
        m.put("id", id);
        m.put("shared", id != null && (id.startsWith("norm.") || id.startsWith("val.")));
        m.put("label", label(id, bean));
        return m;
    }

    private static String label(String id, Object bean) {
        if (id != null && LABELS.containsKey(id)) {
            return LABELS.get(id);
        }
        if (bean instanceof RegexValidator r) {
            return "Matches " + r.getRegex();
        }
        if (bean instanceof AllowedValuesValidator a) {
            return "One of " + String.join(", ", a.getAllowed());
        }
        String cls = bean.getClass().getSimpleName().replace("Normalizer", "").replace("Validator", "");
        return cls.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    /** Identity map bean instance -> bean id, for normalizer/validator beans with an id. */
    private Map<Object, String> beanNames() {
        Map<Object, String> names = new IdentityHashMap<>();
        context.getBeansOfType(FieldNormalizer.class).forEach((k, v) -> names.put(v, k));
        context.getBeansOfType(FieldValidator.class).forEach((k, v) -> names.put(v, k));
        return names;
    }

    /** Shared clean-up steps and checks a doc type in the designer can use. */
    public Map<String, Object> catalog() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("normalizers", catalogOf(context.getBeansOfType(FieldNormalizer.class)));
        m.put("validators", catalogOf(context.getBeansOfType(FieldValidator.class)));
        return m;
    }

    private static List<Map<String, Object>> catalogOf(Map<String, ?> beans) {
        List<Map<String, Object>> out = new ArrayList<>();
        beans.forEach((id, bean) -> {
            if (id.startsWith("norm.") || id.startsWith("val.")) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", id);
                e.put("label", label(id, bean));
                out.add(e);
            }
        });
        return out;
    }

    private java.util.Set<String> catalogIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String id : context.getBeansOfType(FieldNormalizer.class).keySet()) {
            if (id.startsWith("norm.")) {
                ids.add(id);
            }
        }
        for (String id : context.getBeansOfType(FieldValidator.class).keySet()) {
            if (id.startsWith("val.")) {
                ids.add(id);
            }
        }
        return ids;
    }

    public String previewXml(DocTypeSpec spec, String user) {
        DocTypeXml.validate(spec, catalogIds());
        return DocTypeXml.write(spec, user);
    }

    /**
     * Creates (update=false) or updates (update=true) a doc type made in the designer. The file is validated by
     * loading it into a Spring context before it replaces anything, so a broken file never reaches the folder.
     */
    public synchronized Map<String, Object> save(DocTypeSpec spec, boolean update, String user) throws IOException {
        DocTypeXml.validate(spec, catalogIds());
        String key = spec.docType().toUpperCase(Locale.ROOT);
        if (update) {
            if (!customFiles.containsKey(key)) {
                throw new IllegalArgumentException(spec.docType() + " is built into the batch jar and cannot be edited here. "
                        + "Create a new doc type (e.g. a copy with another name) instead.");
            }
        } else if (registry.contains(spec.docType()) || customFiles.containsKey(key)) {
            throw new IllegalArgumentException("A doc type named " + spec.docType() + " already exists");
        }
        for (String label : spec.classifierLabels() == null ? List.<String>of() : spec.classifierLabels()) {
            DocTypeConfig other = registry.findByClassifierLabel(label);
            if (other != null && !other.getDocType().equalsIgnoreCase(spec.docType())) {
                throw new IllegalArgumentException("Classifier label " + label + " is already used by " + other.getDocType());
            }
        }

        Files.createDirectories(doctypesDir);
        Path target = update ? customFiles.get(key) : doctypesDir.resolve(DocTypeXml.fileName(spec.docType()));
        if (!update && Files.exists(target)) {
            throw new IllegalArgumentException("File " + target.getFileName() + " already exists in " + doctypesDir);
        }
        // temp name that does not end with .xml, so a batch starting at this moment never loads a half-written file
        Path temp = doctypesDir.resolve("." + target.getFileName() + ".saving");
        Files.writeString(temp, DocTypeXml.write(spec, user), StandardCharsets.UTF_8);

        GenericXmlApplicationContext child = new GenericXmlApplicationContext();
        DocTypeConfig config;
        try {
            child.setParent(context);
            child.load(new FileSystemResource(temp));
            child.refresh();
            config = child.getBean(DocTypeConfig.class);
        } catch (RuntimeException e) {
            child.close();
            Files.deleteIfExists(temp);
            throw new IllegalArgumentException("The doc type could not be loaded: " + e.getMessage(), e);
        }
        registry.register(config, update);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        customFiles.put(key, target);
        GenericXmlApplicationContext old = children.put(key, child);
        if (old != null) {
            old.close();
        }
        log.info("Doc type {} {} by {} ({})", spec.docType(), update ? "updated" : "created", user, target);
        return docType(spec.docType());
    }

    @PreDestroy
    public void close() {
        children.values().forEach(GenericXmlApplicationContext::close);
    }

    // ------------------------------------------------------------------ Azure models

    public Map<String, String> models() {
        return docIntelClient.listCustomModels();
    }

    public Map<String, String> modelFields(String modelId) {
        return docIntelClient.modelFields(modelId);
    }

    // ------------------------------------------------------------------ settings

    /** Effective settings grouped for display. Secrets are never returned, only whether they are set. */
    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> azure = new LinkedHashMap<>();
        azure.put("Endpoint", host(env.getProperty("azure.endpoint")));
        azure.put("Authentication", isSet("azure.api-key") ? "API key (set)" : "Microsoft Entra ID (DefaultAzureCredential)");
        String classifier = env.getProperty("azure.classifier-id", "");
        azure.put("Classifier", classifier.isBlank() ? "none - every document is " + env.getProperty("classify.default-doc-type", "?")
                + " unless uploaded into a doc type folder" : classifier);
        azure.put("Proxy", env.getProperty("azure.proxy.host", "").isBlank() ? "direct connection"
                : env.getProperty("azure.proxy.host") + ":" + env.getProperty("azure.proxy.port", ""));
        azure.put("SDK retries", env.getProperty("azure.max-retries"));
        azure.put("Timeout (s)", env.getProperty("azure.timeout-seconds"));
        m.put("Azure Document Intelligence", azure);

        Map<String, Object> db = new LinkedHashMap<>();
        db.put("Connection", jdbcDisplay(env.getProperty("db.url", "")));
        db.put("User", env.getProperty("db.user", ""));
        db.put("Password", isSet("db.password") ? "set" : "not set (integrated security?)");
        db.put("Schema", "ocr (all objects of the job)");
        db.put("Create missing objects at start", env.getProperty("db.init"));
        db.put("Pool size", env.getProperty("db.pool-size"));
        try {
            Map<String, Object> info = jdbc.queryForMap("SELECT DB_NAME() AS db, @@SERVERNAME AS server, "
                    + "CAST(SERVERPROPERTY('ProductVersion') AS VARCHAR(50)) AS version, SUSER_SNAME() AS login");
            db.put("Connected to", info.get("server") + " / " + info.get("db") + " (SQL Server " + info.get("version") + ")");
            db.put("Login", info.get("login"));
        } catch (RuntimeException e) {
            db.put("Connected to", "ERROR: " + e.getMessage());
        }
        m.put("Database", db);

        Map<String, Object> proc = new LinkedHashMap<>();
        proc.put("Input folder", Path.of(env.getProperty("input.dir", "./data/input")).toAbsolutePath().normalize().toString());
        proc.put("File types", env.getProperty("input.extensions"));
        proc.put("Max file size (MB)", env.getProperty("input.max-file-mb"));
        proc.put("Parallel Azure calls", env.getProperty("batch.threads"));
        proc.put("Commit interval", env.getProperty("batch.commit-interval"));
        proc.put("Retries before FAILED", env.getProperty("retry.max-attempts"));
        proc.put("Retry delay (min, doubling)", env.getProperty("retry.base-delay-minutes") + " \u2192 max " + env.getProperty("retry.max-delay-minutes"));
        proc.put("Keep full Azure response", env.getProperty("results.store-full-json"));
        m.put("Processing", proc);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("Full Azure response (days)", env.getProperty("retention.full-json-days"));
        ret.put("History and errors (days)", env.getProperty("retention.history-days"));
        ret.put("Job run records (days)", env.getProperty("retention.batch-metadata-days"));
        m.put("Retention", ret);

        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("Address", env.getProperty("server.address", "0.0.0.0") + ":" + env.getProperty("server.port", "8080"));
        ui.put("Custom doc types folder", doctypesDir.toString());
        ui.put("Brand folder", brandDir + (Files.isRegularFile(brandDir.resolve("logo.svg")) ? " (logo found)" : " (no logo.svg)")
                + (Files.isRegularFile(brandDir.resolve("fonts.css")) ? " (fonts.css found)" : ""));
        ui.put("Run job after upload", env.getProperty("ui.run-job-on-upload", "true"));
        m.put("Demo UI", ui);
        return m;
    }

    private boolean isSet(String key) {
        String v = env.getProperty(key);
        return v != null && !v.isBlank();
    }

    private static String host(String url) {
        if (url == null || url.isBlank()) {
            return "not set";
        }
        try {
            return URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return "invalid URL";
        }
    }

    /** jdbc:sqlserver://host:1433;databaseName=x;password=... -> host:1433 / databaseName=x (credentials removed). */
    static String jdbcDisplay(String url) {
        if (url == null || url.isBlank()) {
            return "not set";
        }
        StringBuilder b = new StringBuilder();
        for (String part : url.split(";")) {
            String p = part.toLowerCase(Locale.ROOT);
            if (p.startsWith("password") || p.startsWith("user") || p.contains("secret") || p.contains("key")) {
                continue;
            }
            b.append(b.length() == 0 ? "" : ";").append(part);
        }
        return b.toString();
    }
}
