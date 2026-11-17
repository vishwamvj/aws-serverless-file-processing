package com.cloud.fileprocessing;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FileValidatorHandler
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png");

    private static final Set<String> DOCUMENT_EXTENSIONS =
            Set.of("docx");

    private static final Set<String> TEXT_EXTENSIONS =
            Set.of("txt");

    // SQS client using default Lambda execution credentials and region
    private static final SqsClient sqsClient = SqsClient.builder()
            .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
            .build();

    @Override
    public Map<String, Object> handleRequest(
            Map<String, Object> event,
            Context context) {

        List<Map<String, Object>> results = new ArrayList<>();

        // Handle S3 event notification
        if (event.containsKey("Records")) {
            Object recordsObject = event.get("Records");

            if (!(recordsObject instanceof List<?> records) || records.isEmpty()) {
                throw new IllegalArgumentException("Invalid S3 event: missing or empty Records");
            }

            for (Object recordObject : records) {
                if (!(recordObject instanceof Map<?, ?> record)) {
                    throw new IllegalArgumentException("Invalid S3 event record structure");
                }

                Object s3Object = record.get("s3");
                if (!(s3Object instanceof Map<?, ?> s3)) {
                    throw new IllegalArgumentException("Missing S3 details in event record");
                }

                Object bucketObject = s3.get("bucket");
                Object objectObject = s3.get("object");

                if (!(bucketObject instanceof Map<?, ?> bucket) || !(objectObject instanceof Map<?, ?> object)) {
                    throw new IllegalArgumentException("Missing bucket or object details");
                }

                String bucketName = String.valueOf(bucket.get("name"));
                String encodedKey = String.valueOf(object.get("key"));

                // S3 object keys are URL-encoded
                String objectKey = URLDecoder.decode(
                        encodedKey.replace("+", "%2B"),
                        StandardCharsets.UTF_8);

                String fileName = objectKey.contains("/")
                        ? objectKey.substring(objectKey.lastIndexOf('/') + 1)
                        : objectKey;

                Map<String, Object> result = processFile(fileName, bucketName, objectKey, context);
                results.add(result);
            }
        } else {
            // Support for manual Lambda console test events
            String fileName = String.valueOf(event.get("fileName"));
            String bucketName = event.get("bucketName") == null ? null : String.valueOf(event.get("bucketName"));
            String objectKey = event.get("objectKey") == null ? fileName : String.valueOf(event.get("objectKey"));

            results.add(processFile(fileName, bucketName, objectKey, context));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("results", results);
        response.put("processedCount", results.size());

        return response;
    }

    private Map<String, Object> processFile(
            String fileName,
            String bucketName,
            String objectKey,
            Context context) {

        // Extract extension
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < fileName.length() - 1) {
            extension = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        }

        // Classify file type
        String fileType;
        boolean supported;

        if (IMAGE_EXTENSIONS.contains(extension)) {
            fileType = "IMAGE";
            supported = true;
        } else if ("pdf".equals(extension)) {
            fileType = "PDF";
            supported = true;
        } else if (DOCUMENT_EXTENSIONS.contains(extension)) {
            fileType = "DOCUMENT";
            supported = false;
        } else if (TEXT_EXTENSIONS.contains(extension)) {
            fileType = "TEXT";
            supported = false;
        } else {
            fileType = "UNSUPPORTED";
            supported = false;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("fileName", fileName);
        result.put("fileType", fileType);
        result.put("supported", supported);

        if (bucketName != null) {
            result.put("bucketName", bucketName);
        }

        // Route supported IMAGE and PDF files to their respective SQS queues
        if (bucketName != null && supported) {
            String queueUrl = null;

            if ("IMAGE".equals(fileType)) {
                queueUrl = System.getenv("IMAGE_QUEUE_URL");
            } else if ("PDF".equals(fileType)) {
                queueUrl = System.getenv("PDF_QUEUE_URL");
            }

            if (queueUrl != null && !queueUrl.isBlank()) {
                String messageBody = "{"
                        + "\"bucketName\":\"" + escapeJson(bucketName) + "\","
                        + "\"objectKey\":\"" + escapeJson(objectKey) + "\","
                        + "\"fileName\":\"" + escapeJson(fileName) + "\","
                        + "\"fileType\":\"" + fileType + "\""
                        + "}";

                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(messageBody)
                        .build());

                result.put("sqsMessageSent", true);
                result.put("destinationQueue", queueUrl);
            } else if ("IMAGE".equals(fileType) || "PDF".equals(fileType)) {
                String missingEnvVar = "IMAGE".equals(fileType) ? "IMAGE_QUEUE_URL" : "PDF_QUEUE_URL";
                throw new IllegalStateException("Missing environment variable: " + missingEnvVar);
            } else {
                result.put("sqsMessageSent", false);
            }
        } else {
            result.put("sqsMessageSent", false);
        }

        String logMessage = "File validation result: " + result;
        if (context != null) {
            context.getLogger().log(logMessage + "\n");
        } else {
            System.out.println(logMessage);
        }

        return result;
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
