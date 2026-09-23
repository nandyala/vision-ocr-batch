package com.visionocr.ui.service;

/** Minimal CSV encoding (RFC 4180) that is also safe to open in Excel (no formula injection). */
final class Csv {

    private Csv() {
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.contains("'")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
