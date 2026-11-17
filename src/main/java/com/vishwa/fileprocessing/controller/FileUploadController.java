package com.vishwa.fileprocessing.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.vishwa.fileprocessing.service.S3UploadService;
import com.vishwa.fileprocessing.service.MetadataApiClient;
import com.vishwa.fileprocessing.service.MetadataApiClient.MetadataApiException;
import com.vishwa.fileprocessing.service.MetadataApiClient.ProcessedMetadata;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    private final S3UploadService uploadService;
    private final MetadataApiClient metadataApiClient;

    public FileUploadController(
            S3UploadService uploadService,
            MetadataApiClient metadataApiClient) {
        this.uploadService = uploadService;
        this.metadataApiClient = metadataApiClient;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            String key = uploadService.uploadFile(file);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "File uploaded successfully!");
            response.put("fileKey", key);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid file upload request: {}", e.getMessage());
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorBody);

        } catch (S3Exception e) {
            String awsErrorCode = (e.awsErrorDetails() != null && e.awsErrorDetails().errorCode() != null)
                    ? e.awsErrorDetails().errorCode()
                    : "S3Error";
            String awsErrorMessage = (e.awsErrorDetails() != null && e.awsErrorDetails().errorMessage() != null)
                    ? e.awsErrorDetails().errorMessage()
                    : e.getMessage();

            int statusCode = e.statusCode() > 0 ? e.statusCode() : HttpStatus.INTERNAL_SERVER_ERROR.value();
            HttpStatus status = HttpStatus.resolve(statusCode);
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }

            String detail = String.format("AWS S3 Error [%s]: %s", awsErrorCode, awsErrorMessage);
            log.error("AWS S3 upload error (status {}): {}", statusCode, detail, e);

            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("error", detail);
            errorBody.put("awsErrorCode", awsErrorCode);
            return ResponseEntity.status(status).body(errorBody);

        } catch (SdkException e) {
            String detail = "AWS SDK Error: " + e.getMessage();
            log.error("AWS SDK error: {}", detail, e);
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("error", detail);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody);

        } catch (IOException e) {
            String detail = "Could not read the uploaded file: " + e.getMessage();
            log.error("File I/O error during upload: {}", detail, e);
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("error", detail);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody);

        } catch (Exception e) {
            String detail = "Unexpected server error: " + e.getMessage();
            log.error("Unexpected error during upload: {}", detail, e);
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("error", detail);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody);
        }
    }

    /**
     * API to check if the processed file (optimized image or extracted PDF text) is ready in the output bucket.
     * Returns UPLOADED before metadata is available, then reflects processing and output readiness.
     */
    @GetMapping({"/status", "/status/pdf", "/pdf/status"})
    public ResponseEntity<Map<String, Object>> checkStatus(@RequestParam("key") String fileKey) {
        try {
            if (fileKey == null || fileKey.isBlank()) {
                throw new IllegalArgumentException(
                        "File key must not be empty.");
            }

            ProcessedMetadata metadata =
                    metadataApiClient.findByFileId(fileKey)
                            .orElse(null);

            String processedKey = metadata == null
                    ? null
                    : metadata.outputKey();
            boolean ready = false;
            String status = metadata == null
                    ? "UPLOADED"
                    : "PROCESSING";

            if (metadata != null) {
                if ("FAILED".equalsIgnoreCase(metadata.status())) {
                    status = "FAILED";
                } else if (processedKey != null) {
                    ready = uploadService.isProcessedFileReady(processedKey);
                    status = ready ? "COMPLETED" : "PROCESSING";
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("fileKey", fileKey);
            response.put("processedKey", processedKey);
            response.put("ready", ready);
            response.put("status", status);
            response.put("metadataAvailable", metadata != null);
            response.put("fileName", metadata == null
                    ? null
                    : metadata.fileName());
            response.put("fileType", metadata == null
                    ? null
                    : metadata.fileType());
            response.put("inputBucket", metadata == null
                    ? null
                    : metadata.inputBucket());
            response.put("inputKey", metadata == null
                    || metadata.inputKey() == null
                    ? fileKey
                    : metadata.inputKey());
            response.put("outputBucket", metadata == null
                    ? null
                    : metadata.outputBucket());
            response.put("outputKey", processedKey);
            response.put("processedAt", metadata == null
                    ? null
                    : metadata.processedAt());
            response.put("pageCount", metadata == null
                    ? null
                    : metadata.pageCount());
            response.put("pdfSizeBytes", metadata == null
                    ? null
                    : metadata.pdfSizeBytes());
            response.put("extractedTextBytes", metadata == null
                    ? null
                    : metadata.extractedTextBytes());
            response.put("originalSizeBytes", metadata == null
                    ? null
                    : metadata.originalSizeBytes());
            response.put("optimizedSizeBytes", metadata == null
                    ? null
                    : metadata.optimizedSizeBytes());
            response.put("processedSizeBytes", metadata == null
                    ? null
                    : metadata.processedSizeBytes());
            response.put("sizeReductionPercent", metadata == null
                    ? null
                    : metadata.sizeReductionPercent());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);

        } catch (MetadataApiException e) {
            log.error("Metadata API error checking status for key '{}': {}",
                    fileKey, e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);

        } catch (S3Exception e) {
            log.error("S3 error checking status for key '{}': {}", fileKey, e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "AWS S3 Error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * API to download the processed file (optimized image or extracted PDF text) from the output bucket.
     */
    @GetMapping("/download")
    public ResponseEntity<?> download(@RequestParam("key") String fileKey) {
        return serveProcessedDownload(fileKey, false);
    }

    /**
     * Dedicated API endpoint to download the extracted PDF TXT file from the output bucket.
     */
    @GetMapping({"/download/pdf", "/pdf/download"})
    public ResponseEntity<?> downloadPdf(@RequestParam("key") String fileKey) {
        return serveProcessedDownload(fileKey, true);
    }

    private ResponseEntity<?> serveProcessedDownload(String fileKey, boolean forcePdf) {
        try {
            ResponseInputStream<GetObjectResponse> s3Stream = forcePdf
                    ? uploadService.downloadProcessedPdf(fileKey)
                    : uploadService.downloadProcessedFile(fileKey);
            GetObjectResponse responseMeta = s3Stream.response();

            String downloadName = forcePdf
                    ? uploadService.resolveProcessedPdfKey(fileKey)
                    : uploadService.resolveProcessedKey(fileKey);

            if (downloadName.contains("/")) {
                downloadName = downloadName.substring(downloadName.lastIndexOf('/') + 1);
            }

            MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
            if (responseMeta.contentType() != null && !responseMeta.contentType().isBlank()) {
                try {
                    mediaType = MediaType.parseMediaType(responseMeta.contentType());
                } catch (Exception ignored) {
                }
            }

            // Ensure proper media type fallback when S3 default is binary/generic
            if (mediaType.equals(MediaType.APPLICATION_OCTET_STREAM)) {
                if (downloadName.endsWith(".txt")) {
                    mediaType = MediaType.valueOf("text/plain;charset=UTF-8");
                } else if (downloadName.endsWith(".jpg") || downloadName.endsWith(".jpeg")) {
                    mediaType = MediaType.IMAGE_JPEG;
                } else if (downloadName.endsWith(".png")) {
                    mediaType = MediaType.IMAGE_PNG;
                }
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                    .contentType(mediaType)
                    .contentLength(responseMeta.contentLength())
                    .body(new InputStreamResource(s3Stream));

        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);

        } catch (NoSuchKeyException e) {
            boolean isPdf = forcePdf || uploadService.isPdfKey(fileKey);
            String itemType = isPdf ? "Extracted TXT file" : "Processed image";
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", itemType + " is not ready yet in the output bucket.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);

        } catch (S3Exception e) {
            if (e.statusCode() == 404 || uploadService.isMissingObjectError(e)) {
                boolean isPdf = forcePdf || uploadService.isPdfKey(fileKey);
                String itemType = isPdf ? "Extracted TXT file" : "Processed image";
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", itemType + " not found in the output bucket.");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }
            log.error("S3 error downloading processed file for key '{}': {}", fileKey, e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "AWS S3 Error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
