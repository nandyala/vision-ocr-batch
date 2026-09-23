package com.visionocr.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.visionocr.domain.ClassificationOutput;
import com.visionocr.domain.ClassificationResult;
import com.visionocr.domain.ExtractionOutput;
import com.visionocr.domain.RawField;
import com.visionocr.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only offline stand-in for Azure. For input file X it reads "X.stub.json":
 * <pre>
 * { "classifierLabel": "auto_pay_auth", "classifyConfidence": 0.97, "pages": "1",
 *   "docConfidence": 0.95,
 *   "fields": { "RoutingNumber": { "value": "021000021", "confidence": 0.93, "type": "string" } } }
 * </pre>
 * Failure simulation: "failExtractTimes": 2 makes the first 2 analyze calls throw a retryable
 * (HTTP 429-like) error, "failExtract": "permanent" makes every call throw a non-retryable one.
 * Lets you run and test the whole pipeline end-to-end without an Azure subscription.
 */
public class StubDocIntelClient implements DocIntelClient {

    private final Map<String, AtomicInteger> extractCalls = new ConcurrentHashMap<>();
    /** Call counters are also kept in the temp dir so the simulation works across separate job runs (JVMs). */
    private final Path counterDir = Path.of(System.getProperty("java.io.tmpdir"), "visionocr-stub");

    @Override
    public String classifierId() {
        return "stub-classifier";
    }

    @Override
    public ClassificationOutput classify(Path file) {
        JsonNode n = sidecar(file);
        return new ClassificationOutput(List.of(new ClassificationResult(
                n.path("classifierLabel").asText("other"),
                n.path("classifyConfidence").asDouble(0.0),
                n.hasNonNull("pages") ? n.get("pages").asText() : null)),
                "{\"stub\":\"classify\",\"label\":\"" + n.path("classifierLabel").asText("other") + "\"}");
    }

    @Override
    public ExtractionOutput analyze(String modelId, Path file, String pages) {
        JsonNode n = sidecar(file);
        if ("permanent".equals(n.path("failExtract").asText())) {
            throw new DocIntelException("Stub: simulated invalid document (HTTP 400)", false);
        }
        int calls = nextCallNumber(file);
        if (calls <= n.path("failExtractTimes").asInt(0)) {
            throw new DocIntelException("Stub: simulated throttling (HTTP 429), call " + calls, true);
        }
        ExtractionOutput out = new ExtractionOutput();
        out.setModelDocType(modelId);
        out.setDocConfidence(n.path("docConfidence").asDouble(0.0));
        out.setRawResultJson(n.toString());
        Iterator<Map.Entry<String, JsonNode>> it = n.path("fields").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode f = e.getValue();
            out.getFields().put(e.getKey(), new RawField(e.getKey(),
                    f.hasNonNull("value") ? f.get("value").asText() : null,
                    f.hasNonNull("content") ? f.get("content").asText() : null,
                    f.hasNonNull("confidence") ? f.get("confidence").asDouble() : null,
                    f.path("type").asText("string")));
        }
        return out;
    }

    private synchronized int nextCallNumber(Path file) {
        AtomicInteger counter = extractCalls.computeIfAbsent(file.toAbsolutePath().toString(), k -> new AtomicInteger());
        Path store = counterDir.resolve(Integer.toHexString(file.toAbsolutePath().toString().hashCode()) + ".count");
        try {
            if (counter.get() == 0 && Files.exists(store)) {
                counter.set(Integer.parseInt(Files.readString(store).trim()));
            }
            int n = counter.incrementAndGet();
            Files.createDirectories(counterDir);
            Files.writeString(store, String.valueOf(n));
            return n;
        } catch (IOException | NumberFormatException e) {
            return counter.incrementAndGet();
        }
    }

    private static JsonNode sidecar(Path file) {
        Path json = file.resolveSibling(file.getFileName() + ".stub.json");
        if (!Files.exists(json)) {
            throw new DocIntelException("Stub mode: no sidecar " + json.getFileName(), false);
        }
        try {
            return Json.mapper().readTree(json.toFile());
        } catch (IOException e) {
            throw new DocIntelException("Stub mode: bad sidecar " + json, e);
        }
    }
}
