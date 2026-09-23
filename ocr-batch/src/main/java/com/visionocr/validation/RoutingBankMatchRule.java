package com.visionocr.validation;

import com.visionocr.domain.MappedField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional: checks that the bank name written on the form matches the bank that owns the routing number.
 * Needs a CSV "routingNumber,bankName" (e.g. exported from the Fed E-Payments Routing Directory).
 * When no file is configured the rule is a no-op.
 */
public class RoutingBankMatchRule implements CrossFieldRule {

    private static final Logger log = LoggerFactory.getLogger(RoutingBankMatchRule.class);

    private final String routingField;
    private final String bankNameField;
    private final double minSimilarity;
    private final Map<String, String> directory = new HashMap<>();

    public RoutingBankMatchRule(String routingField, String bankNameField, double minSimilarity, String directoryCsv) {
        this.routingField = routingField;
        this.bankNameField = bankNameField;
        this.minSimilarity = minSimilarity;
        if (directoryCsv != null && !directoryCsv.isBlank()) {
            load(Path.of(directoryCsv));
        }
    }

    private void load(Path csv) {
        if (!Files.isRegularFile(csv)) {
            log.warn("Routing directory {} not found - routing/bank cross-check disabled", csv);
            return;
        }
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] parts = line.split(",", 2);
                if (parts.length == 2 && parts[0].trim().matches("\\d{9}")) {
                    directory.put(parts[0].trim(), parts[1].trim().replace("\"", ""));
                }
            }
            log.info("Loaded {} routing numbers from {}", directory.size(), csv);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read routing directory " + csv, e);
        }
    }

    @Override
    public String check(Map<String, MappedField> fields) {
        if (directory.isEmpty()) {
            return null;
        }
        MappedField routing = fields.get(routingField);
        MappedField bank = fields.get(bankNameField);
        if (routing == null || !routing.hasValue()) {
            return null;
        }
        String owner = directory.get(routing.getValue());
        if (owner == null) {
            return "ROUTING_NOT_IN_DIRECTORY";
        }
        if (bank == null || !bank.hasValue()) {
            return null;
        }
        return FuzzyMatchRule.similarity(owner, bank.getValue()) >= minSimilarity ? null : "BANK_NAME_ROUTING_MISMATCH";
    }
}
