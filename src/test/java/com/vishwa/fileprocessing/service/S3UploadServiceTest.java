package com.vishwa.fileprocessing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class S3UploadServiceTest {

    @Mock
    private S3Client s3Client;

    private S3UploadService uploadService;

    @BeforeEach
    void setUp() throws Exception {
        uploadService = new S3UploadService(s3Client);
        setField(uploadService, "bucketName", "test-input-bucket");
        setField(uploadService, "outputBucketName", "test-output-bucket");
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testResolveProcessedKeyForPdf() {
        assertEquals("processed/document.pdf-extracted.txt", uploadService.resolveProcessedKey("document.pdf"));
        assertEquals("processed/uuid-1234-doc.pdf-extracted.txt", uploadService.resolveProcessedKey("uuid-1234-doc.pdf"));
        assertEquals("processed/doc.test.pdf-extracted.txt", uploadService.resolveProcessedKey("doc.test.pdf"));
    }

    @Test
    void testResolveProcessedKeyForImages() {
        assertEquals("processed/photo-optimized.jpg", uploadService.resolveProcessedKey("photo.jpg"));
        assertEquals("processed/photo-optimized.jpg", uploadService.resolveProcessedKey("photo.jpeg"));
        assertEquals("processed/image-optimized.png", uploadService.resolveProcessedKey("image.png"));
    }

    @Test
    void testResolveProcessedKeyAlreadyProcessed() {
        assertEquals("processed/doc-extracted.txt", uploadService.resolveProcessedKey("processed/doc-extracted.txt"));
        assertEquals("processed/photo-optimized.jpg", uploadService.resolveProcessedKey("processed/photo-optimized.jpg"));
    }

    @Test
    void testResolveProcessedPdfKey() {
        assertEquals("processed/report.pdf-extracted.txt", uploadService.resolveProcessedPdfKey("report.pdf"));
        assertEquals("processed/report-extracted.txt", uploadService.resolveProcessedPdfKey("report"));
        assertEquals("processed/report-extracted.txt", uploadService.resolveProcessedPdfKey("processed/report-extracted.txt"));
    }

    @Test
    void testUploadFileUsesRepeatableByteBodyAndPreservesContent() throws Exception {
        byte[] content = "original upload content".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("sample.jpg");
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getBytes()).thenReturn(content);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String key = uploadService.uploadFile(file);

        ArgumentCaptor<PutObjectRequest> requestCaptor =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor =
                ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());
        assertTrue(key.endsWith("-sample.jpg"));
        assertEquals(key, requestCaptor.getValue().key());
        assertEquals("image/jpeg", requestCaptor.getValue().contentType());
        assertEquals((long) content.length, requestCaptor.getValue().contentLength());
        verify(file, times(1)).getBytes();
        verify(file, never()).getInputStream();

        RequestBody requestBody = bodyCaptor.getValue();
        for (int attempt = 0; attempt < 2; attempt++) {
            try (InputStream stream =
                    requestBody.contentStreamProvider().newStream()) {
                assertArrayEquals(content, stream.readAllBytes());
            }
        }
    }

    @Test
    void testIsPdfKey() {
        assertTrue(uploadService.isPdfKey("file.pdf"));
        assertTrue(uploadService.isPdfKey("FILE.PDF"));
        assertTrue(uploadService.isPdfKey("file-extracted.txt"));
        assertFalse(uploadService.isPdfKey("file.jpg"));
        assertFalse(uploadService.isPdfKey("file.png"));
        assertFalse(uploadService.isPdfKey(null));
        assertFalse(uploadService.isPdfKey(""));
    }

    @Test
    void testIsProcessedFileReady_Success() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        assertTrue(uploadService.isProcessedFileReady("document.pdf"));
        assertTrue(uploadService.isProcessedPdfReady("document.pdf"));
        assertTrue(uploadService.isProcessedImageReady("image.jpg"));
    }

    @Test
    void testIsProcessedFileReady_NoSuchKey() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("Key not found").build());

        assertFalse(uploadService.isProcessedFileReady("document.pdf"));
        assertFalse(uploadService.isProcessedPdfReady("document.pdf"));
    }

    @Test
    void testIsProcessedFileReady_S3Exception404() {
        S3Exception s3Ex = (S3Exception) S3Exception.builder()
                .statusCode(404)
                .message("Not found")
                .build();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Ex);

        assertFalse(uploadService.isProcessedFileReady("document.pdf"));
    }

    @Test
    void testIsProcessedFileReady_S3Exception403_ListBucket() {
        software.amazon.awssdk.awscore.exception.AwsErrorDetails errorDetails =
                software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode("AccessDenied")
                        .errorMessage("User is not authorized to perform: s3:ListBucket on resource")
                        .build();
        S3Exception s3Ex = (S3Exception) S3Exception.builder()
                .statusCode(403)
                .awsErrorDetails(errorDetails)
                .build();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Ex);

        assertFalse(uploadService.isProcessedFileReady("document.pdf"));
        assertTrue(uploadService.isMissingObjectError(s3Ex));
    }

    @Test
    void testDownloadProcessedFile() {
        byte[] content = "Extracted sample text".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse responseMeta = GetObjectResponse.builder()
                .contentType("text/plain; charset=utf-8")
                .contentLength((long) content.length)
                .build();

        ResponseInputStream<GetObjectResponse> mockStream = new ResponseInputStream<>(
                responseMeta,
                AbortableInputStream.create(new ByteArrayInputStream(content))
        );

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);

        ResponseInputStream<GetObjectResponse> resultStream = uploadService.downloadProcessedFile("document.pdf");
        assertNotNull(resultStream);
        assertEquals("text/plain; charset=utf-8", resultStream.response().contentType());
    }
}
