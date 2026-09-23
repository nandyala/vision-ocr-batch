package com.visionocr.mapping;

/**
 * Keeps digits only. Handles MICR symbols, the dash/box guides on the routing
 * number line, spaces between handwritten digits, etc. Also maps common OCR
 * confusions (O->0, l/I->1) when they appear between digits.
 */
public class DigitsOnlyNormalizer implements FieldNormalizer {

    private boolean fixOcrConfusions = true;

    @Override
    public String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value;
        if (fixOcrConfusions) {
            v = v.replaceAll("(?<=\\d)[Oo](?=\\d)", "0").replaceAll("(?<=\\d)[lI|](?=\\d)", "1");
        }
        return v.replaceAll("\\D", "");
    }

    public void setFixOcrConfusions(boolean fixOcrConfusions) {
        this.fixOcrConfusions = fixOcrConfusions;
    }
}
