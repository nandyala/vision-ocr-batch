package com.visionocr.ui.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocTypeXmlTest {

    private static final Set<String> BEANS = Set.of("norm.whitespace", "norm.upper", "val.signed");

    private static DocTypeSpec spec(String docType, String description, DocTypeSpec.Field... fields) {
        return new DocTypeSpec(docType, description, true, "dispute-neural-v1", List.of("dispute_form"), 0.8, 0.6, true, 0.0, List.of(fields));
    }

    private static DocTypeSpec.Field field(String azure, String regex) {
        return new DocTypeSpec.Field(azure, null, true, 0.85, List.of("norm.whitespace"), List.of(), regex, "BAD <FORMAT>", null, null);
    }

    @Test
    void writesEscapedXmlWithDerivedFieldNames() {
        DocTypeSpec s = spec("DISPUTE_FORM", "Card \"dispute\" & chargeback", field("Customer Name", "\\d{4}"));
        DocTypeXml.validate(s, BEANS);
        String xml = DocTypeXml.write(s, "Jane");
        assertTrue(xml.contains("<bean id=\"docType.DISPUTE_FORM\""));
        assertTrue(xml.contains("value=\"Card &quot;dispute&quot; &amp; chargeback\""));
        assertTrue(xml.contains("<property name=\"canonicalField\" value=\"customerName\"/>"));
        assertTrue(xml.contains("<constructor-arg value=\"BAD &lt;FORMAT&gt;\"/>"));
        assertTrue(xml.contains("<ref bean=\"norm.whitespace\"/>"));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> DocTypeXml.validate(spec("bad name", null), BEANS));
        assertThrows(IllegalArgumentException.class, () -> DocTypeXml.validate(spec("DISPUTE", "${db.password}"), BEANS));
        assertThrows(IllegalArgumentException.class, () -> DocTypeXml.validate(spec("DISPUTE", null, field("A", "([")), BEANS));
        assertThrows(IllegalArgumentException.class, () -> DocTypeXml.validate(spec("DISPUTE", null, field("A", null), field("A", null)), BEANS));
        DocTypeSpec.Field unknown = new DocTypeSpec.Field("A", null, false, 0.0, List.of("norm.nope"), List.of(), null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> DocTypeXml.validate(spec("DISPUTE", null, unknown), BEANS));
    }

    @Test
    void fileName() {
        assertEquals("dispute-form.xml", DocTypeXml.fileName("DISPUTE_FORM"));
    }
}
