package com.cloud.imageprocessing;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.coobird.thumbnailator.Thumbnails;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImageProcessingHandler
        implements RequestHandler<Map<String, Object>, String> {

    private static final Region REGION = resolveRegion();

    private static final S3Client s3 = S3Client.builder()
            .region(REGION)
            .build();

    private static final SnsClient sns = SnsClient.builder()
            .region(REGION)
            .build();

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final int MAX_DIMENSION = 1600;

    @Override
    public String handleRequest(
            Map<String, Object> event, Context context) {

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> records =
                    (List<Map<String, Object>>) event.get("Records");

            if (records == null || records.isEmpty()) {
                throw new IllegalArgumentException("No SQS records found in event");
            }

            for (Map<String, Object> record : records) {
                String body = (String) record.get("body");

                Map<String, Object> message = mapper.readValue(
                        body,
                        new TypeReference<Map<String, Object>>() {}
                );

                String inputBucket = (String) message.get("bucketName");
                String objectKey = (String) message.get("objectKey");
                String fileName = (String) message.get("fileName");
                String outputBucket = System.getenv("OUTPUT_BUCKET");

                if (inputBucket == null || objectKey == null || outputBucket == null) {
                    throw new IllegalArgumentException(
                            "Missing bucket or object information (inputBucket: "
                                    + inputBucket + ", objectKey: " + objectKey
                                    + ", outputBucket: " + outputBucket + ")");
                }

                if (fileName == null || !fileName.contains(".")) {
                    fileName = objectKey.contains("/")
                            ? objectKey.substring(objectKey.lastIndexOf('/') + 1)
                            : objectKey;
                }

                String extension = fileName.substring(
                        fileName.lastIndexOf('.') + 1
                ).toLowerCase();

                if (!extension.equals("jpg")
                        && !extension.equals("jpeg")
                        && !extension.equals("png")) {
                    throw new IllegalArgumentException(
                            "Unsupported image format: " + extension);
                }

                String outputFormat = extension.equals("jpeg") ? "jpg" : extension;

                // 1. Download original image from input S3 bucket
                context.getLogger().log("Downloading " + objectKey + " from " + inputBucket + "...\n");
                byte[] originalImage = s3.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(inputBucket)
                                .key(objectKey)
                                .build()
                ).asByteArray();

                long originalSizeBytes = originalImage.length;
                context.getLogger().log("Original image size: " + originalSizeBytes + " bytes\n");

                // 2. Read image dimensions
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(originalImage));
                if (image == null) {
                    throw new IllegalArgumentException("Unable to decode image bytes with ImageIO");
                }

                int width = image.getWidth();
                int height = image.getHeight();
                context.getLogger().log("Original dimensions: " + width + " x " + height + "\n");

                byte[] processedImage = originalImage;

                // 3. Resize only if dimensions exceed limit
                if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

                    Thumbnails.of(new ByteArrayInputStream(originalImage))
                            .size(MAX_DIMENSION, MAX_DIMENSION)
                            .keepAspectRatio(true)
                            .outputFormat(outputFormat)
                            .outputQuality(0.80)
                            .toOutputStream(outputStream);

                    byte[] candidate = outputStream.toByteArray();

                    // Keep candidate only when it is actually smaller
                    if (candidate.length < originalSizeBytes) {
                        processedImage = candidate;
                        context.getLogger().log("Resized image accepted. New size: " + candidate.length + " bytes\n");
                    } else {
                        context.getLogger().log("Resized image was larger (" + candidate.length
                                + " bytes). Keeping original image.\n");
                    }
                } else {
                    context.getLogger().log("Image is already within dimension limit ("
                            + MAX_DIMENSION + "px). No resizing required.\n");
                }

                long optimizedSizeBytes = processedImage.length;
                context.getLogger().log("Final image size: " + optimizedSizeBytes + " bytes\n");
                Double sizeReductionPercent = originalSizeBytes > 0
                        ? ((originalSizeBytes - optimizedSizeBytes) * 100.0)
                                / originalSizeBytes
                        : null;

                // 4. Create output key
                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                String outputKey = "processed/" + baseName + "-optimized." + outputFormat;
                String contentType = outputFormat.equals("png") ? "image/png" : "image/jpeg";

                // 5. Upload processed image to output S3 bucket
                context.getLogger().log("Uploading processed image to " + outputBucket + " as " + outputKey + "...\n");
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(outputBucket)
                                .key(outputKey)
                                .contentType(contentType)
                                .build(),
                        RequestBody.fromBytes(processedImage)
                );

                context.getLogger().log("Final image uploaded successfully: " + outputKey + "\n");

                // 6. Send metadata to MetadataLambda via API Gateway HTTP API
                String apiUrl = System.getenv("METADATA_API_URL");
                if (apiUrl == null || apiUrl.isBlank()) {
                    throw new IllegalStateException(
                            "METADATA_API_URL environment variable is missing.");
                }

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("fileType", "IMAGE");
                metadata.put("fileId", objectKey);
                metadata.put("fileName", fileName);
                metadata.put("inputBucket", inputBucket);
                metadata.put("inputKey", objectKey);
                metadata.put("outputBucket", outputBucket);
                metadata.put("outputKey", outputKey);
                metadata.put("status", "COMPLETED");
                metadata.put("processedAt", Instant.now().toString());
                metadata.put("originalSizeBytes", originalSizeBytes);
                metadata.put("optimizedSizeBytes", optimizedSizeBytes);
                metadata.put("processedSizeBytes", optimizedSizeBytes);
                if (sizeReductionPercent != null) {
                    metadata.put("sizeReductionPercent", sizeReductionPercent);
                }

                String metadataJson = mapper.writeValueAsString(metadata);
                context.getLogger().log("Posting metadata to API Gateway (" + apiUrl + "): " + metadataJson + "\n");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(metadataJson, StandardCharsets.UTF_8))
                        .build();

                try {
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    int statusCode = response.statusCode();

                    if (statusCode >= 200 && statusCode < 300) {
                        context.getLogger().log("Metadata successfully posted for " + objectKey
                                + " [HTTP " + statusCode + "]. Response: " + response.body() + "\n");
                    } else {
                        context.getLogger().log("ERROR: Metadata API returned non-2xx HTTP status code " + statusCode
                                + " for fileId '" + objectKey + "'. Response body: " + response.body() + "\n");
                        throw new RuntimeException("Metadata API request failed with HTTP " + statusCode + ": " + response.body());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Metadata API request was interrupted", e);
                }

                publishNotification(
                        fileName, outputBucket, outputKey, context);

                context.getLogger().log("Image processing completed successfully for: " + fileName + "\n");
            }

            return "Image processing completed successfully";

        } catch (Exception e) {
            context.getLogger().log("Image processing failed: " + e.getMessage() + "\n");
            throw new RuntimeException("Image processing failed", e);
        }
    }

    private void publishNotification(
            String fileName,
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

        Map<String, String> notification = new LinkedHashMap<>();
        notification.put("fileName", fileName);
        notification.put("fileType", "IMAGE");
        notification.put("status", "COMPLETED");
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
                    "SNS notification published successfully. MessageId: "
                            + response.messageId()
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
