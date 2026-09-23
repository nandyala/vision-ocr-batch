package com.visionocr.validation;

/**
 * US ABA routing number: 9 digits and 3*(d1+d4+d7) + 7*(d2+d5+d8) + (d3+d6+d9) must be divisible by 10.
 * Catches most single-digit OCR/handwriting misreads.
 */
public class AbaRoutingNumberValidator implements FieldValidator {

    @Override
    public String validate(String value) {
        if (value == null || !value.matches("\\d{9}")) {
            return "ROUTING_NOT_9_DIGITS";
        }
        return isValid(value) ? null : "ROUTING_CHECKSUM_FAILED";
    }

    public static boolean isValid(String aba) {
        if (aba == null || !aba.matches("\\d{9}")) {
            return false;
        }
        int[] d = new int[9];
        for (int i = 0; i < 9; i++) {
            d[i] = aba.charAt(i) - '0';
        }
        int sum = 3 * (d[0] + d[3] + d[6]) + 7 * (d[1] + d[4] + d[7]) + (d[2] + d[5] + d[8]);
        return sum % 10 == 0 && sum > 0;
    }
}
