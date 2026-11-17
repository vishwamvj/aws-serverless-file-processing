package com.cloud.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

class MetadataHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DynamoDbClient dynamoDB;
    private Context context;
    private MetadataHandler handler;
    private Function<GetItemRequest, GetItemResponse> getItemBehavior;
    private final List<GetItemRequest> getItemRequests = new ArrayList<>();
    private final List<String> logMessages = new ArrayList<>();
    private PutItemRequest capturedPutItem;

    @BeforeEach
    void setUp() {
        getItemRequests.clear();
        logMessages.clear();
        capturedPutItem = null;
        getItemBehavior = request -> GetItemResponse.builder().build();
        dynamoDB = (DynamoDbClient) Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class<?>[] { DynamoDbClient.class },
                (proxy, method, arguments) -> {
                    if ("getItem".equals(method.getName())) {
                        GetItemRequest request =
                                (GetItemRequest) arguments[0];
                        getItemRequests.add(request);
                        return getItemBehavior.apply(request);
                    }
                    if ("putItem".equals(method.getName())) {
                        capturedPutItem = (PutItemRequest) arguments[0];
                        return PutItemResponse.builder().build();
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(
                            "Unexpected DynamoDB operation: "
                                    + method.getName());
                });
        LambdaLogger logger = (LambdaLogger) Proxy.newProxyInstance(
                LambdaLogger.class.getClassLoader(),
                new Class<?>[] { LambdaLogger.class },
                (proxy, method, arguments) -> {
                    if ("log".equals(method.getName())
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String message) {
                        logMessages.add(message);
                    }
                    return null;
                });
        context = (Context) Proxy.newProxyInstance(
                Context.class.getClassLoader(),
                new Class<?>[] { Context.class },
                (proxy, method, arguments) ->
                        "getLogger".equals(method.getName())
                                ? logger
                                : null);
        handler = new MetadataHandler(dynamoDB);
    }

    @Test
    void getReturnsPdfMetadataFromPdfTable() throws Exception {
        Map<String, AttributeValue> item = Map.of(
                "field", stringValue("input/report.pdf"),
                "fieldId", stringValue("input/report.pdf"),
                "fileId", stringValue("input/report.pdf"),
                "fileType", stringValue("PDF"),
                "outputKey", stringValue("processed/actual-output.txt"),
                "status", stringValue("COMPLETED"),
                "pageCount", AttributeValue.builder().n("3").build());

        getItemBehavior = request ->
                "PDFProcessingMetadata".equals(request.tableName())
                        ? GetItemResponse.builder().item(item).build()
                        : GetItemResponse.builder().build();

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(
                        request(
                                "GET",
                                "fileID",
                                "input/report.pdf",
                                null),
                        context);

        assertEquals(200, response.getStatusCode());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertEquals("input/report.pdf", body.get("fileId").asText());
        assertEquals("processed/actual-output.txt", body.get("outputKey").asText());
        assertEquals(3, body.get("pageCount").asInt());
        assertFalse(body.has("originalSizeBytes"));
        assertFalse(body.has("optimizedSizeBytes"));
        assertFalse(body.has("sizeReductionPercent"));

        assertEquals(2, getItemRequests.size());
        assertTrue(getItemRequests.stream().allMatch(getItemRequest ->
                "input/report.pdf".equals(
                        getItemRequest.key().get("fileId").s())
                        && ("PDFProcessingMetadata".equals(
                                getItemRequest.tableName())
                                ? getItemRequest.key().containsKey("fileId")
                                : "ImageProcessingMetadata".equals(
                                        getItemRequest.tableName())
                                        && getItemRequest.key()
                                                .containsKey("fileId"))));
        assertTrue(logMessages.stream().anyMatch(message ->
                message.contains("PDFProcessingMetadata")
                        && message.contains("fileId 'input/report.pdf'")));
        assertTrue(logMessages.stream().anyMatch(message ->
                message.contains("ImageProcessingMetadata")
                        && message.contains("fileId 'input/report.pdf'")));
    }

    @Test
    void getReturnsImageMetadataFromImageTable() throws Exception {
        Map<String, AttributeValue> item = Map.of(
                "field", stringValue("input/photo.jpg"),
                "fieldId", stringValue("input/photo.jpg"),
                "fileId", stringValue("input/photo.jpg"),
                "fileType", stringValue("IMAGE"),
                "outputKey", stringValue("processed/photo-optimized.jpg"),
                "status", stringValue("COMPLETED"),
                "originalSizeBytes", AttributeValue.builder().n("2048").build(),
                "optimizedSizeBytes", AttributeValue.builder().n("1024").build(),
                "processedSizeBytes", AttributeValue.builder().n("1024").build(),
                "sizeReductionPercent", AttributeValue.builder().n("50.0").build());

        getItemBehavior = request ->
                "ImageProcessingMetadata".equals(request.tableName())
                        ? GetItemResponse.builder().item(item).build()
                        : GetItemResponse.builder().build();

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(
                        request("GET", "input/photo.jpg", null),
                        context);

        assertEquals(200, response.getStatusCode());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertEquals("IMAGE", body.get("fileType").asText());
        assertEquals("processed/photo-optimized.jpg",
                body.get("outputKey").asText());
        assertTrue(body.get("originalSizeBytes").isNumber());
        assertEquals(2048, body.get("originalSizeBytes").asLong());
        assertTrue(body.get("optimizedSizeBytes").isNumber());
        assertEquals(1024, body.get("optimizedSizeBytes").asLong());
        assertTrue(body.get("sizeReductionPercent").isNumber());
        assertEquals(50.0, body.get("sizeReductionPercent").asDouble());
        assertTrue(getItemRequests.stream().anyMatch(getItemRequest ->
                "ImageProcessingMetadata".equals(
                        getItemRequest.tableName())
                        && getItemRequest.key().containsKey("fileId")));
    }

    @Test
    void getReturnsNotFoundWhenMetadataDoesNotExist() throws Exception {
        APIGatewayV2HTTPResponse response =
                handler.handleRequest(
                        request("GET", "missing.pdf", null),
                        context);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void getReturnsBadRequestWhenNeitherPathParameterIsPresent() throws Exception {
        APIGatewayV2HTTPResponse response =
                handler.handleRequest(
                        request("GET", null, null, null),
                        context);

        assertEquals(400, response.getStatusCode());
        assertTrue(getItemRequests.isEmpty());
    }

    @Test
    void getReturnsServerErrorWhenDynamoDbFails() throws Exception {
        getItemBehavior = request -> {
            throw new IllegalStateException("DynamoDB unavailable");
        };

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(
                        request("GET", "input/report.pdf", null),
                        context);

        assertEquals(500, response.getStatusCode());
        assertTrue(logMessages.stream().anyMatch(message ->
                message.contains("java.lang.IllegalStateException")
                        && message.contains("DynamoDB unavailable")));
    }

    @Test
    void postKeepsFileIdAndWritesPartitionKey() throws Exception {
        String postBody = """
                {
                  "fileType": "PDF",
                  "fileId": "input/report.pdf",
                  "fileName": "report.pdf",
                  "inputBucket": "input-bucket",
                  "inputKey": "input/report.pdf",
                  "outputBucket": "output-bucket",
                  "outputKey": "processed/actual-output.txt",
                  "status": "COMPLETED"
                }
                """;

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(
                        request("POST", null, postBody),
                        context);

        assertEquals(200, response.getStatusCode());
        assertTrue(capturedPutItem != null);
        assertEquals("input/report.pdf",
                capturedPutItem.item().get("field").s());
        assertEquals("input/report.pdf",
                capturedPutItem.item().get("fieldId").s());
        assertEquals("input/report.pdf",
                capturedPutItem.item().get("fileId").s());
    }

    @Test
    void postStoresImageStatisticsAsDynamoDbNumbers() throws Exception {
        String postBody = """
                {
                  "fileType": "IMAGE",
                  "fileId": "input/photo.jpg",
                  "originalSizeBytes": 2048,
                  "optimizedSizeBytes": 1024,
                  "sizeReductionPercent": 50.0
                }
                """;

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(
                        request("POST", null, postBody),
                        context);

        assertEquals(200, response.getStatusCode());
        assertTrue(capturedPutItem != null);
        assertEquals("ImageProcessingMetadata",
                capturedPutItem.tableName());
        assertEquals("2048",
                capturedPutItem.item().get("originalSizeBytes").n());
        assertEquals("1024",
                capturedPutItem.item().get("optimizedSizeBytes").n());
        assertEquals("50.0",
                capturedPutItem.item().get("sizeReductionPercent").n());
    }

    private static APIGatewayV2HTTPEvent request(
            String method,
            String fileId,
            String body) throws Exception {
        return request(method, "fileId", fileId, body);
    }

    private static APIGatewayV2HTTPEvent request(
            String method,
            String pathParameterName,
            String fileId,
            String body) throws Exception {

        ObjectNode event = MAPPER.createObjectNode();
        event.put("version", "2.0");
        String routeParameter = pathParameterName == null
                ? "fileId"
                : pathParameterName;
        event.put("routeKey",
                method + " /metadata/{" + routeParameter + "}");
        event.put("rawPath", fileId == null
                ? "/metadata/"
                : "/metadata/" + fileId);
        if (pathParameterName != null && fileId != null) {
            event.putObject("pathParameters")
                    .put(pathParameterName, fileId);
        }
        if (body != null) {
            event.put("body", body);
        }
        event.putObject("requestContext")
                .putObject("http")
                .put("method", method);

        return MAPPER.treeToValue(
                event, APIGatewayV2HTTPEvent.class);
    }

    private static AttributeValue stringValue(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
