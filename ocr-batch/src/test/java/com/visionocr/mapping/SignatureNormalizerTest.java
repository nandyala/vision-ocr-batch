package com.visionocr.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignatureNormalizerTest {

    private final SignatureNormalizer n = new SignatureNormalizer();

    @Test
    void anyWritingCountsAsSigned() {
        assertEquals("signed", n.normalize("Jane Doe"));
        assertEquals("signed", n.normalize("X  J. Doe"));
        assertEquals("signed", n.normalize("~~/jd"));
    }

    @Test
    void emptyOrGuideMarksAreUnsigned() {
        assertEquals("unsigned", n.normalize(null));
        assertEquals("unsigned", n.normalize(""));
        assertEquals("unsigned", n.normalize("X"));
        assertEquals("unsigned", n.normalize(" x ______ "));
        assertEquals("unsigned", n.normalize("SIGNATURE"));
        assertEquals("unsigned", n.normalize("Signature: X ____"));
    }

    @Test
    void signatureTypedValuesPassThrough() {
        assertEquals("signed", n.normalize("signed"));
        assertEquals("unsigned", n.normalize("UNSIGNED"));
    }
}
