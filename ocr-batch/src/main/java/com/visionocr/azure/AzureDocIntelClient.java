package com.visionocr.azure;

import com.azure.ai.documentintelligence.DocumentIntelligenceAdministrationClient;
import com.azure.ai.documentintelligence.DocumentIntelligenceAdministrationClientBuilder;
import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.DocumentIntelligenceClientBuilder;
import com.azure.ai.documentintelligence.models.AnalyzeDocumentOptions;
import com.azure.ai.documentintelligence.models.AnalyzeOperationDetails;
import com.azure.ai.documentintelligence.models.AnalyzeResult;
import com.azure.ai.documentintelligence.models.AnalyzedDocument;
import com.azure.ai.documentintelligence.models.BoundingRegion;
import com.azure.ai.documentintelligence.models.ClassifyDocumentOptions;
import com.azure.ai.documentintelligence.models.DocumentField;
import com.azure.ai.documentintelligence.models.DocumentFieldSchema;
import com.azure.ai.documentintelligence.models.DocumentModelDetails;
import com.azure.ai.documentintelligence.models.DocumentTypeDetails;
import com.azure.ai.documentintelligence.models.DocumentFieldType;
import com.azure.ai.documentintelligence.models.SplitMode;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.ProxyOptions;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.util.HttpClientOptions;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.json.JsonProviders;
import com.azure.json.JsonWriter;
import com.visionocr.domain.ClassificationOutput;
import com.visionocr.domain.ClassificationResult;
import com.visionocr.domain.ExtractionOutput;
import com.visionocr.domain.RawField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Azure Document Intelligence v4.0 (API 2024-11-30) implementation.
 *
 * Auth: API key when azure.api-key is set, otherwise DefaultAzureCredential
 * (managed identity in Azure, az login locally) - preferred for production.
 * HTTP 429/5xx are retried by the SDK with exponential backoff.
 * Corporate proxy: set azure.proxy.host / azure.proxy.port (and user/password if required).
 */
public class AzureDocIntelClient implements DocIntelClient {

    private static final Logger log = LoggerFactory.getLogger(AzureDocIntelClient.class);

    private final DocumentIntelligenceClient client;
    /** Model management API (list models, read a model's field schema); only used by the demo UI. */
    private final DocumentIntelligenceAdministrationClient adminClient;
    private final String classifierId;
    private final Duration pollInterval;
    private final Duration timeout;

