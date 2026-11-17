
package com.cloud.pdfprocessing;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

public class PDFProcessingHandler
        implements RequestHandler<SQSEvent, Void> {

    private static final Region REGION =
            resolveRegion();

    private static final String OUTPUT_BUCKET =
            System.getenv("OUTPUT_BUCKET");

    private static final String METADATA_API_URL =
            System.getenv("METADATA_API_URL");

    private static final S3Client s3 =
            S3Client.builder()
                    .region(REGION)
                    .build();

    private static final SnsClient sns =
            SnsClient.builder()
                    .region(REGION)
                    .build();

    private static final HttpClient httpClient =
            HttpClient.newHttpClient();

    private static final ObjectMapper mapper =
            new ObjectMapper();

    @Override
    public Void handleRequest(SQSEvent event, Context context) {

        context.getLogger().log("PDFProcessingLambda invoked.\n");

        for (SQSEvent.SQSMessage message : event.getRecords()) {

            try {
                processPDF(message.getBody(), context);
            } catch (Exception e) {
                context.getLogger().log(
                        "PDF processing failed: " + e.getMessage() + "\n"
                );
                throw new RuntimeException(e);
            }
        }

        return null;
    }

    private void processPDF(String body, Context context)
            throws Exception {

        JsonNode message = mapper.readTree(body);

        String inputBucket = message.get("bucketName").asText();
        String inputKey = message.get("objectKey").asText();
        String fileName = message.has("fileName")
                ? message.get("fileName").asText()
                : inputKey;

        context.getLogger().log(
                "Downloading PDF: " + inputKey + "\n"
        );

        // 1. Download original PDF from input S3 bucket
        GetObjectRequest getRequest =
                GetObjectRequest.builder()
                        .bucket(inputBucket)
                        .key(inputKey)
                        .build();

        byte[] pdfBytes;

        try (ResponseInputStream<?> input =
                     s3.getObject(getRequest);
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            input.transferTo(output);
            pdfBytes = output.toByteArray();
        }

        long pdfSize = pdfBytes.length;

        // 2. Extract selectable text using PDFBox
        String extractedText;
        int pageCount;

        try (PDDocument document = PDDocument.load(pdfBytes)) {

            pageCount = document.getNumberOfPages();

            PDFTextStripper stripper = new PDFTextStripper();
            extractedText = stripper.getText(document);
        }

        context.getLogger().log(
                "Extracted text from " + pageCount
                        + " pages.\n"
        );

        // 3. Create output TXT file
        String uniqueId = UUID.randomUUID().toString();

        String baseName = fileName.replaceAll("(?i)\\.pdf$", "");

        String outputKey =
                "processed/" + uniqueId + "-"
                        + baseName + "-extracted.txt";

        byte[] textBytes =
                extractedText.getBytes(StandardCharsets.UTF_8);

        // 4. Upload extracted TXT to output S3 bucket
        PutObjectRequest putRequest =
                PutObjectRequest.builder()
                        .bucket(OUTPUT_BUCKET)
                        .key(outputKey)
                        .contentType("text/plain")
                        .build();

        s3.putObject(
                putRequest,
                RequestBody.fromBytes(textBytes)
        );

        context.getLogger().log(
                "Extracted TXT uploaded: " + outputKey + "\n"
        );

        // 5. Prepare metadata for API Gateway
        ObjectNode metadata = mapper.createObjectNode();

        metadata.put("fileType", "PDF");
        metadata.put("fileId", inputKey);
        metadata.put("fileName", fileName);
        metadata.put("inputBucket", inputBucket);
        metadata.put("inputKey", inputKey);
        metadata.put("outputBucket", OUTPUT_BUCKET);
        metadata.put("outputKey", outputKey);
        metadata.put("status", "COMPLETED");
        metadata.put("pageCount", pageCount);
        metadata.put("pdfSizeBytes", pdfSize);
        metadata.put("extractedTextBytes", textBytes.length);
        metadata.put("processedAt", Instant.now().toString());

        // 6. Send metadata to API Gateway
        if (METADATA_API_URL == null ||
                METADATA_API_URL.isBlank()) {
            throw new IllegalStateException(
                    "METADATA_API_URL environment variable is missing."
            );
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(METADATA_API_URL))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(metadata)))
                        .build();

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        context.getLogger().log(
                "Metadata API HTTP status: "
                        + response.statusCode() + "\n"
        );

        if (response.statusCode() < 200 ||
                response.statusCode() >= 300) {

            throw new RuntimeException(
                    "Metadata API failed: "
                            + response.statusCode()
                            + " - " + response.body()
            );
        }

        context.getLogger().log(
                "Metadata successfully stored through API Gateway.\n"
        );

        publishNotification(
                fileName, "PDF", "COMPLETED",
                OUTPUT_BUCKET, outputKey, context);

        context.getLogger().log(
                "PDF processing completed successfully.\n"
        );
    }

    private void publishNotification(
            String fileName,
            String fileType,
            String status,
            String outputBucket,
            String outputKey,
            Context context) throws Exception {

        String topicArn = System.getenv("SNS_TOPIC_ARN");
        if (topicArn == null || topicArn.isBlank()) {
            String errorMessage =
                    "SNS_TOPIC_ARN environment variable is missing.";
            context.getLogger().log(
                    "SNS notification publishing failed: "
                            + errorMessage + "\n");
            throw new IllegalStateException(errorMessage);
        }

        ObjectNode notification = mapper.createObjectNode();
        notification.put("fileName", fileName);
        notification.put("fileType", fileType);
        notification.put("status", status);
        notification.put("outputBucket", outputBucket);
        notification.put("outputKey", outputKey);

        String messageBody =
                mapper.writeValueAsString(notification);

        try {
            PublishResponse response = sns.publish(
                    PublishRequest.builder()
                            .topicArn(topicArn)
                            .message(messageBody)
                            .build());

            context.getLogger().log(
                    "SNS notification published successfully. "
                            + "MessageId: " + response.messageId()
                            + ", outputKey: " + outputKey + "\n");
        } catch (SdkException e) {
            context.getLogger().log(
                    "SNS notification publishing failed for outputKey "
                            + outputKey + ": " + e.getMessage() + "\n");
            throw e;
        }
    }

    private static Region resolveRegion() {
        String region = System.getenv("AWS_REGION");
        if (region == null || region.isBlank()) {
            region = System.getenv("AWS_DEFAULT_REGION");
        }
        return Region.of(
                region == null || region.isBlank()
                        ? "us-east-1"
                        : region);
    }
}
