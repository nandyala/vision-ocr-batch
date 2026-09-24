package com.visionocr.ui.web;

import com.visionocr.ui.service.DataService;
import com.visionocr.ui.service.InsightsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Overview numbers, model quality, correction log and the per doc type data grid (+ CSV exports). */
@RestController
@RequestMapping("/api")
public class InsightsController {

    private final InsightsService insights;
    private final DataService data;

    public InsightsController(InsightsService insights, DataService data) {
        this.insights = insights;
        this.data = data;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(name = "days", defaultValue = "14") int days) {
        return insights.overview(days);
    }

    @GetMapping("/insights/fields")
    public List<Map<String, Object>> fieldQuality(@RequestParam(name = "docType", required = false) String docType) {
        return insights.fieldQuality(docType);
    }

    @GetMapping("/corrections")
    public Map<String, Object> corrections(@RequestParam(name = "docType", required = false) String docType,
                                           @RequestParam(name = "field", required = false) String field,
                                           @RequestParam(name = "reason", required = false) String reason,
                                           @RequestParam(name = "user", required = false) String user,
                                           @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive,
                                           @RequestParam(name = "page", defaultValue = "0") int page,
                                           @RequestParam(name = "size", defaultValue = "25") int size) {
        return insights.corrections(docType, field, reason, user, includeInactive, page, size);
    }

    @GetMapping("/corrections/stats")
    public Map<String, Object> correctionStats() {
        return insights.correctionStats();
    }

    @GetMapping("/corrections/export")
    public void correctionsCsv(@RequestParam(name = "docType", required = false) String docType, HttpServletResponse response) throws IOException {
        csv(response, "corrections-" + LocalDate.now() + ".csv");
        try (Writer w = response.getWriter()) {
            w.write('﻿');   // BOM so Excel opens UTF-8 correctly
            insights.correctionsCsv(docType, w);
        }
    }

    @GetMapping("/data")
    public List<Map<String, Object>> dataDocTypes() {
        return data.docTypes();
    }

    @GetMapping("/data/{docType}")
    public Map<String, Object> grid(@PathVariable("docType") String docType,
                                    @RequestParam(name = "status", required = false) String status,
                                    @RequestParam(name = "q", required = false) String q,
                                    @RequestParam(name = "page", defaultValue = "0") int page,
                                    @RequestParam(name = "size", defaultValue = "50") int size) {
        return data.grid(docType, status, q, page, size);
    }

    @GetMapping("/data/{docType}/export")
    public void gridCsv(@PathVariable("docType") String docType, HttpServletResponse response) throws IOException {
        csv(response, docType.toLowerCase().replaceAll("[^a-z0-9_]", "_") + "-" + LocalDate.now() + ".csv");
        try (Writer w = response.getWriter()) {
            w.write('﻿');
            data.csv(docType, w);
        }
    }

    private static void csv(HttpServletResponse response, String fileName) {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
    }
}
