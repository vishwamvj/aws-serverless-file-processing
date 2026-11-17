package com.vishwa.fileprocessing.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vishwa.fileprocessing.service.S3UploadService;
import com.vishwa.fileprocessing.service.MetadataApiClient;
import com.vishwa.fileprocessing.service.MetadataApiClient.ProcessedMetadata;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class FileUploadControllerTest {

    private MockMvc mockMvc;

    @Mock
    private S3UploadService uploadService;

    @Mock
    private MetadataApiClient metadataApiClient;

    @InjectMocks
    private FileUploadController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testPdfStatus_Completed() throws Exception {
        String actualOutputKey =
                "processed/9ecf6c2b-report-extracted.txt";
        when(metadataApiClient.findByFileId("sample.pdf"))
                .thenReturn(Optional.of(new ProcessedMetadata(
                        "sample.pdf",
                        "PDF",
                        "report.pdf",
                        "COMPLETED",
                        "input-bucket",
                        "sample.pdf",
                        "output-bucket",
                        actualOutputKey,
                        "2026-11-17T01:00:00Z",
                        2L,
                        1024L,
                        256L,
                        null,
                        null,
                        null,
                        null)));
        when(uploadService.isProcessedFileReady(actualOutputKey))
                .thenReturn(true);

        mockMvc.perform(get("/api/files/status").param("key", "sample.pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileKey").value("sample.pdf"))
                .andExpect(jsonPath("$.processedKey").value(actualOutputKey))
                .andExpect(jsonPath("$.fileType").value("PDF"))
                .andExpect(jsonPath("$.inputBucket").value("input-bucket"))
                .andExpect(jsonPath("$.inputKey").value("sample.pdf"))
                .andExpect(jsonPath("$.outputBucket").value("output-bucket"))
                .andExpect(jsonPath("$.outputKey").value(actualOutputKey))
                .andExpect(jsonPath("$.processedAt").value("2026-11-17T01:00:00Z"))
                .andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.extractedTextBytes").value(256))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        verify(uploadService).isProcessedFileReady(actualOutputKey);
    }

    @Test
    void testPdfStatus_MetadataNotYetAvailable() throws Exception {
        when(metadataApiClient.findByFileId("sample.pdf"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/files/status").param("key", "sample.pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileKey").value("sample.pdf"))
                .andExpect(jsonPath("$.processedKey").doesNotExist())
                .andExpect(jsonPath("$.metadataAvailable").value(false))
                .andExpect(jsonPath("$.inputKey").value("sample.pdf"))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.status").value("UPLOADED"));
        verifyNoInteractions(uploadService);
    }

    @Test
    void testImageStatus_Completed() throws Exception {
        String actualOutputKey = "processed/sample-optimized-final.jpg";
        when(metadataApiClient.findByFileId("sample.jpg"))
                .thenReturn(Optional.of(new ProcessedMetadata(
                        "sample.jpg",
                        "IMAGE",
                        "sample.jpg",
                        "COMPLETED",
                        "input-bucket",
                        "sample.jpg",
                        "output-bucket",
                        actualOutputKey,
                        "2026-11-17T01:01:00Z",
                        null,
                        null,
                        null,
                        2048L,
                        1024L,
                        1024L,
                        50.0)));
        when(uploadService.isProcessedFileReady(actualOutputKey))
                .thenReturn(true);

        mockMvc.perform(get("/api/files/status").param("key", "sample.jpg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileKey").value("sample.jpg"))
                .andExpect(jsonPath("$.processedKey").value(actualOutputKey))
                .andExpect(jsonPath("$.originalSizeBytes").value(2048))
                .andExpect(jsonPath("$.optimizedSizeBytes").value(1024))
                .andExpect(jsonPath("$.processedSizeBytes").value(1024))
                .andExpect(jsonPath("$.sizeReductionPercent").value(50.0))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void testPdfStatus_Failed() throws Exception {
        when(metadataApiClient.findByFileId("failed.pdf"))
                .thenReturn(Optional.of(new ProcessedMetadata(
                        "failed.pdf",
                        "PDF",
                        "failed.pdf",
                        "FAILED",
                        "input-bucket",
                        "failed.pdf",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));

        mockMvc.perform(get("/api/files/status").param("key", "failed.pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedKey").doesNotExist())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.status").value("FAILED"));
        verifyNoInteractions(uploadService);
    }

    @Test
    void testDownloadPdf_Success() throws Exception {
        byte[] content = "Extracted text content from PDF".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse getResponse = GetObjectResponse.builder()
                .contentType("text/plain; charset=utf-8")
                .contentLength((long) content.length)
                .build();
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                getResponse,
                AbortableInputStream.create(new ByteArrayInputStream(content))
        );

        when(uploadService.downloadProcessedFile("sample.pdf")).thenReturn(stream);
        when(uploadService.resolveProcessedKey("sample.pdf")).thenReturn("processed/sample-extracted.txt");

        mockMvc.perform(get("/api/files/download").param("key", "sample.pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample-extracted.txt\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Extracted text content from PDF"));
    }

    @Test
    void testDedicatedDownloadPdfEndpoint_Success() throws Exception {
        byte[] content = "Extracted text via dedicated PDF endpoint".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse getResponse = GetObjectResponse.builder()
                .contentType("text/plain; charset=utf-8")
                .contentLength((long) content.length)
                .build();
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                getResponse,
                AbortableInputStream.create(new ByteArrayInputStream(content))
        );

        when(uploadService.downloadProcessedPdf("sample.pdf")).thenReturn(stream);
        when(uploadService.resolveProcessedPdfKey("sample.pdf")).thenReturn("processed/sample-extracted.txt");

        mockMvc.perform(get("/api/files/download/pdf").param("key", "sample.pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample-extracted.txt\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Extracted text via dedicated PDF endpoint"));
    }

    @Test
    void testDownloadImage_Success() throws Exception {
        byte[] imageBytes = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
        GetObjectResponse getResponse = GetObjectResponse.builder()
                .contentType("image/jpeg")
                .contentLength((long) imageBytes.length)
                .build();
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                getResponse,
                AbortableInputStream.create(new ByteArrayInputStream(imageBytes))
        );

        when(uploadService.downloadProcessedFile("sample.jpg")).thenReturn(stream);
        when(uploadService.resolveProcessedKey("sample.jpg")).thenReturn("processed/sample-optimized.jpg");

        mockMvc.perform(get("/api/files/download").param("key", "sample.jpg"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample-optimized.jpg\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(content().bytes(imageBytes));
    }

    @Test
    void testDownload_NotFound() throws Exception {
        when(uploadService.downloadProcessedFile("missing.pdf"))
                .thenThrow(NoSuchKeyException.builder().message("NoSuchKey").build());
        when(uploadService.isPdfKey("missing.pdf")).thenReturn(true);

        mockMvc.perform(get("/api/files/download").param("key", "missing.pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Extracted TXT file is not ready yet in the output bucket."));
    }

    @Test
    void testDownloadPdf_S3MissingObjectError() throws Exception {
        S3Exception s3Ex = (S3Exception) S3Exception.builder()
                .statusCode(403)
                .message("Forbidden")
                .build();
        when(uploadService.downloadProcessedPdf("missing.pdf")).thenThrow(s3Ex);
        when(uploadService.isMissingObjectError(s3Ex)).thenReturn(true);

        mockMvc.perform(get("/api/files/download/pdf").param("key", "missing.pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Extracted TXT file not found in the output bucket."));
    }

    @Test
    void testStatus_InvalidKey() throws Exception {
        mockMvc.perform(get("/api/files/status").param("key", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("File key must not be empty."));
        verifyNoInteractions(metadataApiClient, uploadService);
    }
}