    public AzureDocIntelClient(String endpoint, String apiKey, String classifierId,
                               int maxRetries, int pollIntervalSeconds, int timeoutSeconds,
                               String proxyHost, String proxyPort, String proxyUser, String proxyPassword) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("azure.endpoint (AZURE_DI_ENDPOINT) is not set");
        }
        RetryOptions retry = new RetryOptions(new ExponentialBackoffOptions()
                .setMaxRetries(maxRetries)
                .setBaseDelay(Duration.ofSeconds(2))
                .setMaxDelay(Duration.ofSeconds(60)));
        DocumentIntelligenceClientBuilder builder = new DocumentIntelligenceClientBuilder()
                .endpoint(endpoint)
                .retryOptions(retry);
        DocumentIntelligenceAdministrationClientBuilder adminBuilder = new DocumentIntelligenceAdministrationClientBuilder()
                .endpoint(endpoint)
                .retryOptions(retry);
        if (proxyHost != null && !proxyHost.isBlank()) {
            int port = proxyPort == null || proxyPort.isBlank() ? 8080 : Integer.parseInt(proxyPort.trim());
            ProxyOptions proxy = new ProxyOptions(ProxyOptions.Type.HTTP, new InetSocketAddress(proxyHost.trim(), port));
            if (proxyUser != null && !proxyUser.isBlank()) {
                proxy.setCredentials(proxyUser, proxyPassword == null ? "" : proxyPassword);
            }
            HttpClient http = HttpClient.createDefault(new HttpClientOptions().setProxyOptions(proxy));
            builder.httpClient(http);
            adminBuilder.httpClient(http);
            log.info("Using proxy {}:{} for Azure calls", proxyHost.trim(), port);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            AzureKeyCredential key = new AzureKeyCredential(apiKey);
            builder.credential(key);
            adminBuilder.credential(key);
        } else {
            TokenCredential entra = new DefaultAzureCredentialBuilder().build();
            builder.credential(entra);
            adminBuilder.credential(entra);
        }
        this.client = builder.buildClient();
        this.adminClient = adminBuilder.buildClient();
        this.classifierId = classifierId;
        this.pollInterval = Duration.ofSeconds(pollIntervalSeconds);
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        log.info("Azure Document Intelligence client ready (endpoint={}, classifier={}, auth={})",
                endpoint, classifierId, apiKey != null && !apiKey.isBlank() ? "key" : "entra-id");
    }

    @Override
    public String classifierId() {
        return classifierId;
    }

    @Override
    public Map<String, String> listCustomModels() {
        Map<String, String> models = new TreeMap<>();
        for (DocumentModelDetails m : adminClient.listModels()) {
            if (m.getModelId() != null && !m.getModelId().startsWith("prebuilt-")) {
                models.put(m.getModelId(), m.getDescription() == null ? "" : m.getDescription());
            }
        }
        return models;
    }

    @Override
    public Map<String, String> modelFields(String modelId) {
        DocumentModelDetails model = adminClient.getModel(modelId);
        Map<String, String> fields = new LinkedHashMap<>();
        if (model.getDocumentTypes() != null) {
            for (DocumentTypeDetails type : model.getDocumentTypes().values()) {
                if (type.getFieldSchema() == null) {
                    continue;
                }
                for (Map.Entry<String, DocumentFieldSchema> f : type.getFieldSchema().entrySet()) {
                    fields.putIfAbsent(f.getKey(), f.getValue().getType() == null ? "string" : f.getValue().getType().toString());
                }
            }
        }
        return fields;
    }

    @Override
    public ClassificationOutput classify(Path file) {
        ClassifyDocumentOptions options = new ClassifyDocumentOptions(readBytes(file)).setSplit(SplitMode.AUTO);
        AnalyzeResult result = await(client.beginClassifyDocument(classifierId, options), "classify " + file.getFileName());

        List<ClassificationResult> out = new ArrayList<>();
        if (result.getDocuments() != null) {
            for (AnalyzedDocument d : result.getDocuments()) {
                out.add(new ClassificationResult(d.getDocumentType(), d.getConfidence(), pageRange(d.getBoundingRegions())));
            }
        }
        return new ClassificationOutput(out, toJson(result));
    }

    @Override
    public ExtractionOutput analyze(String modelId, Path file, String pages) {
        AnalyzeDocumentOptions options = new AnalyzeDocumentOptions(readBytes(file));
        if (pages != null && !pages.isBlank()) {
            options.setPages(Arrays.asList(pages.split(",")));
        }
        AnalyzeResult result = await(client.beginAnalyzeDocument(modelId, options), "analyze " + file.getFileName());

        ExtractionOutput out = new ExtractionOutput();
        out.setRawResultJson(toJson(result));
        if (result.getDocuments() == null || result.getDocuments().isEmpty()) {
            out.setDocConfidence(0.0);
            return out;
        }
        // A composed/neural model can return several documents; take the most confident one.
        AnalyzedDocument best = result.getDocuments().get(0);
        for (AnalyzedDocument d : result.getDocuments()) {
            if (d.getConfidence() > best.getConfidence()) {
                best = d;
            }
        }
        out.setModelDocType(best.getDocumentType());
        out.setDocConfidence(best.getConfidence());
        if (best.getFields() != null) {
            for (Map.Entry<String, DocumentField> e : best.getFields().entrySet()) {
                flatten(e.getKey(), e.getValue(), out.getFields());
            }
        }
        return out;
    }

    /** Objects become "Parent.Child", arrays "Parent[0].Child" - so table fields can be mapped too. */
    private void flatten(String name, DocumentField f, Map<String, RawField> target) {
        if (f == null) {
            return;
        }
        DocumentFieldType type = f.getType();
        if (DocumentFieldType.OBJECT.equals(type) && f.getValueMap() != null) {
            for (Map.Entry<String, DocumentField> e : f.getValueMap().entrySet()) {
                flatten(name + "." + e.getKey(), e.getValue(), target);
            }
            return;
        }
        if (DocumentFieldType.ARRAY.equals(type) && f.getValueList() != null) {
            List<DocumentField> list = f.getValueList();
            for (int i = 0; i < list.size(); i++) {
                flatten(name + "[" + i + "]", list.get(i), target);
            }
            return;
        }
        target.put(name, new RawField(name, typedValue(f), f.getContent(), f.getConfidence(),
                type == null ? null : type.toString()));
    }

    private static String typedValue(DocumentField f) {
        DocumentFieldType t = f.getType();
        Object v = null;
        if (DocumentFieldType.STRING.equals(t)) {
            v = f.getValueString();
        } else if (DocumentFieldType.DATE.equals(t)) {
            v = f.getValueDate();
        } else if (DocumentFieldType.NUMBER.equals(t)) {
            v = f.getValueNumber();
        } else if (DocumentFieldType.INTEGER.equals(t)) {
            v = f.getValueInteger();
        } else if (DocumentFieldType.PHONE_NUMBER.equals(t)) {
            v = f.getValuePhoneNumber();
        } else if (DocumentFieldType.SIGNATURE.equals(t)) {
            v = f.getValueSignature();                    // "signed" / "unsigned"
        } else if (DocumentFieldType.SELECTION_MARK.equals(t)) {
            v = f.getValueSelectionMark();                // "selected" / "unselected"
        } else if (DocumentFieldType.CURRENCY.equals(t) && f.getValueCurrency() != null) {
            v = f.getValueCurrency().getAmount();
        }
        return v == null ? null : v.toString();
    }

    private AnalyzeResult await(SyncPoller<AnalyzeOperationDetails, AnalyzeResult> poller, String what) {
        poller.setPollInterval(pollInterval);
        PollResponse<AnalyzeOperationDetails> response = poller.waitForCompletion(timeout);
        LongRunningOperationStatus status = response.getStatus();
        if (status != LongRunningOperationStatus.SUCCESSFULLY_COMPLETED) {
            // Still running after the timeout -> try again later. FAILED/cancelled -> the document itself is the problem.
            boolean stillRunning = status == LongRunningOperationStatus.IN_PROGRESS
                    || status == LongRunningOperationStatus.NOT_STARTED;
            throw new DocIntelException("Azure operation '" + what + "' ended with status " + status, stillRunning);
        }
        return poller.getFinalResult();
    }

    private static String pageRange(List<BoundingRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            return null;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (BoundingRegion r : regions) {
            min = Math.min(min, r.getPageNumber());
            max = Math.max(max, r.getPageNumber());
        }
        return min == max ? String.valueOf(min) : min + "-" + max;
    }

    private static String toJson(AnalyzeResult result) {
        try (StringWriter sw = new StringWriter(); JsonWriter jw = JsonProviders.createWriter(sw)) {
            result.toJson(jw);
            jw.flush();
            return sw.toString();
        } catch (IOException e) {
            log.warn("Could not serialize AnalyzeResult: {}", e.getMessage());
            return null;
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new DocIntelException("Cannot read " + file, e);
        }
    }
}
