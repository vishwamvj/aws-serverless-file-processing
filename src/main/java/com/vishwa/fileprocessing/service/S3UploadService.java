
package com.vishwa.fileprocessing.service;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class S3UploadService {

    private static final Logger log =
            LoggerFactory.getLogger(S3UploadService.class);

    private final S3Client s3Client;

    @Value("${aws.s3.input-bucket}")
    private String bucketName;

    @Value("${aws.s3.output-bucket}")
    private String outputBucketName;

    public S3UploadService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public String getBucketName() {
        return bucketName;
    }

    public String getOutputBucketName() {
        return outputBucketName;
    }

    // ==========================================
    // 1. UPLOAD ORIGINAL FILE TO INPUT S3 BUCKET
    // ==========================================

    public String uploadFile(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "Please select a file to upload.");
        }

        String originalName = file.getOriginalFilename();

        if (originalName != null) {
            int lastSeparator = Math.max(
                    originalName.lastIndexOf('/'),
                    originalName.lastIndexOf('\\'));

            if (lastSeparator >= 0) {
                originalName =
                        originalName.substring(lastSeparator + 1);
            }
        }

        String safeName =
                (originalName == null || originalName.isBlank())
                        ? "uploaded-file"
                        : originalName.replaceAll(
                                "[^a-zA-Z0-9._-]", "_");

        String key = UUID.randomUUID() + "-" + safeName;

        String contentType = file.getContentType();

        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        try {
            byte[] fileBytes = file.getBytes();

            log.info(
                    "Uploading '{}' to bucket '{}' with key '{}'",
                    safeName, bucketName, key);

            PutObjectRequest request =
                    PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .contentType(contentType)
                            .contentLength((long) fileBytes.length)
                            .build();

            s3Client.putObject(
                    request,
                    RequestBody.fromBytes(fileBytes));

            log.info("Upload successful: {}", key);

            return key;

        } catch (S3Exception e) {
            log.error("S3 upload failed: {}", e.getMessage(), e);
            throw e;

        } catch (SdkException e) {
            log.error("AWS SDK upload error: {}", e.getMessage(), e);
            throw e;
        }
    }

    // ==========================================
    // 2. CHECK WHETHER FILE IS A PDF
    // ==========================================

    public boolean isPdfKey(String rawKey) {

        if (rawKey == null || rawKey.isBlank()) {
            return false;
        }

        String trimmed = rawKey.trim().toLowerCase();

        return trimmed.endsWith(".pdf")
                || trimmed.endsWith("-extracted.txt");
    }

    // ==========================================
    // 3. RESOLVE PROCESSED IMAGE OR PDF KEY
    // ==========================================

    public String resolveProcessedKey(String rawKey) {

        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalArgumentException(
                    "File key must not be empty.");
        }

        String trimmed = rawKey.trim();

        if (trimmed.startsWith("processed/")) {

            if (trimmed.endsWith("-extracted.txt")
                    || trimmed.contains("-optimized.")) {
                return trimmed;
            }

            trimmed =
                    trimmed.substring("processed/".length());
        }

        String fileName = trimmed.contains("/")
                ? trimmed.substring(
                        trimmed.lastIndexOf('/') + 1)
                : trimmed;

        if (fileName.endsWith("-extracted.txt")
                || fileName.contains("-optimized.")) {
            return "processed/" + fileName;
        }

        int lastDot = fileName.lastIndexOf('.');

        if (lastDot <= 0) {
            return "processed/" + fileName
                    + "-optimized.jpg";
        }

        String ext = fileName.substring(lastDot + 1)
                .toLowerCase();

        // IMPORTANT: Keep the .pdf extension.
        if ("pdf".equals(ext)) {
            return "processed/" + fileName
                    + "-extracted.txt";
        }

        String baseName =
                fileName.substring(0, lastDot);

        String outputFormat =
                ext.equals("jpeg") ? "jpg" : ext;

        return "processed/" + baseName
                + "-optimized." + outputFormat;
    }

    // ==========================================
    // 4. RESOLVE EXACT EXTRACTED PDF TXT KEY
    // ==========================================

    public String resolveProcessedPdfKey(String rawKey) {

        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalArgumentException(
                    "File key must not be empty.");
        }

        String trimmed = rawKey.trim();

        // Already an extracted TXT key.
        if (trimmed.endsWith("-extracted.txt")) {

            return trimmed.startsWith("processed/")
                    ? trimmed
                    : "processed/" + trimmed;
        }

        // Remove processed/ if supplied.
        if (trimmed.startsWith("processed/")) {
            trimmed =
                    trimmed.substring("processed/".length());
        }

        String fileName = trimmed.contains("/")
                ? trimmed.substring(
                        trimmed.lastIndexOf('/') + 1)
                : trimmed;

        // Preserve the complete original PDF filename.
        return "processed/" + fileName
                + "-extracted.txt";
    }

    // ==========================================
    // 5. CHECK PROCESSED FILE AVAILABILITY
    // ==========================================

    public boolean isProcessedFileReady(String rawKey) {

        String processedKey =
                resolveProcessedKey(rawKey);

        try {
            s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(outputBucketName)
                            .key(processedKey)
                            .build());

            return true;

        } catch (NoSuchKeyException e) {
            return false;

        } catch (S3Exception e) {

            if (e.statusCode() == 404
                    || isMissingObjectError(e)) {
                return false;
            }

            throw e;
        }
    }

    // Backward compatibility for image status.
    public boolean isProcessedImageReady(String rawKey) {
        return isProcessedFileReady(rawKey);
    }

    // ==========================================
    // 6. CHECK EXTRACTED PDF TXT AVAILABILITY
    // ==========================================

    public boolean isProcessedPdfReady(String rawKey) {

        String processedKey =
                resolveProcessedPdfKey(rawKey);

        try {
            s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(outputBucketName)
                            .key(processedKey)
                            .build());

            return true;

        } catch (NoSuchKeyException e) {
            return false;

        } catch (S3Exception e) {

            if (e.statusCode() == 404
                    || isMissingObjectError(e)) {
                return false;
            }

            throw e;
        }
    }

    // ==========================================
    // 7. DETECT MISSING S3 OBJECT
    // ==========================================

    public boolean isMissingObjectError(S3Exception e) {

        return e.statusCode() == 404
                || e.statusCode() == 403;
    }

    // ==========================================
    // 8. DOWNLOAD PROCESSED FILE
    // ==========================================

    public ResponseInputStream<GetObjectResponse>
            downloadProcessedFile(String rawKey) {

        String processedKey =
                resolveProcessedKey(rawKey);

        log.info(
                "Downloading processed file '{}' from bucket '{}'",
                processedKey, outputBucketName);

        return s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(outputBucketName)
                        .key(processedKey)
                        .build());
    }

    // Backward compatibility for image downloads.
    public ResponseInputStream<GetObjectResponse>
            downloadProcessedImage(String rawKey) {

        return downloadProcessedFile(rawKey);
    }

    // ==========================================
    // 9. DOWNLOAD EXTRACTED PDF TEXT
    // ==========================================

    public ResponseInputStream<GetObjectResponse>
            downloadProcessedPdf(String rawKey) {

        String processedKey =
                resolveProcessedPdfKey(rawKey);

        log.info(
                "Downloading extracted PDF text '{}' from bucket '{}'",
                processedKey, outputBucketName);

        return s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(outputBucketName)
                        .key(processedKey)
                        .build());
    }
}
