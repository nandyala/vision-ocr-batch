package com.visionocr.mapping;

import com.visionocr.config.DocTypeConfig;
import com.visionocr.config.FieldMapping;
import com.visionocr.domain.DocumentRecord;
import com.visionocr.domain.ExtractionOutput;
import com.visionocr.domain.FieldStatus;
import com.visionocr.domain.MappedDocument;
import com.visionocr.domain.MappedField;
import com.visionocr.domain.RawField;
import com.visionocr.validation.AbaRoutingNumberValidator;
import com.visionocr.validation.FuzzyMatchRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldMappingServiceTest {

    private final FieldMappingService service = new FieldMappingService();

    private DocTypeConfig config() {
        FieldMapping routing = new FieldMapping();
        routing.setAzureField("RoutingNumber");
        routing.setCanonicalField("routingNumber");
        routing.setRequired(true);
        routing.setMinConfidence(0.9);
        routing.setNormalizers(List.of(new DigitsOnlyNormalizer()));
        routing.setValidators(List.of(new AbaRoutingNumberValidator()));

        FieldMapping name = new FieldMapping();
        name.setAzureField("CustomerName");
        name.setCanonicalField("customerName");
        name.setRequired(true);
        name.setNormalizers(List.of(new WhitespaceNormalizer(), new CaseNormalizer("UPPER")));

        FieldMapping holder = new FieldMapping();
        holder.setAzureField("BankAccountHolderName");
        holder.setCanonicalField("bankAccountHolderName");
        holder.setNormalizers(List.of(new WhitespaceNormalizer(), new CaseNormalizer("UPPER")));

        DocTypeConfig c = new DocTypeConfig();
        c.setDocType("AUTO_PAY_AUTH");
        c.setFields(List.of(routing, name, holder));
        c.setCrossFieldRules(List.of(new FuzzyMatchRule("customerName", "bankAccountHolderName", 0.8, "NAME_MISMATCH")));
        return c;
    }

    private static ExtractionOutput output(String routing, double routingConf, String name, String holder) {
        ExtractionOutput out = new ExtractionOutput();
        out.setDocConfidence(0.9);
        out.getFields().put("RoutingNumber", new RawField("RoutingNumber", routing, routing, routingConf, "string"));
        if (name != null) {
            out.getFields().put("CustomerName", new RawField("CustomerName", name, name, 0.95, "string"));
        }
        out.getFields().put("BankAccountHolderName", new RawField("BankAccountHolderName", holder, holder, 0.95, "string"));
        return out;
    }

    @Test
    void cleanDocumentHasNoReviewReasons() {
        DocumentRecord doc = new DocumentRecord();
        MappedDocument m = service.map(config(), doc, output("|: 0210-0002-1 |:", 0.97, "John  Q Sample", "John Q. Sample"), null);

        assertTrue(doc.getReviewReasons().isEmpty(), doc.getReviewReasons().toString());
        MappedField routing = m.getFields().get(0);
        assertEquals("021000021", routing.getValue());
        assertEquals(FieldStatus.OK, routing.getStatus());
        assertEquals("JOHN Q SAMPLE", m.getFields().get(1).getValue());
    }

    @Test
    void problemsBecomeReviewReasons() {
        DocumentRecord doc = new DocumentRecord();
        service.map(config(), doc, output("021000022", 0.97, null, "Robert Lopez"), null);

        assertTrue(doc.getReviewReasons().contains("INVALID:routingNumber:ROUTING_CHECKSUM_FAILED"), doc.getReviewReasons().toString());
        assertTrue(doc.getReviewReasons().contains("MISSING:customerName"));
    }

    @Test
    void lowConfidenceIsFlagged() {
        DocumentRecord doc = new DocumentRecord();
        service.map(config(), doc, output("021000021", 0.50, "Ann Smith", "Ann Smith"), null);
        assertEquals(List.of("LOW_CONFIDENCE:routingNumber"), doc.getReviewReasons());
    }

    @Test
    void nameMismatchIsFlagged() {
        DocumentRecord doc = new DocumentRecord();
        service.map(config(), doc, output("021000021", 0.97, "Maria Lopez", "Robert Lopez"), null);
        assertEquals(List.of("NAME_MISMATCH"), doc.getReviewReasons());
    }

    @Test
    void unmappedFieldsArePassedThrough() {
        DocumentRecord doc = new DocumentRecord();
        ExtractionOutput out = output("021000021", 0.97, "Ann Smith", "Ann Smith");
        out.getFields().put("ReferenceCode", new RawField("ReferenceCode", "  REF-123 ", null, 0.91, "string"));
        MappedDocument m = service.map(config(), doc, out, null);

        MappedField extra = m.getFields().stream().filter(f -> f.getName().equals("ReferenceCode")).findFirst().orElseThrow();
        assertEquals("REF-123", extra.getValue());
        assertEquals(false, extra.isConfigured());
        assertEquals(4, m.getFields().size(), "3 configured + 1 pass-through");
        assertTrue(doc.getReviewReasons().isEmpty(), doc.getReviewReasons().toString());
    }

    @Test
    void unmappedFieldsCanBeSwitchedOff() {
        DocTypeConfig c = config();
        c.setIncludeUnmappedFields(false);
        ExtractionOutput out = output("021000021", 0.97, "Ann Smith", "Ann Smith");
        out.getFields().put("ReferenceCode", new RawField("ReferenceCode", "REF-123", null, 0.91, "string"));
        assertEquals(3, service.map(c, new DocumentRecord(), out, null).getFields().size());
    }
}
