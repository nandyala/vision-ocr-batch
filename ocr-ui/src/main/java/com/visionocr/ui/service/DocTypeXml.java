package com.visionocr.ui.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates a {@link DocTypeSpec} and writes it as a Spring XML doc type file - the same format as
 * ocr-batch/src/main/resources/doctypes/auto-pay-auth.xml.
 */
public final class DocTypeXml {

    private static final Pattern DOC_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._~-]{1,63}");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern CANONICAL = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,99}");

    private DocTypeXml() {
    }

    /** File name for a doc type: DISPUTE_FORM -> dispute-form.xml */
    public static String fileName(String docType) {
        return docType.toLowerCase(Locale.ROOT).replace('_', '-') + ".xml";
    }

    /**
     * Checks the spec; throws IllegalArgumentException with a message the UI can show.
     *
     * @param knownBeans ids of the normalizer/validator beans that may be referenced
     */
    public static void validate(DocTypeSpec s, Set<String> knownBeans) {
        require(s != null, "Nothing to save");
        require(s.docType() != null && DOC_TYPE.matcher(s.docType()).matches(),
                "Doc type name: 2-50 characters, capital letters, digits and _ , starting with a letter (e.g. DISPUTE_FORM)");
        require(s.modelId() != null && MODEL_ID.matcher(s.modelId()).matches(),
                "Model id: 2-64 characters, letters, digits, . _ ~ - (the id of the trained model in Azure)");
        text(s.description(), 200, "Description");
        for (String l : nz(s.classifierLabels())) {
            require(LABEL.matcher(l).matches(), "Classifier label '" + l + "': letters, digits, _ . - only");
        }
        range(s.minClassifyConfidence(), "Minimum classification confidence");
        range(s.minDocumentConfidence(), "Minimum document confidence");
        range(s.defaultMinConfidence(), "Default field confidence");
        Set<String> names = new HashSet<>();
        Set<String> azure = new HashSet<>();
        for (DocTypeSpec.Field f : nz(s.fields())) {
            require(f.azureField() != null && !f.azureField().isBlank() && f.azureField().length() <= 100,
                    "Every field needs the field name used in the Azure model (max 100 characters)");
            text(f.azureField(), 100, "Azure field");
            String canonical = canonical(f);
            require(CANONICAL.matcher(canonical).matches(),
                    "Stored name '" + canonical + "': letters, digits and _ , starting with a letter");
            require(names.add(canonical.toLowerCase(Locale.ROOT)), "Field '" + canonical + "' is listed twice");
            require(azure.add(f.azureField()), "Azure field '" + f.azureField() + "' is listed twice");
            range(f.minConfidence(), "Minimum confidence of " + canonical);
            for (String id : nz(f.normalizers())) {
                require(knownBeans.contains(id), "Unknown clean-up step " + id);
            }
            for (String id : nz(f.validators())) {
                require(knownBeans.contains(id), "Unknown check " + id);
            }
            if (f.regex() != null && !f.regex().isBlank()) {
                text(f.regex(), 300, "Pattern of " + canonical);
                try {
                    Pattern.compile(f.regex());
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("Pattern of " + canonical + " is not a valid regular expression: "
                            + e.getDescription());
                }
                text(f.regexMessage(), 100, "Pattern message of " + canonical);
            }
            for (String v : nz(f.allowedValues())) {
                text(v, 100, "Allowed value of " + canonical);
            }
            text(f.allowedMessage(), 100, "Allowed values message of " + canonical);
        }
    }

    public static String write(DocTypeSpec s, String author) {
        StringBuilder x = new StringBuilder(4096);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<!--\n  DOC TYPE: ").append(comment(s.docType()));
        if (s.description() != null && !s.description().isBlank()) {
            x.append(" - ").append(comment(s.description()));
        }
        x.append("\n  Created with the Vision OCR demo UI by ").append(comment(author)).append(" on ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(".\n")
                .append("  Loaded from the doctypes.dir folder. To ship it with the job, move it to\n")
                .append("  ocr-batch/src/main/resources/doctypes/.\n-->\n");
        x.append("<beans xmlns=\"http://www.springframework.org/schema/beans\"\n")
                .append("       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
                .append("       xsi:schemaLocation=\"http://www.springframework.org/schema/beans ")
                .append("https://www.springframework.org/schema/beans/spring-beans.xsd\">\n\n");
        x.append("    <bean id=\"docType.").append(s.docType()).append("\" class=\"com.visionocr.config.DocTypeConfig\">\n");
        prop(x, 8, "docType", s.docType());
        if (s.description() != null && !s.description().isBlank()) {
            prop(x, 8, "description", s.description().trim());
        }
        prop(x, 8, "enabled", String.valueOf(!Boolean.FALSE.equals(s.enabled())));
        List<String> labels = nz(s.classifierLabels());
        if (!labels.isEmpty()) {
            x.append("        <property name=\"classifierLabels\">\n            <list>\n");
            for (String l : labels) {
                x.append("                <value>").append(esc(l)).append("</value>\n");
            }
            x.append("            </list>\n        </property>\n");
        }
        prop(x, 8, "modelId", s.modelId());
        prop(x, 8, "minClassifyConfidence", num(s.minClassifyConfidence(), 0.70));
        prop(x, 8, "minDocumentConfidence", num(s.minDocumentConfidence(), 0.0));
        prop(x, 8, "includeUnmappedFields", String.valueOf(!Boolean.FALSE.equals(s.includeUnmappedFields())));
        prop(x, 8, "defaultMinConfidence", num(s.defaultMinConfidence(), 0.0));

        List<DocTypeSpec.Field> fields = nz(s.fields());
        if (!fields.isEmpty()) {
            x.append("\n        <property name=\"fields\">\n            <list>\n");
            for (DocTypeSpec.Field f : fields) {
                x.append("                <bean class=\"com.visionocr.config.FieldMapping\">\n");
                prop(x, 20, "azureField", f.azureField().trim());
                prop(x, 20, "canonicalField", canonical(f));
                prop(x, 20, "required", String.valueOf(Boolean.TRUE.equals(f.required())));
                prop(x, 20, "minConfidence", num(f.minConfidence(), 0.0));
                List<String> norms = nz(f.normalizers());
                if (!norms.isEmpty()) {
                    x.append("                    <property name=\"normalizers\">\n                        <list>");
                    for (String id : norms) {
                        x.append("<ref bean=\"").append(esc(id)).append("\"/>");
                    }
                    x.append("</list>\n                    </property>\n");
                }
                boolean regex = f.regex() != null && !f.regex().isBlank();
                boolean allowed = !nz(f.allowedValues()).isEmpty();
                if (!nz(f.validators()).isEmpty() || regex || allowed) {
                    x.append("                    <property name=\"validators\">\n                        <list>\n");
                    for (String id : nz(f.validators())) {
                        x.append("                            <ref bean=\"").append(esc(id)).append("\"/>\n");
                    }
                    if (regex) {
                        x.append("                            <bean class=\"com.visionocr.validation.RegexValidator\">\n")
                                .append("                                <constructor-arg value=\"").append(esc(f.regex())).append("\"/>\n")
                                .append("                                <constructor-arg value=\"")
                                .append(esc(blank(f.regexMessage()) ? "BAD_FORMAT" : f.regexMessage().trim())).append("\"/>\n")
                                .append("                            </bean>\n");
                    }
                    if (allowed) {
                        x.append("                            <bean class=\"com.visionocr.validation.AllowedValuesValidator\">\n")
                                .append("                                <constructor-arg>\n                                    <list>");
                        for (String v : f.allowedValues()) {
                            x.append("<value>").append(esc(v.trim())).append("</value>");
                        }
                        x.append("</list>\n                                </constructor-arg>\n")
                                .append("                                <constructor-arg value=\"")
                                .append(esc(blank(f.allowedMessage()) ? "NOT_AN_ALLOWED_VALUE" : f.allowedMessage().trim()))
                                .append("\"/>\n                            </bean>\n");
                    }
                    x.append("                        </list>\n                    </property>\n");
                }
                x.append("                </bean>\n");
            }
            x.append("            </list>\n        </property>\n");
        }
        x.append("    </bean>\n</beans>\n");
        return x.toString();
    }

    static String canonical(DocTypeSpec.Field f) {
        if (!blank(f.canonicalField())) {
            return f.canonicalField().trim();
        }
        // "Customer Name" / "CustomerName" -> customerName
        String[] parts = f.azureField().trim().split("[^A-Za-z0-9]+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            b.append(b.length() == 0 ? Character.toLowerCase(p.charAt(0)) + p.substring(1)
                    : Character.toUpperCase(p.charAt(0)) + p.substring(1));
        }
        return b.length() == 0 ? "field" : b.toString();
    }

    private static void prop(StringBuilder x, int indent, String name, String value) {
        x.append(" ".repeat(indent)).append("<property name=\"").append(name).append("\" value=\"").append(esc(value)).append("\"/>\n");
    }

    private static String num(Double d, double dflt) {
        double v = d == null ? dflt : d;
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** XML attribute/text escaping. */
    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String comment(String s) {
        return s == null ? "" : s.replace("--", "- -").replaceAll("[\\r\\n]", " ");
    }

    private static void text(String s, int max, String what) {
        if (s == null) {
            return;
        }
        require(s.length() <= max, what + " is too long (max " + max + " characters)");
        // ${...} would be read as a property placeholder when the file is loaded at startup
        require(!s.contains("${"), what + " must not contain ${");
        require(s.chars().noneMatch(c -> c < 0x20 && c != '\t'), what + " must not contain control characters");
    }

    private static void range(Double d, String what) {
        require(d == null || (d >= 0 && d <= 1), what + " must be between 0 and 1");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
