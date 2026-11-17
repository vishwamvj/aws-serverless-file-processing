package com.vishwa.fileprocessing.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class MetadataApiClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${aws.metadata.api-url}")
    private String metadataApiUrl;

    public Optional<ProcessedMetadata> findByFileId(String fileId) {
        URI uri = UriComponentsBuilder.fromUriString(metadataApiUrl)
                .pathSegment(fileId)
                .build()
                .encode()
                .toUri();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetadataApiException(
                    "Metadata API request was interrupted.", e);
        } catch (IOException e) {
            throw new MetadataApiException(
                    "Metadata API request could not be completed.", e);
        }

        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() < 200
                || response.statusCode() >= 300) {
            throw new MetadataApiException(
                    "Metadata API returned HTTP "
                            + response.statusCode() + ".");
        }

        try {
            JsonNode item = mapper.readTree(response.body());
            String returnedFileId = requiredText(item, "fileId");
            if (!fileId.equals(returnedFileId)) {
                throw new MetadataApiException(
                        "Metadata API returned a different file identifier.");
            }

            return Optional.of(new ProcessedMetadata(
                    returnedFileId,
                    requiredText(item, "fileType"),
                    optionalText(item, "fileName"),
                    requiredText(item, "status"),
                    optionalText(item, "inputBucket"),
                    optionalText(item, "inputKey"),
                    optionalText(item, "outputBucket"),
                    optionalText(item, "outputKey"),
                    optionalText(item, "processedAt"),
                    optionalLong(item, "pageCount"),
                    optionalLong(item, "pdfSizeBytes"),
                    optionalLong(item, "extractedTextBytes"),
                    optionalLong(item, "originalSizeBytes"),
                    optionalLong(item, "optimizedSizeBytes"),
                    optionalLong(item, "processedSizeBytes"),
                    optionalDouble(item, "sizeReductionPercent")));
        } catch (IOException | IllegalArgumentException e) {
            throw new MetadataApiException(
                    "Metadata API returned invalid metadata.", e);
        }
    }

    private static String requiredText(JsonNode item, String field) {
        JsonNode value = item == null ? null : item.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Metadata field is missing: " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode item, String field) {
        JsonNode value = item == null ? null : item.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText();
    }

    private static Long optionalLong(JsonNode item, String field) {
        JsonNode value = item == null ? null : item.get(field);
        return value != null && value.isNumber()
                ? value.longValue()
                : null;
    }

    private static Double optionalDouble(JsonNode item, String field) {
        JsonNode value = item == null ? null : item.get(field);
        return value != null && value.isNumber()
                ? value.doubleValue()
                : null;
    }

    public record ProcessedMetadata(
            String fileId,
            String fileType,
            String fileName,
            String status,
            String inputBucket,
            String inputKey,
            String outputBucket,
            String outputKey,
            String processedAt,
            Long pageCount,
            Long pdfSizeBytes,
            Long extractedTextBytes,
            Long originalSizeBytes,
            Long optimizedSizeBytes,
            Long processedSizeBytes,
            Double sizeReductionPercent) {
    }

    public static class MetadataApiException extends RuntimeException {
        public MetadataApiException(String message) {
            super(message);
        }

        public MetadataApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
