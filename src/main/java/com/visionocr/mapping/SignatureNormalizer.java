package com.visionocr.mapping;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a signature field into "signed" / "unsigned", whatever type the Azure model uses for it:
 * <ul>
 *   <li>signature type: Azure already returns "signed" / "unsigned" - kept as is</li>
 *   <li>string type: the OCR text of the signature box - any real ink/text means "signed";
 *       empty, or only the printed guide marks ("X", underscores, the word "signature") means "unsigned"</li>
 * </ul>
 */
public class SignatureNormalizer implements FieldNormalizer {

    /** Printed guide marks that are not a signature: X, lines, dots, colons, the caption itself. */
    private static final Pattern NOT_A_SIGNATURE =
            Pattern.compile("^[\\sxX_\\-.:;,|/\\\\*]*(signature|sign here|signed by)?[\\sxX_\\-.:;,|/\\\\*]*$",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public String normalize(String value) {
        if (value == null) {
            return "unsigned";
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("signed") || v.equals("unsigned")) {
            return v;
        }
        return NOT_A_SIGNATURE.matcher(v).matches() ? "unsigned" : "signed";
    }
}
